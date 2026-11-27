package org.example.delivery;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import static org.example.delivery.AuthHttpClient.expect;
import static org.example.delivery.InboundBatchTests.activate;
import static org.example.delivery.InboundBatchTests.login;
import static org.example.delivery.InboundBatchTests.manifest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"identity-demo", "planning-demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"delivery.demo.temporary-password=Demo-Temporary-001", "spring.flyway.target=10"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DraftAdjustmentHttpTests {
    private static final String PLANS = "/staff/delivery-plans";
    private static final String BATCHES = "/staff/inbound-batches";
    private static final String AT = "2026-11-25T08:00:00+08:00";
    private static final String REASON = "东区临时支援";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    private String batchId;
    private String shiftId;
    private String chen;
    private String support;
    private String foreign;
    private String east;
    private String migratedId;
    private JsonNode migratedQuarantine;

    @BeforeAll
    void prepareRealReceiptsAndUpgradeAnExistingDraft() throws Exception {
        activate(port, "dispatcher-zhou");
        activate(port, "operator-xu");
        try (var dispatcher = login(port, "dispatcher-zhou"); var operator = login(port, "operator-xu")) {
            east = expect(200, dispatcher.get("/staff/areas")).path("items").get(0).path("id").asText();
            expect(201, dispatcher.post("/staff/area-rules", Map.of(
                    "street", "演示东路", "community", "草案样例小区", "areaId", east), dispatcher.csrf()));
            var manifest = manifest("PLANNING-03-DEMO", List.of("000726030001", "000726030002", "000726030003")
                    .stream().map(waybill -> Map.of("waybillNo", waybill, "street", "演示东路",
                            "community", "草案样例小区")).toList());
            batchId = expect(201, dispatcher.post(BATCHES, manifest, dispatcher.csrf())).path("id").asText();
            for (String waybill : List.of("000726030001", "000726030002", "000726030003")) {
                expect(201, operator.post(BATCHES + "/" + batchId + "/receipts",
                        Map.of("waybillNo", waybill), operator.csrf(), "support-" + waybill));
            }
            migratedQuarantine = expect(200, operator.post(BATCHES + "/" + batchId + "/quarantines",
                    Map.of("kind", "UNLISTED", "waybillNo", "000726039999", "physicalConfirmed", true),
                    operator.csrf(), "support-legacy-unlisted"));
            shiftId = expect(200, dispatcher.get("/staff/shifts?at=2026-11-25T08:00:00%2B08:00"))
                    .path("items").get(0).path("shiftId").asText();
        }
        chen = worker("courier-chen");
        support = worker("courier-liu");
        foreign = worker("courier-wang");
        jdbc.sql("""
                INSERT INTO delivery_plan (network_id, shift_id, inbound_batch_id, created_by, created_at, evaluated_at)
                SELECT e.unit_id, :shift, :batch, e.id, '2026-11-25 08:10:00', '2026-11-25 08:00:00'
                FROM employee_account e WHERE e.login_name = 'dispatcher-zhou'
                """).param("shift", shiftId).param("batch", batchId).update();
        migratedId = jdbc.sql("SELECT id FROM delivery_plan").query(String.class).single();
        jdbc.sql("""
                INSERT INTO plan_item (plan_id, parcel_id, target_area_id, target_area_name,
                                       proposed_worker_id, proposed_worker_name, parcel_version)
                SELECT :plan, p.id, p.address_area_id, '东片区', :chen, '陈立', p.version FROM parcel p
                WHERE p.waybill_no = '000726030001'
                """).param("plan", migratedId).param("chen", chen).update();
        var flyway = Flyway.configure().dataSource(dataSource).load();
        assertEquals("10", flyway.info().current().getVersion().getVersion());
        flyway.migrate();
    }

    @Test
    void preservesQuarantineAndAdjustsDraftMigratedFromV10() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var batch = expect(200, dispatcher.get(BATCHES + "/" + batchId));
            assertEquals(3, batch.path("receivedCount").asInt());
            assertEquals(3, batch.path("sortCreditCount").asInt());
            assertEquals(1, batch.path("quarantines").size());
            assertEquals(migratedQuarantine, batch.path("quarantines").get(0));
            var old = expect(200, dispatcher.get(PLANS + "/" + migratedId));
            assertEquals("0", old.path("version").asText());
            var changed = expect(200, dispatcher.patch(PLANS + "/" + migratedId,
                    adjustment("0", List.of(line(old, 0).path("parcelId").asText()), support), dispatcher.csrf()));
            assertEquals("1", changed.path("version").asText());
            assertEquals(support, line(changed, 0).path("proposedWorkerId").asText());
            assertEquals(changed, expect(200, dispatcher.get(PLANS + "/" + migratedId)));
        }
    }

    @Test
    void explicitlyAssignsSupportAndKeepsTheOtherParcelAndCustody() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var before = custody();
            var plan = create(dispatcher);
            var path = PLANS + "/" + plan.path("id").asText();
            var available = expect(200, dispatcher.get(path + "/support-workers")).path("items").findValues("id")
                    .stream().map(JsonNode::asText).toList();
            assertEquals(List.of(chen, support), available);
            var id = line(plan, 0).path("parcelId").asText();
            var updated = expect(200, dispatcher.patch(path, adjustment("0", List.of(id), support), dispatcher.csrf()));
            assertEquals("1", updated.path("version").asText());
            assertEquals(east, updated.path("groups").get(0).path("areaId").asText());
            assertEquals(support, line(updated, 0).path("proposedWorkerId").asText());
            assertEquals(line(plan, 1), line(updated, 1));
            assertEquals(updated, expect(200, dispatcher.get(path)));
            var change = updated.path("adjustments").get(0);
            assertEquals(id, change.path("parcelId").asText());
            assertEquals(chen, change.path("oldWorkerId").asText());
            assertEquals(support, change.path("newWorkerId").asText());
            assertEquals(REASON, change.path("reason").asText());
            assertEquals(plan.path("createdBy").asText(), change.path("changedBy").asText());
            var changedAt = OffsetDateTime.parse(change.path("changedAt").asText());
            assertEquals("+08:00", changedAt.getOffset().toString());
            assertEquals(changedAt.toLocalDateTime(), jdbc.sql("SELECT changed_at FROM plan_adjustment WHERE plan_id = :id")
                    .param("id", plan.path("id").asText()).query(LocalDateTime.class).single());
            assertEquals(before, custody());
        }
    }

    @Test
    void logsOnlyActualReassignmentsAndKeepsTheVersionWhenEveryoneAlreadyMatches() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var plan = create(dispatcher);
            var path = PLANS + "/" + plan.path("id").asText();
            var group = plan.path("groups").get(0).path("items").findValues("parcelId").stream()
                    .map(JsonNode::asText).toList();
            expect(200, dispatcher.patch(path, adjustment("0", List.of(group.getFirst()), support), dispatcher.csrf()));

            var mixed = expect(200, dispatcher.patch(path, adjustment("1", group, support), dispatcher.csrf()));
            assertEquals("2", mixed.path("version").asText());
            var changes = mixed.path("adjustments");
            assertEquals(group.size(), changes.size());
            assertEquals(group.subList(1, group.size()), changes.valueStream()
                    .filter(change -> "2".equals(change.path("planVersion").asText()))
                    .map(change -> change.path("parcelId").asText()).toList());
            assertEquals("1", changes.get(changes.size() - 1).path("planVersion").asText());
            assertTrue(mixed.path("groups").get(0).path("items").findValues("proposedWorkerId").stream()
                    .allMatch(worker -> support.equals(worker.asText())));

            assertEquals(mixed, expect(200, dispatcher.patch(path, adjustment("2", group, support), dispatcher.csrf())));
            assertEquals(mixed, expect(200, dispatcher.get(path)));
            assertEquals(group.size(), auditCount(plan));
            assertEquals(2, jdbc.sql("SELECT version FROM delivery_plan WHERE id = :id")
                    .param("id", plan.path("id").asText()).query(Integer.class).single());
        }
    }

    @Test
    void recordsPendingToSupportWhenTheRegularCourierIsOnLeave() throws Exception {
        jdbc.sql("UPDATE shift_member SET duty_status = 'ON_LEAVE' WHERE shift_id = :shift AND worker_id = :worker")
                .param("shift", shiftId).param("worker", chen).update();
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var plan = create(dispatcher);
            assertEquals(3, plan.path("pending").asInt());
            var id = line(plan, 0).path("parcelId").asText();
            var updated = expect(200, dispatcher.patch(PLANS + "/" + plan.path("id").asText(),
                    adjustment("0", List.of(id), support), dispatcher.csrf()));
            assertEquals(1, updated.path("assigned").asInt());
            assertEquals(2, updated.path("pending").asInt());
            assertTrue(updated.path("adjustments").get(0).path("oldWorkerId").isNull());
            assertEquals(support, updated.path("adjustments").get(0).path("newWorkerId").asText());
        } finally {
            jdbc.sql("UPDATE shift_member SET duty_status = 'ON_DUTY' WHERE shift_id = :shift AND worker_id = :worker")
                    .param("shift", shiftId).param("worker", chen).update();
        }
    }

    @Test
    void holdsOneSelectedParcelWithoutChangingItsAreaOrCustodyAndCanRestoreIt() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var plan = create(dispatcher);
            var path = PLANS + "/" + plan.path("id").asText();
            var selected = List.of(line(plan, 0).path("parcelId").asText());
            var before = custody();
            var held = expect(200, dispatcher.patch(path, hold("0", selected), dispatcher.csrf()));
            assertEquals("1", held.path("version").asText());
            assertEquals(OffsetDateTime.parse(AT), OffsetDateTime.parse(held.path("evaluatedAt").asText()));
            assertEquals(plan.path("assigned").asInt() - 1, held.path("assigned").asInt());
            assertEquals(plan.path("pending").asInt() + 1, held.path("pending").asInt());
            assertEquals(east, held.path("groups").get(0).path("areaId").asText());
            assertTrue(line(held, 0).path("proposedWorkerId").isNull());
            assertEquals("DISPATCHER_HOLD", line(held, 0).path("pendingReason").asText());
            assertEquals(line(plan, 1), line(held, 1));
            var change = held.path("adjustments").get(0);
            assertEquals(selected.getFirst(), change.path("parcelId").asText());
            assertEquals(chen, change.path("oldWorkerId").asText());
            assertTrue(change.path("newWorkerId").isNull());
            assertEquals("留给下一趟", change.path("reason").asText());
            assertEquals(plan.path("createdBy").asText(), change.path("changedBy").asText());
            var changedAt = OffsetDateTime.parse(change.path("changedAt").asText());
            assertEquals("+08:00", changedAt.getOffset().toString());
            assertEquals(changedAt.toLocalDateTime(), jdbc.sql("SELECT changed_at FROM plan_adjustment WHERE plan_id = :id")
                    .param("id", plan.path("id").asText()).query(LocalDateTime.class).single());
            assertEquals(held, expect(200, dispatcher.get(path)));
            assertEquals(held, expect(200, dispatcher.patch(path, hold("1", selected), dispatcher.csrf())));
            assertEquals(1, auditCount(plan));

            var restored = expect(200, dispatcher.patch(path, adjustment("1", selected, support), dispatcher.csrf()));
            assertEquals("2", restored.path("version").asText());
            assertEquals(support, line(restored, 0).path("proposedWorkerId").asText());
            assertTrue(line(restored, 0).path("pendingReason").isNull());
            assertEquals(plan.path("assigned"), restored.path("assigned"));
            assertEquals(2, auditCount(plan));
            assertEquals(before, custody());
        }
    }

    @Test
    void rejectsForeignOnLeaveAndStaleParcelsWithoutPartialChanges() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou"); var operator = login(port, "operator-xu")) {
            var plan = create(dispatcher);
            var path = PLANS + "/" + plan.path("id").asText();
            var selected = List.of(line(plan, 0).path("parcelId").asText(), line(plan, 1).path("parcelId").asText());
            expect(403, operator.patch(path, adjustment("0", selected, support), operator.csrf()));
            expect(409, dispatcher.patch(path, adjustment("0", selected, foreign), dispatcher.csrf()));

            jdbc.sql("UPDATE shift_member SET duty_status = 'ON_LEAVE' WHERE shift_id = :shift AND worker_id = :worker")
                    .param("shift", shiftId).param("worker", support).update();
            try {
                expect(409, dispatcher.patch(path, adjustment("0", selected, support), dispatcher.csrf()));
            } finally {
                jdbc.sql("UPDATE shift_member SET duty_status = 'ON_DUTY' WHERE shift_id = :shift AND worker_id = :worker")
                        .param("shift", shiftId).param("worker", support).update();
            }

            jdbc.sql("UPDATE parcel SET version = version + 1 WHERE id = :id").param("id", selected.get(1)).update();
            var before = custody();
            var conflict = expect(409, dispatcher.patch(path, adjustment("0", selected, support), dispatcher.csrf()));
            assertEquals("DRAFT_PARCEL_CHANGED", conflict.path("code").asText());
            var holdConflict = expect(409, dispatcher.patch(path, hold("0", selected), dispatcher.csrf()));
            assertEquals("DRAFT_PARCEL_CHANGED", holdConflict.path("code").asText());
            assertEquals(plan, expect(200, dispatcher.get(path)));
            assertEquals(0, auditCount(plan));
            assertEquals(before, custody());
        }
    }

    @Test
    void rollsBackSelectedItemsWhenAnAdjustmentRecordCannotBeWritten() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var plan = create(dispatcher);
            var id = plan.path("id").asLong();
            var path = PLANS + "/" + id;
            var selected = List.of(line(plan, 0).path("parcelId").asText(), line(plan, 1).path("parcelId").asText());
            long second = Long.parseLong(selected.get(1));
            jdbc.sql("""
                    ALTER TABLE plan_adjustment ADD CONSTRAINT fail_second_adjustment
                        CHECK (plan_id <> %d OR parcel_id <> %d)
                    """.formatted(id, second)).update();
            try {
                expect(500, dispatcher.patch(path, adjustment("0", selected, support), dispatcher.csrf()));
                assertEquals(plan, expect(200, dispatcher.get(path)));
                assertEquals(0, auditCount(plan));
            } finally {
                jdbc.sql("ALTER TABLE plan_adjustment DROP CHECK fail_second_adjustment").update();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentGroupAdjustmentsFromTheSameVersionCommitExactlyOnce(boolean includesHold) throws Exception {
        try (var first = login(port, "dispatcher-zhou"); var second = login(port, "dispatcher-zhou");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var plan = create(first);
            var path = PLANS + "/" + plan.path("id").asText();
            var selected = plan.path("groups").get(0).path("items").findValues("parcelId").stream()
                    .map(JsonNode::asText).toList();
            var body = adjustment("0", selected, support);
            var competing = includesHold ? hold("0", selected) : body;
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return first.patch(path, body, first.csrf()); });
            var b = executor.submit(() -> { start.await(); return second.patch(path, competing, second.csrf()); });
            start.countDown();
            var responses = List.of(a.get(), b.get());
            assertEquals(List.of(200, 409), responses.stream().map(response -> response.statusCode()).sorted().toList());
            var rejected = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
            assertEquals("DRAFT_VERSION_CONFLICT", expect(409, rejected).path("code").asText());
            var stored = expect(200, first.get(path));
            assertEquals("1", stored.path("version").asText());
            boolean holdWon = includesHold && responses.get(1).statusCode() == 200;
            assertTrue(stored.path("groups").get(0).path("items").valueStream().allMatch(
                    item -> holdWon
                            ? item.path("proposedWorkerId").isNull() && "DISPATCHER_HOLD".equals(item.path("pendingReason").asText())
                            : support.equals(item.path("proposedWorkerId").asText()) && item.path("pendingReason").isNull()));
            assertEquals(selected.size(), auditCount(plan));
        }
    }

    private JsonNode create(AuthHttpClient dispatcher) throws Exception {
        return expect(201, dispatcher.post(PLANS, Map.of("batchId", batchId, "shiftId", shiftId, "at", AT),
                dispatcher.csrf()));
    }

    private static JsonNode line(JsonNode plan, int index) {
        return plan.path("groups").get(0).path("items").get(index);
    }

    private static Map<String, ?> adjustment(String version, List<String> parcels, String worker) {
        return Map.of("expectedVersion", version, "parcelIds", parcels, "workerId", worker, "reason", REASON);
    }

    private static Map<String, ?> hold(String version, List<String> parcels) {
        return Map.of("action", "HOLD", "expectedVersion", version, "parcelIds", parcels, "reason", "留给下一趟");
    }

    private int auditCount(JsonNode plan) {
        return jdbc.sql("SELECT COUNT(*) FROM plan_adjustment WHERE plan_id = :id")
                .param("id", plan.path("id").asText()).query(Integer.class).single();
    }

    private String worker(String number) {
        return jdbc.sql("SELECT id FROM worker_profile WHERE worker_no = :number").param("number", number)
                .query(String.class).single();
    }

    private List<Map<String, Object>> custody() {
        return jdbc.sql("SELECT id, stage, holder_unit_id, holder_kind, version FROM parcel ORDER BY id").query().listOfRows();
    }
}
