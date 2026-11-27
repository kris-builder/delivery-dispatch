package org.example.delivery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.example.delivery.AuthHttpClient.expect;
import static org.example.delivery.InboundBatchTests.activate;
import static org.example.delivery.InboundBatchTests.login;
import static org.example.delivery.InboundBatchTests.manifest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"identity-demo", "planning-demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"delivery.demo.temporary-password=Demo-Temporary-001"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DraftPlanHttpTests {
    private static final String BATCHES = "/staff/inbound-batches";
    private static final String PLANS = "/staff/delivery-plans";
    private static final String AT = "2026-11-25T08:00:00+08:00";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    private String batchId;
    private String shiftId;
    private String foreignShiftId;

    @BeforeAll
    void receiveDemoParcels() throws Exception {
        for (String name : List.of("dispatcher-zhou", "dispatcher-other", "operator-xu", "station-leader-a")) {
            activate(port, name);
        }
        try (var dispatcher = login(port, "dispatcher-zhou"); var operator = login(port, "operator-xu");
             var foreign = login(port, "dispatcher-other")) {
            String east = expect(200, dispatcher.get("/staff/areas")).path("items").get(0).path("id").asText();
            expect(201, dispatcher.post("/staff/area-rules",
                    Map.of("street", "演示东路", "community", "草案样例小区", "areaId", east), dispatcher.csrf()));
            batchId = expect(201, dispatcher.post(BATCHES, manifest("PLANNING-03-DEMO", List.of(
                    Map.of("waybillNo", "000726030001", "street", "演示东路", "community", "草案样例小区"),
                    Map.of("waybillNo", "000726030002"),
                    Map.of("waybillNo", "000726030003"))), dispatcher.csrf())).path("id").asText();
            for (String waybill : List.of("000726030001", "000726030002")) {
                expect(201, operator.post(BATCHES + "/" + batchId + "/receipts",
                        Map.of("waybillNo", waybill), operator.csrf(), "planning-demo-" + waybill));
            }
            var shifts = "/staff/shifts?at=2026-11-25T08:00:00%2B08:00";
            shiftId = expect(200, dispatcher.get(shifts)).path("items").get(0).path("shiftId").asText();
            foreignShiftId = expect(200, foreign.get(shifts)).path("items").get(0).path("shiftId").asText();
        }
    }

    @Test
    void savesPlanAndReloadsOriginalGroupsOwnershipAndBeijingTimes() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou"); var foreign = login(port, "dispatcher-other");
             var operator = login(port, "operator-xu")) {
            var before = fulfillment();
            var response = dispatcher.post(PLANS, input(), dispatcher.csrf());
            var plan = expect(201, response);
            String path = PLANS + "/" + plan.path("id").asText();
            assertEquals("/api/v1" + path, response.headers().firstValue("Location").orElseThrow());
            assertEquals(plan, expect(200, dispatcher.get(path)));
            expect(404, foreign.get(path));
            expect(403, operator.get(path));

            assertEquals("0", plan.path("version").asText());
            assertEquals("DRAFT", plan.path("status").asText());
            assertTrue(plan.path("createdAt").asText().endsWith("+08:00"));
            assertEquals(OffsetDateTime.parse(AT), OffsetDateTime.parse(plan.path("evaluatedAt").asText()));
            assertEquals(2, plan.path("total").asInt());
            assertEquals(1, plan.path("assigned").asInt());
            assertEquals(1, plan.path("pending").asInt());

            var groups = plan.path("groups");
            assertEquals("东片区", groups.get(0).path("areaName").asText());
            var first = groups.get(0).path("items").get(0);
            assertEquals("000726030001", first.path("waybillNo").asText());
            assertEquals("陈立", first.path("proposedWorkerName").asText());
            assertEquals("AREA_UNCONFIRMED", groups.get(1).path("items").get(0).path("pendingReason").asText());
            assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM plan_item WHERE plan_id = :id")
                    .param("id", plan.path("id").asText()).query(Integer.class).single());
            assertEquals(first.path("parcelVersion").asText(), jdbc.sql("""
                    SELECT CAST(i.parcel_version AS CHAR) FROM plan_item i JOIN parcel p ON p.id = i.parcel_id
                    WHERE i.plan_id = :id AND p.waybill_no = '000726030001'
                    """).param("id", plan.path("id").asText()).query(String.class).single());
            assertEquals(plan.path("createdBy").asText(), jdbc.sql("""
                    SELECT CAST(e.id AS CHAR) FROM delivery_plan d JOIN employee_account e ON e.id = d.created_by
                    WHERE d.id = :id AND e.login_name = 'dispatcher-zhou'
                    """).param("id", plan.path("id").asText()).query(String.class).single());
            assertEquals(before, fulfillment());
        }
    }

    @Test
    void savesAndReopensValidLocalShiftWithNoMembers() throws Exception {
        jdbc.sql("""
                INSERT INTO duty_shift (unit_id, service_date, code, starts_at, ends_at)
                SELECT unit_id, service_date, 'EMPTY', starts_at, ends_at FROM duty_shift WHERE id = :id
                """).param("id", shiftId).update();
        String emptyShiftId = jdbc.sql("""
                SELECT id FROM duty_shift WHERE code = 'EMPTY'
                """).query(String.class).single();
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var before = fulfillment();
            var input = Map.of("batchId", batchId, "shiftId", emptyShiftId, "at", AT);
            var plan = expect(201, dispatcher.post(PLANS, input, dispatcher.csrf()));
            var id = plan.path("id").asText();
            assertEquals(plan, expect(200, dispatcher.get(PLANS + "/" + id)));
            assertEquals(emptyShiftId, plan.path("shiftId").asText());
            assertEquals("0", plan.path("version").asText());
            assertEquals(2, plan.path("total").asInt());
            assertEquals(0, plan.path("assigned").asInt());
            assertEquals(2, plan.path("pending").asInt());
            var item = plan.path("groups").get(0).path("items").get(0);
            assertEquals("000726030001", item.path("waybillNo").asText());
            assertTrue(item.path("proposedWorkerId").isNull());
            assertEquals("REGULAR_WORKER_UNAVAILABLE", item.path("pendingReason").asText());
            assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM plan_item WHERE plan_id = :id AND proposed_worker_id IS NULL")
                    .param("id", id).query(Integer.class).single());
            assertEquals(0, jdbc.sql("SELECT version FROM delivery_plan WHERE id = :id")
                    .param("id", id).query(Integer.class).single());
            assertEquals(before, fulfillment());

            var choices = expect(200, dispatcher.get(PLANS + "/eligible-shifts?at=2026-11-25T08:00:00%2B08:00"))
                    .path("items").findValues("shiftId").stream().map(value -> value.asText()).toList();
            assertEquals(List.of(shiftId, emptyShiftId), choices);
        }
    }

    @Test
    void rejectsWrongRoleCsrfForeignResourcesAndShiftEndWithoutSaving() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou"); var foreign = login(port, "dispatcher-other");
             var operator = login(port, "operator-xu"); var station = login(port, "station-leader-a")) {
            int plansBefore = planCount();
            var before = fulfillment();
            expect(403, dispatcher.post(PLANS, input(), null));
            expect(403, operator.post(PLANS, input(), operator.csrf()));
            expect(403, station.post(PLANS, input(), station.csrf()));
            expect(404, foreign.post(PLANS, input(), foreign.csrf()));
            var wrongSite = expect(404, dispatcher.post(PLANS, Map.of("batchId", batchId, "shiftId", foreignShiftId, "at", AT),
                    dispatcher.csrf()));
            assertEquals("PLANNING_SHIFT_NOT_FOUND", wrongSite.path("code").asText());
            expect(404, dispatcher.post(PLANS, Map.of("batchId", batchId, "shiftId", shiftId,
                    "at", "2026-11-25T12:00:00+08:00"), dispatcher.csrf()));
            assertEquals(plansBefore, planCount());
            assertEquals(before, fulfillment());
        }
    }

    @Test
    void rollsBackPlanAndPreviouslyInsertedItemWhenAnotherItemFails() throws Exception {
        int plansBefore = planCount();
        int itemsBefore = jdbc.sql("SELECT COUNT(*) FROM plan_item").query(Integer.class).single();
        var before = fulfillment();
        long lastPlan = jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM delivery_plan").query(Long.class).single();
        jdbc.sql("""
                ALTER TABLE plan_item ADD CONSTRAINT fail_second_draft_item
                    CHECK (plan_id <= %d OR reason_code <> 'AREA_UNCONFIRMED')
                """.formatted(lastPlan)).update();
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            expect(500, dispatcher.post(PLANS, input(), dispatcher.csrf()));
            assertEquals(plansBefore, planCount());
            assertEquals(itemsBefore, jdbc.sql("SELECT COUNT(*) FROM plan_item").query(Integer.class).single());
            assertEquals(before, fulfillment());
        } finally {
            jdbc.sql("ALTER TABLE plan_item DROP CHECK fail_second_draft_item").update();
        }
    }

    private Map<String, String> input() {
        return Map.of("batchId", batchId, "shiftId", shiftId, "at", AT);
    }

    private int planCount() {
        return jdbc.sql("SELECT COUNT(*) FROM delivery_plan").query(Integer.class).single();
    }

    private List<Map<String, Object>> fulfillment() {
        return jdbc.sql("""
                SELECT p.waybill_no, p.stage, p.holder_kind, p.holder_unit_id, p.version,
                       (SELECT COUNT(*) FROM inbound_receipt r WHERE r.parcel_id = p.id) AS receipts,
                       (SELECT COUNT(*) FROM parcel_event e WHERE e.parcel_id = p.id) AS events
                FROM parcel p WHERE p.waybill_no LIKE '00072603%' ORDER BY p.waybill_no
                """).query().listOfRows();
    }
}
