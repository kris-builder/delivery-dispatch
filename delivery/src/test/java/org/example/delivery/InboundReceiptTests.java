package org.example.delivery;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.delivery.time.BeijingTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import static org.example.delivery.AuthHttpClient.expect;
import static org.example.delivery.InboundBatchTests.BATCHES;
import static org.example.delivery.InboundBatchTests.activate;
import static org.example.delivery.InboundBatchTests.login;
import static org.example.delivery.InboundBatchTests.manifest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import({TestcontainersConfiguration.class, InboundReceiptTests.TimeoutConfiguration.class})
@ActiveProfiles({"identity-demo", "planning-demo"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "delivery.demo.temporary-password=Demo-Temporary-001")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboundReceiptTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired HoldFirstResponse timeout;

    @BeforeAll
    void activateAccounts() throws Exception {
        for (String account : List.of("dispatcher-zhou", "dispatcher-other", "operator-xu")) {
            activate(port, account);
        }
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var csrf = dispatcher.csrf();
            for (var area : expect(200, dispatcher.get("/staff/areas")).path("items")) {
                String community = "EAST".equals(area.path("code").asText()) ? "东" : "西";
                expect(201, dispatcher.post("/staff/area-rules", Map.of("street", "测试街道",
                        "community", community, "areaId", area.path("id").asText()), csrf));
            }
        }
        addEmployee("operator-local-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        addEmployee("operator-foreign-test", "OTHER-DEMO", "NETWORK_OPERATOR");
        addEmployee("sorter-test", "PICKUP-DEMO-A", "PICKUP_STATION_SORTER");
        addEmployee("operator-rollback-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        addEmployee("manual-rollback-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        addEmployee("credit-scanner-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        addEmployee("credit-assignee-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        addEmployee("credit-boundary-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
    }

    @Test
    void returnsAnEmptyHomeCandidateListBeforeAnyReceipts() throws Exception {
        String batch = importBatch("HOME-EMPTY", List.of(line("0008100001", "东")));
        String path = BATCHES + "/" + batch + "/home-staging";
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou");
             var foreign = login(port, "dispatcher-other"); var sorter = login(port, "sorter-test")) {
            var detail = expect(200, operator.get(path));
            assertEquals(batch, detail.path("batchId").asText());
            assertTrue(detail.path("candidates").isArray());
            assertEquals(0, detail.path("candidates").size());
            assertEquals(detail, expect(200, dispatcher.get(path)));
            assertEquals("INBOUND_BATCH_NOT_FOUND", expect(404, foreign.get(path)).path("code").asText());
            expect(403, sorter.get(path));
        }
    }

    @Test
    void findsOnlyCurrentConfirmedHomeAgreementsForEligibleReceipts() throws Exception {
        String eligible = "0008100011";
        String pending = "0008100012";
        String notReceived = "0008100013";
        String quarantined = "0008100014";
        String departed = "0008100015";
        String unknown = "0008100016";
        String pickup = "0008100017";
        String superseded = "0008100018";
        String inactiveArea = "0008100019";
        String otherBatch = "0008100020";
        String batch = importBatch("HOME-ELIGIBILITY", List.of(
                line(eligible, "东"), line(pending, "未知"), line(notReceived, "东"), line(quarantined, "东"),
                line(departed, "东"), line(unknown, "东"), line(pickup, "东"), line(superseded, "东"),
                line(inactiveArea, "东")));
        String another = importBatch("HOME-DIFFERENT-BATCH", List.of(line(otherBatch, "东")));
        String path = BATCHES + "/" + batch + "/home-staging";
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou");
             var foreign = login(port, "dispatcher-other"); var sorter = login(port, "sorter-test")) {
            var csrf = operator.csrf();
            for (String waybill : List.of(eligible, pending, departed, unknown, pickup, superseded, inactiveArea)) {
                expect(201, operator.post(scan(batch), body(waybill), csrf, "home-scan-" + waybill));
            }
            expect(201, operator.post(scan(another), body(otherBatch), csrf, "home-scan-" + otherBatch));
            expect(200, operator.post(quarantine(batch),
                    Map.of("kind", "DAMAGED", "waybillNo", quarantined, "note", "外箱破损"), csrf, "home-damage"));
            String west = areaId("JIANGAN-DEMO", "WEST");
            String east = areaId("JIANGAN-DEMO", "EAST");
            for (String waybill : List.of(eligible, pending, notReceived, quarantined, departed, inactiveArea, otherBatch)) {
                insertHomeAgreement(waybill, west);
            }
            insertHomeAgreement(superseded, east);
            jdbc.sql("""
                    UPDATE delivery_agreement SET superseded_at = '2026-11-26 08:10:00'
                    WHERE parcel_id = :parcel
                    """).param("parcel", parcelId(superseded)).update();
            jdbc.sql("""
                    INSERT INTO delivery_agreement
                        (parcel_id, method, pickup_point_id, confirmation_source, confirmed_at)
                    VALUES (:parcel, 'PICKUP', 1, 'SYNTHETIC', '2026-11-26 08:00:00')
                    """).param("parcel", parcelId(pickup)).update();
            jdbc.sql("""
                    UPDATE parcel SET stage = 'OUT_FOR_DELIVERY', holder_kind = 'WORKER', holder_unit_id = NULL
                    WHERE waybill_no = :waybill
                    """).param("waybill", departed).update();
            jdbc.sql("""
                    INSERT INTO delivery_area (network_id, code, name, active)
                    SELECT id, 'HOME-INACTIVE', '停用片区', FALSE FROM org_unit WHERE code = 'JIANGAN-DEMO'
                    """).update();
            jdbc.sql("UPDATE parcel SET address_area_id = :area WHERE waybill_no = :waybill")
                    .param("area", areaId("JIANGAN-DEMO", "HOME-INACTIVE")).param("waybill", inactiveArea).update();

            var candidates = expect(200, dispatcher.get(path)).path("candidates");
            assertEquals(List.of(eligible),
                    candidates.findValues("waybillNo").stream().map(value -> value.asText()).toList());
            var candidate = candidates.get(0);
            assertEquals(parcelId(eligible), candidate.path("parcelId").asText());
            assertTrue(candidate.path("parcelId").isTextual());
            assertTrue(candidate.path("parcelVersion").isTextual());
            assertEquals(east, candidate.path("area").path("id").asText());
            assertEquals("HOME", candidate.path("agreement").path("method").asText());
            assertEquals("0", candidate.path("agreement").path("version").asText());
            assertEquals("2026-11-26T08:00:00.123456+08:00", candidate.path("agreement").path("confirmedAt").asText());
            assertEquals(jdbc.sql("SELECT id FROM delivery_agreement WHERE parcel_id = :parcel")
                    .param("parcel", parcelId(eligible)).query(String.class).single(),
                    candidate.path("agreement").path("id").asText());

            expect(200, operator.post(assignment(parcelId(pending)), Map.of("areaId", west),
                    csrf, "home-manual-" + pending));
            var after = expect(200, operator.get(path)).path("candidates");
            assertEquals(List.of(eligible, pending),
                    after.findValues("waybillNo").stream().map(value -> value.asText()).toList());
            assertEquals(west, after.get(1).path("area").path("id").asText());
            assertEquals(jdbc.sql("SELECT version FROM parcel WHERE id = :parcel").param("parcel", parcelId(pending))
                    .query(String.class).single(), after.get(1).path("parcelVersion").asText());
            assertEquals("INBOUND_BATCH_NOT_FOUND", expect(404, foreign.get(path)).path("code").asText());
            expect(403, sorter.get(path));
        }
    }

    private void insertHomeAgreement(String waybill, String targetArea) {
        jdbc.sql("""
                INSERT INTO delivery_agreement
                    (parcel_id, method, target_area_id, home_address, confirmation_source, confirmed_at)
                VALUES (:parcel, 'HOME', :area, '合成上门地址 1 号', 'SYNTHETIC', '2026-11-26 08:00:00.123456')
                """).param("parcel", parcelId(waybill)).param("area", targetArea).update();
    }

    @Test
    void registersDamageAndMissingLabelWithoutLeakingIntoPlanningOrCredits() throws Exception {
        String batch = importBatch("PLANNING-03-DEMO",
                List.of(line("000726030001", "东"), line("000726030002", "东")));
        String path = quarantine(batch);
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou");
             var other = login(port, "operator-local-test"); var threads = Executors.newFixedThreadPool(2)) {
            var csrf = operator.csrf();
            expect(201, operator.post(scan(batch), body("000726030001"), csrf, "quarantine-normal"));
            var damage = Map.of("kind", "DAMAGED", "waybillNo", "000726030002", "note", "外箱破损",
                    "evidenceRef", "到货照片 A");
            var otherCsrf = other.csrf();
            var damageStart = new CountDownLatch(1);
            var firstDamage = threads.submit(() -> {
                damageStart.await();
                return operator.post(path, damage, csrf, "quarantine-damage");
            });
            var secondDamage = threads.submit(() -> {
                damageStart.await();
                return other.post(path, damage, otherCsrf, "quarantine-damage-other");
            });
            damageStart.countDown();
            var registered = expect(200, firstDamage.get(15, TimeUnit.SECONDS));
            assertEquals(registered, expect(200, secondDamage.get(15, TimeUnit.SECONDS)));
            assertEquals("UPSTREAM_REVIEW", registered.path("status").asText());
            assertEquals("到货照片 A", registered.path("evidenceRef").asText());
            assertEquals(registered, expect(200, operator.post(path, damage, csrf, "quarantine-damage-repeat")));
            assertEquals("QUARANTINED", expect(200, operator.post(scan(batch), body("000726030002"), csrf,
                    "quarantine-damage-rescan")).path("status").asText());
            var missingInput = Map.of("kind", "MISSING_LABEL", "note", "面单无法辨认");
            var missingStart = new CountDownLatch(1);
            var firstMissing = threads.submit(() -> {
                missingStart.await();
                return operator.post(path, missingInput, csrf, "quarantine-missing");
            });
            var retryMissing = threads.submit(() -> {
                missingStart.await();
                return operator.post(path, missingInput, csrf, "quarantine-missing");
            });
            missingStart.countDown();
            var missing = expect(200, firstMissing.get(15, TimeUnit.SECONDS));
            assertTrue(missing.path("temporaryNo").asText().startsWith("IN-"));
            assertEquals(missing, expect(200, retryMissing.get(15, TimeUnit.SECONDS)));
            var detail = expect(200, operator.get(BATCHES + "/" + batch));
            assertEquals(2, detail.path("receivedCount").asInt());
            assertEquals(1, detail.path("assignedCount").asInt());
            assertEquals(0, detail.path("pendingAreaCount").asInt());
            assertEquals(1, detail.path("sortCreditCount").asInt());
            assertEquals(2, detail.path("quarantines").size());
            assertEquals("QUARANTINED", expect(200, operator.get(lookup("000726030002"))).path("status").asText());
            expect(409, operator.post(assignment(parcelId("000726030002")), Map.of("areaId",
                    areaId("JIANGAN-DEMO", "WEST")), csrf, "quarantine-assign"));
            assertTrue(expect(200, operator.get("/staff/parcels/pending-area")).findValues("waybillNo").stream()
                    .noneMatch(node -> "000726030002".equals(node.asText())));
            String at = "2026-11-25T08:00:00+08:00";
            String shift = expect(200, dispatcher.get("/staff/shifts?at=2026-11-25T08:00:00%2B08:00"))
                    .path("items").get(0).path("shiftId").asText();
            var plan = expect(201, dispatcher.post("/staff/delivery-plans",
                    Map.of("batchId", batch, "shiftId", shift, "at", at), dispatcher.csrf()));
            assertEquals(1, plan.path("total").asInt());
            assertEquals(List.of("000726030001"), plan.findValues("waybillNo").stream().map(v -> v.asText()).toList());
            var time = OffsetDateTime.parse(registered.path("reportedAt").asText());
            assertEquals(ZoneOffset.ofHours(8), time.getOffset());
            assertEquals(time.toLocalDateTime(), jdbc.sql("SELECT reported_at FROM inbound_quarantine WHERE id = :id")
                    .param("id", registered.path("id").asText())
                    .query((row, i) -> row.getObject(1, LocalDateTime.class)).single());
        }
    }

    @Test
    void requiresPhysicalConfirmationAndDeduplicatesUnlistedWaybillAcrossOperators() throws Exception {
        String batch = importBatch("QUARANTINE-UNLISTED", List.of(line("00094001", "东")));
        importBatch("QUARANTINE-OTHER", List.of(line("00094002", "东")));
        String path = quarantine(batch);
        try (var first = login(port, "operator-xu"); var second = login(port, "operator-local-test");
             var foreign = login(port, "operator-foreign-test"); var threads = Executors.newFixedThreadPool(2)) {
            var a = first.csrf();
            var b = second.csrf();
            var input = Map.of("kind", "UNLISTED", "waybillNo", "00094003", "physicalConfirmed", true);
            expect(400, first.post(path, Map.of("kind", "DAMAGED", "waybillNo", "00094001"), a, "quarantine-no-damage-note"));
            expect(400, first.post(path, Map.of("kind", "MISSING_LABEL"), a, "quarantine-no-missing-note"));
            expect(201, first.post(scan(batch), body("00094001"), a, "quarantine-count-baseline"));
            var before = expect(200, first.get(BATCHES + "/" + batch));
            assertEquals("INBOUND_BATCH_MISMATCH", expect(409, first.post(scan(batch), body("00094002"), a,
                    "quarantine-wrong-scan")).path("code").asText());
            expect(409, first.post(path, Map.of("kind", "UNLISTED", "waybillNo", "00094002", "note", "别批",
                    "physicalConfirmed", true), a, "quarantine-wrong-registration"));
            expect(409, first.post(scan(batch), body("00094003"), a, "quarantine-unconfirmed-scan"));
            expect(400, first.post(path, Map.of("kind", "UNLISTED", "waybillNo", "00094003", "note", "待确认"),
                    a, "quarantine-unconfirmed"));
            expect(404, foreign.post(path, input, foreign.csrf(), "quarantine-foreign"));
            var gate = new CountDownLatch(1);
            var left = threads.submit(() -> { gate.await(); return first.post(path, input, a, "quarantine-unlisted-a"); });
            var right = threads.submit(() -> { gate.await(); return second.post(path, input, b, "quarantine-unlisted-b"); });
            gate.countDown();
            var original = expect(200, left.get(15, TimeUnit.SECONDS));
            assertEquals(original, expect(200, right.get(15, TimeUnit.SECONDS)));
            assertEquals(original, expect(200, first.post(path, input, a, "quarantine-unlisted-a")));
            assertEquals("00094003", original.path("waybillNo").asText());
            assertEquals("", original.path("note").asText());
            assertEquals(batch, original.path("batchId").asText());
            var firstId = expect(200, first.get("/auth/session")).path("employeeId").asText();
            var secondId = expect(200, second.get("/auth/session")).path("employeeId").asText();
            assertTrue(List.of(firstId, secondId).contains(original.path("reportedBy").asText()));
            var reportedAt = OffsetDateTime.parse(original.path("reportedAt").asText());
            assertEquals(ZoneOffset.ofHours(8), reportedAt.getOffset());
            assertEquals(reportedAt.toLocalDateTime(), jdbc.sql("SELECT reported_at FROM inbound_quarantine WHERE id = :id")
                    .param("id", original.path("id").asText())
                    .query((row, index) -> row.getObject(1, LocalDateTime.class)).single());
            assertEquals("WAYBILL_QUARANTINED", expect(409, first.post(scan(batch), body("00094003"), a,
                    "quarantine-rescan-unlisted")).path("code").asText());
            var detail = expect(200, first.get(BATCHES + "/" + batch));
            assertEquals(1, before.path("receivedCount").asInt());
            assertEquals(1, before.path("sortCreditCount").asInt());
            assertEquals(before.path("expectedCount"), detail.path("expectedCount"));
            assertEquals(before.path("receivedCount"), detail.path("receivedCount"));
            assertEquals(before.path("sortCreditCount"), detail.path("sortCreditCount"));
            assertEquals(1, detail.path("quarantines").size());
        }
    }

    @Test
    void rollsBackDamagedReceiptAndEventWhenQuarantineWriteFails() throws Exception {
        String batch = importBatch("QUARANTINE-ROLLBACK", List.of(line("00095001", "东")));
        try (var operator = login(port, "operator-xu")) {
            var csrf = operator.csrf();
            var damage = Map.of("kind", "DAMAGED", "waybillNo", "00095001", "note", "故障注入");
            jdbc.sql("""
                    ALTER TABLE inbound_quarantine ADD CONSTRAINT fail_quarantine
                    CHECK (note <> '故障注入')
                    """).update();
            try {
                expect(500, operator.post(quarantine(batch), damage, csrf, "quarantine-rollback"));
                assertEquals(0, countForBatch("inbound_receipt", batch));
                assertEquals(0, countForBatch("parcel_event", batch));
                assertEquals("EXPECTED", expect(200, operator.get(lookup("00095001"))).path("status").asText());
                assertTrue(expect(200, operator.get(BATCHES + "/" + batch)).path("quarantines").isEmpty());
            } finally {
                jdbc.sql("ALTER TABLE inbound_quarantine DROP CHECK fail_quarantine").update();
            }
            expect(200, operator.post(quarantine(batch), damage, csrf, "quarantine-rollback"));
            assertEquals(1, countForBatch("inbound_receipt", batch));
            assertEquals(1, countForBatch("parcel_event", batch));
            assertEquals(1, expect(200, operator.get(BATCHES + "/" + batch)).path("quarantines").size());
        }
    }

    @Test
    void verifiesUnlistedReceiptAndReturnsWrongShipmentWithoutDoubleCounting() throws Exception {
        String originBatch = importBatch("REVIEW-FOUND", List.of(line("00098001", "东")));
        String verifiedBatch = importBatch("REVIEW-CORRECTED", List.of(line("00098002", "东")));
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou");
             var foreign = login(port, "dispatcher-other"); var threads = Executors.newFixedThreadPool(2)) {
            var q = reportUnlisted(operator, originBatch, "00098003");
            String action = review(originBatch, q.path("id").asText());
            var confirmed = Map.of("decision", "RELEASE", "targetBatchId", verifiedBatch, "identityConfirmed", true,
                    "evidence", "上游确认归属和实物", "verifiedAt", "2026-11-27T08:00:00+08:00",
                    "street", "测试街道", "community", "东");
            expect(403, operator.post(action, confirmed, operator.csrf(), "operator-review"));
            expect(404, foreign.post(action, confirmed, foreign.csrf(), "foreign-review"));
            var csrf = dispatcher.csrf();
            var go = new CountDownLatch(1);
            var first = threads.submit(() -> {
                go.await();
                return dispatcher.post(action, confirmed, csrf, "verify-unlisted-first");
            });
            var second = threads.submit(() -> {
                go.await();
                return dispatcher.post(action, confirmed, csrf, "verify-unlisted-other-key");
            });
            go.countDown();
            var released = expect(200, first.get(15, TimeUnit.SECONDS));
            assertEquals(released, expect(200, second.get(15, TimeUnit.SECONDS)));
            assertEquals(released, expect(200, dispatcher.post(action, confirmed, csrf, "verify-unlisted-first")));
            assertEquals("RELEASED", released.path("status").asText());
            assertEquals("00098003", released.path("waybillNo").asText());
            assertEquals("上游确认归属和实物", released.path("evidence").asText());
            assertEquals(verifiedBatch, released.path("targetBatchId").asText());
            assertEquals(OffsetDateTime.parse("2026-11-27T08:00:00+08:00"),
                    OffsetDateTime.parse(released.path("verifiedAt").asText()));
            assertEquals(LocalDateTime.parse("2026-11-27T08:00:00"), jdbc.sql("""
                    SELECT verified_at FROM inbound_quarantine_review WHERE quarantine_id = :id
                    """).param("id", q.path("id").asText()).query((row, i) -> row.getObject(1, LocalDateTime.class)).single());
            var original = expect(200, dispatcher.get(BATCHES + "/" + originBatch));
            assertEquals(q.path("reportedBy"), original.path("quarantines").get(0).path("reportedBy"));
            assertEquals(released, original.path("quarantineReviews").get(0));
            assertEquals(0, original.path("receivedCount").asInt());
            assertEquals("ALREADY_RECEIVED", expect(200, operator.post(scan(verifiedBatch), body("00098003"),
                    operator.csrf(), "review-unlisted-rescan")).path("status").asText());
            var target = expect(200, operator.get(BATCHES + "/" + verifiedBatch));
            assertEquals(2, target.path("expectedCount").asInt());
            assertEquals(1, target.path("receivedCount").asInt());
            assertEquals(1, target.path("sortCreditCount").asInt());
            assertEquals(q.path("reportedBy").asText(), jdbc.sql("""
                    SELECT c.employee_id FROM sort_credit c WHERE c.receipt_id = :receipt
                    """).param("receipt", released.path("receiptId").asText()).query(String.class).single());
            var wrong = reportUnlisted(operator, originBatch, "00098004");
            var returned = expect(200, dispatcher.post(review(originBatch, wrong.path("id").asText()),
                    Map.of("decision", "RETURN", "evidence", "核实错发并交回承运方"), csrf, "review-return"));
            assertEquals("RETURNED", returned.path("status").asText());
            assertEquals(expect(200, dispatcher.get("/auth/session")).path("employeeId"), returned.path("reviewedBy"));
            assertEquals("RETURNED_UPSTREAM", expect(200, operator.get(lookup("00098004"))).path("status").asText());
            var afterReturn = expect(200, dispatcher.get(BATCHES + "/" + originBatch));
            assertEquals(0, afterReturn.path("receivedCount").asInt());
            assertEquals(0, afterReturn.path("sortCreditCount").asInt());
        }
    }

    @Test
    void linksMissingLabelOnlyAfterIdentityCheckAndKeepsOtherHoldsIntact() throws Exception {
        String batch = importBatch("REVIEW-LABEL", List.of(line("00098101", "东"), line("00098102", "东")));
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou")) {
            var csrf = dispatcher.csrf();
            var temp = expect(200, operator.post(quarantine(batch), Map.of("kind", "MISSING_LABEL", "note", "面单不清"),
                    operator.csrf(), "review-missing-label"));
            expect(200, operator.post(quarantine(batch), Map.of("kind", "DAMAGED", "note", "箱体破损", "waybillNo", "00098102"),
                    operator.csrf(), "review-other-hold"));
            String path = review(batch, temp.path("id").asText());
            expect(400, dispatcher.post(path, Map.of("decision", "RELEASE", "evidence", "未核实实物",
                    "targetBatchId", batch, "waybillNo", "00098101"), csrf, "review-no-identity"));
            assertEquals("INBOUND_REVIEW_BLOCKED", expect(409, dispatcher.post(path, Map.of("decision", "RELEASE",
                    "identityConfirmed", true, "evidence", "另有破损拦截", "targetBatchId", batch, "waybillNo", "00098102"),
                    csrf, "review-damage-blocked")).path("code").asText());
            var payload = Map.of("decision", "RELEASE", "identityConfirmed", true, "evidence", "核验外箱与上游照片相符",
                    "targetBatchId", batch, "waybillNo", "00098101");
            var result = expect(200, dispatcher.post(path, payload, csrf, "review-associated"));
            assertEquals(result, expect(200, dispatcher.post(path, payload, csrf, "review-associated-again")));
            assertEquals("00098101", result.path("waybillNo").asText());
            assertEquals(temp.path("temporaryNo"), result.path("temporaryNo"));
            assertEquals(2, expect(200, operator.get(BATCHES + "/" + batch)).path("receivedCount").asInt());
            var another = expect(200, operator.post(quarantine(batch), Map.of("kind", "MISSING_LABEL", "note", "另一实物"),
                    operator.csrf(), "review-another-physical-piece"));
            expect(409, dispatcher.post(review(batch, another.path("id").asText()), payload, csrf, "review-identity-duplicate"));
            assertEquals(2, countForBatch("inbound_receipt", batch));
            assertEquals(1, countForBatch("sort_credit", batch));
        }
    }

    @Test
    void rollsBackExpectedCorrectionAndReceiptWhenReviewInsertFails() throws Exception {
        String batch = importBatch("REVIEW-ROLLBACK", List.of(line("00098201", "东")));
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou")) {
            var q = reportUnlisted(operator, batch, "00098202");
            String path = review(batch, q.path("id").asText());
            var input = Map.of("decision", "RELEASE", "identityConfirmed", true, "evidence", "故障注入",
                    "targetBatchId", batch, "street", "测试街道", "community", "东");
            var csrf = dispatcher.csrf();
            jdbc.sql("ALTER TABLE inbound_quarantine_review ADD CONSTRAINT fail_review CHECK (evidence <> '故障注入')").update();
            try {
                expect(500, dispatcher.post(path, input, csrf, "review-fault"));
                var after = expect(200, operator.get(BATCHES + "/" + batch));
                assertEquals(1, after.path("expectedCount").asInt());
                assertEquals(0, after.path("receivedCount").asInt());
                assertEquals(0, after.path("sortCreditCount").asInt());
                assertEquals("UPSTREAM_REVIEW", after.path("quarantines").get(0).path("status").asText());
                assertTrue(after.path("quarantineReviews").isEmpty());
                assertEquals(0, countForBatch("parcel_event", batch));
            } finally {
                jdbc.sql("ALTER TABLE inbound_quarantine_review DROP CHECK fail_review").update();
            }
            expect(200, dispatcher.post(path, input, csrf, "review-fault"));
            assertEquals(1, countForBatch("inbound_receipt", batch));
        }
    }

    private static tools.jackson.databind.JsonNode reportUnlisted(AuthHttpClient operator, String batch, String no)
            throws Exception {
        return expect(200, operator.post(quarantine(batch),
                Map.of("kind", "UNLISTED", "waybillNo", no, "physicalConfirmed", true), operator.csrf(), "report-" + no));
    }

    private static String review(String batch, String id) {
        return quarantine(batch) + "/" + id + "/reviews";
    }

    private static String quarantine(String batch) {
        return BATCHES + "/" + batch + "/quarantines";
    }

    @Test
    void reports87ForScannerAndCreditsManualAssignmentOnlyToTheOtherEmployee() throws Exception {
        var baseline = IntStream.range(0, 86).mapToObj(index -> "00086" + (100 + index)).toList();
        var lines = new java.util.ArrayList<>(baseline.stream().map(no -> line(no, "东")).toList());
        lines.add(line("00086001", "东"));
        lines.add(line("00086002", "未知"));
        String batch = importBatch("CREDIT-ATTRIBUTION", lines);
        String path = "/staff/me/sort-credits";
        try (var scanner = login(port, "credit-scanner-test"); var assignee = login(port, "credit-assignee-test")) {
            var csrf = scanner.csrf();
            for (String no : baseline) {
                expect(201, scanner.post(scan(batch), body(no), csrf, "baseline-" + no));
            }
            assertEquals(86, expect(200, scanner.get(path)).path("effectiveCount").asInt());
            assertEquals(0, expect(200, assignee.get(path)).path("effectiveCount").asInt());
            expect(201, scanner.post(scan(batch), body("00086001"), csrf, "credit-hit"));
            expect(200, scanner.post(scan(batch), body("00086001"), csrf, "credit-repeat"));
            expect(201, scanner.post(scan(batch), body("00086002"), csrf, "credit-pending"));
            var afterScan = expect(200, scanner.get(path));
            assertEquals(87, afterScan.path("effectiveCount").asInt());
            assertEquals(87, afterScan.path("items").size());
            assertEquals("00086001", afterScan.path("items").get(0).path("waybillNo").asText());
            assertEquals("INBOUND_RECEIVED", afterScan.path("items").get(0).path("source").asText());
            assertTrue(afterScan.findValues("waybillNo").stream().noneMatch(v -> "00086002".equals(v.asText())));
            assertEquals(0, expect(200, assignee.get(path)).path("effectiveCount").asInt());
            expect(200, assignee.post(assignment(parcelId("00086002")), Map.of("areaId", areaId("JIANGAN-DEMO", "WEST")),
                    assignee.csrf(), "credit-fill"));
            assertEquals(afterScan, expect(200, scanner.get(path)));
            String scannerId = expect(200, scanner.get("/auth/session")).path("employeeId").asText();
            var afterFill = expect(200, assignee.get(path + "?employeeId=" + scannerId));
            assertEquals(1, afterFill.path("effectiveCount").asInt());
            assertEquals(1, afterFill.path("items").size());
            assertEquals("00086002", afterFill.path("items").get(0).path("waybillNo").asText());
            assertEquals("MANUAL_AREA_ASSIGNED", afterFill.path("items").get(0).path("source").asText());
            assertEquals("+08:00", OffsetDateTime.parse(afterFill.path("items").get(0).path("creditedAt").asText())
                    .getOffset().toString());
        }
    }

    @Test
    void countsOnlyEffectiveCreditsInsideTheBeijingDayAndRequiresAnOperatorSession() throws Exception {
        var day = BeijingTime.now().toLocalDate();
        var numbers = List.of("00087001", "00087002", "00087003", "00087004", "00087005");
        String batch = importBatch("CREDIT-MIDNIGHT", numbers.stream().map(no -> line(no, "东")).toList());
        String path = "/staff/me/sort-credits";
        try (var employee = login(port, "credit-boundary-test"); var foreign = login(port, "operator-foreign-test");
             var dispatcher = login(port, "dispatcher-zhou"); var anonymous = new AuthHttpClient(port)) {
            var csrf = employee.csrf();
            var moments = List.of(day.atStartOfDay().minusNanos(1000), day.atStartOfDay(),
                    day.plusDays(1).atStartOfDay().minusNanos(1000), day.plusDays(1).atStartOfDay(), day.atTime(8, 0));
            for (int index = 0; index < numbers.size(); index++) {
                String no = numbers.get(index);
                var receipt = expect(201, employee.post(scan(batch), body(no), csrf, "midnight-" + no));
                jdbc.sql("UPDATE sort_credit SET credited_at = :time WHERE receipt_id = :id")
                        .param("time", moments.get(index)).param("id", receipt.path("receiptId").asText()).update();
                if (index == 4) {
                    jdbc.sql("UPDATE sort_credit SET void_event_id = source_event_id WHERE receipt_id = :id")
                            .param("id", receipt.path("receiptId").asText()).update();
                }
            }
            var current = expect(200, employee.get(path));
            assertEquals(day.toString(), current.path("businessDate").asText());
            assertEquals(2, current.path("effectiveCount").asInt());
            assertEquals(List.of("00087003", "00087002"), current.findValues("waybillNo").stream()
                    .map(node -> node.asText()).toList());
            assertEquals(day + "T00:00+08:00",
                    OffsetDateTime.parse(current.path("items").get(1).path("creditedAt").asText()).toString());
            assertEquals(1, expect(200, employee.get(path + "?date=" + day.minusDays(1))).path("effectiveCount").asInt());
            assertEquals(0, expect(200, foreign.get(path)).path("effectiveCount").asInt());
            expect(403, dispatcher.get(path));
            expect(401, anonymous.get(path));
        }
    }

    @Test
    void assignsWestOnceAndUpdatesBatchWithoutChangingTheOriginalReceipt() throws Exception {
        String batch = importBatch("MANUAL-WEST", List.of(line("00070001", "未知")));
        try (var scanner = login(port, "operator-xu"); var sorter = login(port, "operator-local-test");
             var threads = Executors.newFixedThreadPool(2)) {
            var initial = expect(201, scanner.post(scan(batch), body("00070001"), scanner.csrf(), "manual-scan"));
            var pending = expect(200, sorter.get("/staff/parcels/pending-area"));
            String parcelId = parcelId("00070001");
            assertTrue(pending.findValues("parcelId").stream().anyMatch(v -> parcelId.equals(v.asText())));
            var before = expect(200, sorter.get(BATCHES + "/" + batch));
            assertEquals(1, before.path("receivedCount").asInt());
            assertEquals(0, before.path("assignedCount").asInt());
            assertEquals(1, before.path("pendingAreaCount").asInt());
            String west = areaId("JIANGAN-DEMO", "WEST");
            String path = assignment(parcelId);
            var csrfA = sorter.csrf();
            var csrfB = scanner.csrf();
            var start = new CountDownLatch(1);
            var left = threads.submit(() -> {
                start.await();
                return sorter.post(path, Map.of("areaId", west), csrfA, "manual-a");
            });
            var right = threads.submit(() -> {
                start.await();
                return scanner.post(path, Map.of("areaId", west), csrfB, "manual-b");
            });
            start.countDown();
            var first = expect(200, left.get(15, TimeUnit.SECONDS));
            var second = expect(200, right.get(15, TimeUnit.SECONDS));
            assertEquals(first, second);
            assertEquals(first, expect(200, sorter.post(path, Map.of("areaId", west), csrfA, "manual-a")));
            assertEquals("IDEMPOTENCY_KEY_REUSED", expect(409, sorter.post(path, Map.of("areaId",
                    areaId("JIANGAN-DEMO", "EAST")), csrfA, "manual-a")).path("code").asText());
            var detail = expect(200, sorter.get(BATCHES + "/" + batch));
            assertEquals(1, detail.path("receivedCount").asInt());
            assertEquals(1, detail.path("assignedCount").asInt());
            assertEquals(0, detail.path("pendingAreaCount").asInt());
            assertEquals(1, detail.path("sortCreditCount").asInt());
            var lookup = expect(200, sorter.get(lookup("00070001")));
            assertEquals("西片区", lookup.path("area").path("name").asText());
            assertEquals(initial.path("receivedAt"), lookup.path("firstReceivedAt"));
            assertEquals(batch, lookup.path("receivedBatch").path("id").asText());
            assertEquals(1, countForBatch("inbound_receipt", batch));
            assertEquals(1, manualEvents(parcelId));
            assertEquals(1, countForBatch("sort_credit", batch));
            assertEquals(first.path("assignedBy").asText(), jdbc.sql("""
                    SELECT c.employee_id FROM sort_credit c JOIN inbound_receipt r ON r.id = c.receipt_id
                    WHERE r.parcel_id = :parcel
                    """).param("parcel", parcelId).query(String.class).single());
            assertTrue(expect(200, sorter.get("/staff/parcels/pending-area")).findValues("waybillNo").stream()
                    .noneMatch(v -> "00070001".equals(v.asText())));
        }
    }

    @Test
    void rejectsInvalidAreaAndForeignParcelAndRollsBackWhenCreditFails() throws Exception {
        String batch = importBatch("MANUAL-ROLLBACK", List.of(line("00070002", "未知")));
        try (var operator = login(port, "manual-rollback-test"); var foreign = login(port, "operator-foreign-test")) {
            var csrf = operator.csrf();
            expect(201, operator.post(scan(batch), body("00070002"), csrf, "manual-rollback-scan"));
            String parcel = parcelId("00070002");
            String west = areaId("JIANGAN-DEMO", "WEST");
            String path = assignment(parcel);
            expect(404, foreign.post(path, Map.of("areaId", west), foreign.csrf(), "foreign-manual"));
            assertTrue(expect(200, foreign.get("/staff/parcels/pending-area")).isEmpty());
            expect(400, operator.post(path, Map.of("areaId", areaId("OTHER-DEMO", "EAST")), csrf, "wrong-area"));
            jdbc.sql("UPDATE delivery_area SET active = FALSE WHERE id = :id").param("id", west).update();
            try {
                expect(400, operator.post(path, Map.of("areaId", west), csrf, "inactive-area"));
            } finally {
                jdbc.sql("UPDATE delivery_area SET active = TRUE WHERE id = :id").param("id", west).update();
            }
            long actor = Long.parseLong(expect(200, operator.get("/auth/session")).path("employeeId").asText());
            jdbc.sql("ALTER TABLE sort_credit ADD CONSTRAINT test_fail_manual CHECK (employee_id <> " + actor + ")").update();
            try {
                expect(500, operator.post(path, Map.of("areaId", west), csrf, "manual-credit-failure"));
                assertEquals("PENDING_AREA", expect(200, operator.get(lookup("00070002"))).path("status").asText());
                assertEquals(0, manualEvents(parcel));
                assertEquals(0, countForBatch("sort_credit", batch));
            } finally {
                jdbc.sql("ALTER TABLE sort_credit DROP CHECK test_fail_manual").update();
            }
            expect(200, operator.post(path, Map.of("areaId", west), csrf, "manual-credit-failure"));
        }
    }

    private String parcelId(String waybillNo) {
        return jdbc.sql("SELECT id FROM parcel WHERE waybill_no = :waybill")
                .param("waybill", waybillNo).query(String.class).single();
    }

    private String areaId(String networkCode, String areaCode) {
        return jdbc.sql("""
                SELECT a.id FROM delivery_area a JOIN org_unit u ON u.id = a.network_id
                WHERE u.code = :network AND a.code = :code
                """).param("network", networkCode).param("code", areaCode).query(String.class).single();
    }

    private int manualEvents(String parcel) {
        return jdbc.sql("SELECT COUNT(*) FROM parcel_event WHERE parcel_id = :parcel AND event_type = 'MANUAL_AREA_ASSIGNED'")
                .param("parcel", parcel).query(Integer.class).single();
    }

    private static String assignment(String parcel) {
        return "/staff/parcels/" + parcel + "/area-assignments";
    }

    @Test
    void looksUpAssignedPendingExpectedAndForeignWaybillsThroughEmployeeHttp() throws Exception {
        String batch = importBatch("LOOKUP-STATES", List.of(line("00060001", "东"), line("00060002", "未知"),
                line("00060003", "西")));
        try (var operator = login(port, "operator-xu"); var dispatcher = login(port, "dispatcher-zhou");
             var foreign = login(port, "operator-foreign-test"); var sorter = login(port, "sorter-test")) {
            var csrf = operator.csrf();
            var receipt = expect(201, operator.post(scan(batch), body("00060001"), csrf, "lookup-a"));
            expect(201, operator.post(scan(batch), body("00060002"), csrf, "lookup-b"));
            var assigned = expect(200, dispatcher.get(lookup("00060001")));
            assertEquals("00060001", assigned.path("waybillNo").asText());
            assertEquals(receipt.path("receivedAt"), assigned.path("firstReceivedAt"));
            var receivedAt = OffsetDateTime.parse(receipt.path("receivedAt").asText());
            assertEquals(ZoneOffset.ofHours(8), receivedAt.getOffset());
            assertEquals(receivedAt.toLocalDateTime(), jdbc.sql("""
                    SELECT received_at FROM inbound_receipt WHERE id = :id
                    """).param("id", receipt.path("receiptId").asText()).query(LocalDateTime.class).single());
            assertEquals(batch, assigned.path("receivedBatch").path("id").asText());
            assertEquals("LOOKUP-STATES", assigned.path("receivedBatch").path("sourceBatchNo").asText());
            assertEquals("东片区", assigned.path("area").path("name").asText());
            assertEquals("INBOUND_RECEIVED", assigned.path("events").get(0).path("type").asText());
            assertEquals(receipt.path("receivedAt"), assigned.path("events").get(0).path("occurredAt"));
            var pending = expect(200, operator.get(lookup("00060002")));
            assertEquals("PENDING_AREA", pending.path("status").asText());
            assertEquals("NO_EFFECTIVE_RULE", pending.path("pendingAreaReason").asText());
            var expected = expect(200, operator.get(lookup("00060003")));
            assertEquals("EXPECTED", expected.path("status").asText());
            assertTrue(expected.path("firstReceivedAt").isNull());
            assertTrue(expected.path("receivedBatch").isNull());
            assertEquals(batch, expected.path("expectedBatches").get(0).path("id").asText());
            assertEquals(0, expected.path("events").size());
            assertEquals(expect(404, foreign.get(lookup("00069999"))).path("code"),
                    expect(404, foreign.get(lookup("00060001"))).path("code"));
            expect(404, operator.get(lookup("60001")));
            expect(403, sorter.get(lookup("00060001")));
            assertEquals(2, countForBatch("inbound_receipt", batch));
        }
    }

    @Test
    void lookupPreservesFirstReceiptAcrossDuplicateScansAndReadsTheCurrentArea() throws Exception {
        String first = importBatch("LOOKUP-FIRST", List.of(line("00061001", "东")));
        String other = importBatch("LOOKUP-OTHER", List.of(line("00061001", "西")));
        try (var operator = login(port, "operator-xu")) {
            var csrf = operator.csrf();
            var original = expect(201, operator.post(scan(first), body("00061001"), csrf, "lookup-original"));
            expect(200, operator.post(scan(other), body("00061001"), csrf, "lookup-duplicate"));
            var before = expect(200, operator.get(lookup("00061001")));
            assertEquals(original.path("receivedAt"), before.path("firstReceivedAt"));
            assertEquals(first, before.path("receivedBatch").path("id").asText());
            assertEquals(1, before.path("events").size());
            // A persisted area change models the future correction entry point.
            jdbc.sql("""
                    UPDATE parcel p JOIN delivery_area a ON a.network_id = p.network_id AND a.code = 'WEST'
                    SET p.address_area_id = a.id, p.address_area_name = a.name
                    WHERE p.waybill_no = '00061001'
                    """).update();
            var after = expect(200, operator.get(lookup("00061001")));
            assertEquals("西片区", after.path("area").path("name").asText());
            assertEquals(before.path("firstReceivedAt"), after.path("firstReceivedAt"));
            assertEquals(before.path("receivedBatch"), after.path("receivedBatch"));
            assertEquals(1, countForBatch("inbound_receipt", first));
            assertEquals(0, countForBatch("inbound_receipt", other));
        }
    }

    private static String lookup(String waybill) {
        return "/staff/parcels?waybillNo=" + waybill;
    }

    @Test
    void fourSubmissionsPersistThreeReceiptsAndTwoCreditsWithOriginalWaybillsAndActor() throws Exception {
        String id = importBatch("SCAN-FOUR", List.of(line("00010001", "东"), line("00010002", "未知"),
                line("00010003", "西"), Map.of("waybillNo", "00010004")));
        try (var operator = login(port, "operator-xu")) {
            String actor = expect(200, operator.get("/auth/session")).path("employeeId").asText();
            var first = expect(201, operator.post(scan(id), body("00010001"), operator.csrf(), "four-1"));
            assertEquals("RECEIVED", first.path("status").asText());
            assertEquals("东片区", first.path("area").path("name").asText());
            assertEquals("00010001", first.path("waybillNo").asText());
            assertEquals(actor, first.path("receivedBy").asText());
            var pending = expect(201, operator.post(scan(id), body("00010002"), operator.csrf(), "four-2"));
            assertEquals("PENDING_AREA", pending.path("status").asText());
            assertEquals("NO_EFFECTIVE_RULE", pending.path("pendingAreaReason").asText());
            var duplicate = expect(200, operator.post(scan(id), body("00010001"), operator.csrf(), "four-3"));
            assertEquals("ALREADY_RECEIVED", duplicate.path("status").asText());
            for (String field : List.of("receiptId", "receivedAt", "receivedBy", "area")) {
                assertEquals(first.path(field), duplicate.path(field));
            }
            var west = expect(201, operator.post(scan(id), body("00010003"), operator.csrf(), "four-4"));
            assertEquals("西片区", west.path("area").path("name").asText());
            var detail = expect(200, operator.get(BATCHES + "/" + id));
            assertEquals(4, detail.path("expectedCount").asInt());
            assertEquals(3, detail.path("receivedCount").asInt());
            assertEquals(2, detail.path("assignedCount").asInt());
            assertEquals(1, detail.path("pendingAreaCount").asInt());
            assertEquals(2, detail.path("sortCreditCount").asInt());
            assertEquals(actor, expect(200, operator.get("/staff/inbound-receipts/" + first.path("receiptId").asText()))
                    .path("receivedBy").asText());
            assertEquals(3, countForBatch("inbound_receipt", id));
            assertEquals(3, countForBatch("parcel_event", id));
            assertEquals(2, countForBatch("sort_credit", id));
            assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM inbound_receipt WHERE batch_id = :id AND received_by = :actor")
                    .param("id", id).param("actor", actor).query(Integer.class).single());
        }
    }

    @Test
    void crossBatchConcurrentScansCommitOnlyOneReceiptEventAndCredit() throws Exception {
        String first = importBatch("RACE-A", List.of(line("00020001", "东")));
        String second = importBatch("RACE-B", List.of(line("00020001", "东")));
        try (var a = login(port, "operator-xu"); var b = login(port, "operator-local-test");
             var threads = Executors.newFixedThreadPool(2)) {
            var csrfA = a.csrf();
            var csrfB = b.csrf();
            var start = new CountDownLatch(1);
            var left = threads.submit(() -> {
                start.await();
                return a.post(scan(first), body("00020001"), csrfA, "race-a");
            });
            var right = threads.submit(() -> {
                start.await();
                return b.post(scan(second), body("00020001"), csrfB, "race-b");
            });
            start.countDown();
            var responseA = left.get(15, TimeUnit.SECONDS);
            var responseB = right.get(15, TimeUnit.SECONDS);
            assertEquals(List.of(200, 201), java.util.stream.Stream.of(responseA, responseB)
                    .map(java.net.http.HttpResponse::statusCode).sorted().toList());
            var original = expect(201, responseA.statusCode() == 201 ? responseA : responseB);
            var duplicate = expect(200, responseA.statusCode() == 200 ? responseA : responseB);
            assertEquals(original.path("receiptId"), duplicate.path("receiptId"));
            String winner = original.path("batchId").asText();
            String loser = winner.equals(first) ? second : first;
            assertEquals(1, expect(200, a.get(BATCHES + "/" + winner)).path("receivedCount").asInt());
            assertEquals(0, expect(200, a.get(BATCHES + "/" + loser)).path("receivedCount").asInt());
            for (String table : List.of("inbound_receipt", "parcel_event", "sort_credit")) {
                assertEquals(1, countForBatch(table, winner));
                assertEquals(0, countForBatch(table, loser));
            }
        }
    }

    @Test
    void timedOutHttpResponseCanBeRetriedWithExactlyTheOriginalKey() throws Exception {
        String id = importBatch("TIMEOUT", List.of(line("00030001", "东"), line("00030002", "西")));
        try (var operator = login(port, "operator-xu")) {
            var csrf = operator.csrf();
            try {
                assertThrows(HttpTimeoutException.class, () -> operator.post(scan(id), body("00030001"),
                        csrf, HoldFirstResponse.KEY, Duration.ofSeconds(2)));
                assertTrue(timeout.committed.await(5, TimeUnit.SECONDS));
                assertEquals(1, countForBatch("inbound_receipt", id));
            } finally {
                timeout.release.countDown();
            }
            var replay = expect(201, operator.post(scan(id), body("00030001"), csrf, HoldFirstResponse.KEY));
            assertEquals("RECEIVED", replay.path("status").asText());
            assertEquals(Long.parseLong(replay.path("receiptId").asText()), jdbc.sql("""
                    SELECT id FROM inbound_receipt WHERE batch_id = :id
                    """).param("id", id).query(Long.class).single());
            var receipt = expect(200, operator.get("/operations/INBOUND_RECEIPT/" + HoldFirstResponse.KEY));
            assertEquals(replay, receipt.path("result"));
            assertEquals("IDEMPOTENCY_KEY_REUSED", expect(409, operator.post(scan(id), body("00030002"), csrf,
                    HoldFirstResponse.KEY)).path("code").asText());
            assertEquals(1, countForBatch("parcel_event", id));
            assertEquals(1, countForBatch("sort_credit", id));
        }
    }

    @Test
    void eventReceiptAndCreditFailuresLeaveNoPartialStateOrReceipt() throws Exception {
        String id = importBatch("ROLLBACK", List.of(line("00040001", "东")));
        try (var operator = login(port, "operator-rollback-test")) {
            var csrf = operator.csrf();
            long actor = Long.parseLong(expect(200, operator.get("/auth/session")).path("employeeId").asText());
            var columns = Map.of("parcel_event", "actor_employee_id", "inbound_receipt", "received_by",
                    "sort_credit", "employee_id");
            for (String table : List.of("parcel_event", "inbound_receipt", "sort_credit")) {
                jdbc.sql("ALTER TABLE " + table + " ADD CONSTRAINT test_fail_inbound CHECK ("
                        + columns.get(table) + " <> " + actor + ")").update();
                try {
                    expect(500, operator.post(scan(id), body("00040001"), csrf, "rolled-back"));
                    expect(404, operator.get("/operations/INBOUND_RECEIPT/rolled-back"));
                    assertEquals(0, expect(200, operator.get(BATCHES + "/" + id)).path("receivedCount").asInt());
                    assertEquals("EXPECTED", jdbc.sql("SELECT stage FROM parcel WHERE waybill_no = '00040001'")
                            .query(String.class).single());
                    assertEquals(0, countForBatch("inbound_receipt", id));
                    assertEquals(0, countForBatch("parcel_event", id));
                    assertEquals(0, countForBatch("sort_credit", id));
                } finally {
                    jdbc.sql("ALTER TABLE " + table + " DROP CHECK test_fail_inbound").update();
                }
            }
            expect(201, operator.post(scan(id), body("00040001"), csrf, "rolled-back"));
        }
    }

    @Test
    void preservesMissingAddressAndRejectsForeignAndUnlistedScansBeforeAnyStateChanges() throws Exception {
        String id = importBatch("MISSING-ADDRESS", List.of(Map.of("waybillNo", "00050001")));
        importBatch("OTHER-EXPECTED", List.of(line("00050002", "东")));
        try (var operator = login(port, "operator-xu"); var foreign = login(port, "operator-foreign-test");
             var sorter = login(port, "sorter-test")) {
            expect(404, foreign.post(scan(id), body("00050001"), foreign.csrf(), "foreign"));
            expect(403, sorter.post(scan(id), body("00050001"), sorter.csrf(), "sorter"));
            assertEquals("INBOUND_BATCH_MISMATCH", expect(409, operator.post(scan(id), body("00050002"),
                    operator.csrf(), "wrong-batch")).path("code").asText());
            assertEquals("WAYBILL_NOT_EXPECTED", expect(409, operator.post(scan(id), body("00050003"),
                    operator.csrf(), "unlisted")).path("code").asText());
            assertEquals(0, countForBatch("inbound_receipt", id));
            var missing = expect(201, operator.post(scan(id), body("00050001"), operator.csrf(), "missing-address"));
            assertEquals("PENDING_AREA", missing.path("status").asText());
            assertEquals("MISSING_ADDRESS", missing.path("pendingAreaReason").asText());
            assertEquals(1, countForBatch("inbound_receipt", id));
            assertEquals(0, countForBatch("sort_credit", id));
        }
    }

    private void addEmployee(String name, String unit, String role) {
        jdbc.sql("""
                INSERT INTO employee_account (unit_id, login_name, password_hash, role_code, must_change_password)
                SELECT u.id, :name, a.password_hash, :role, FALSE FROM employee_account a
                CROSS JOIN org_unit u WHERE a.login_name = 'operator-xu' AND u.code = :unit
                """).param("name", name).param("role", role).param("unit", unit).update();
    }

    private String importBatch(String no, List<? extends Map<String, ?>> parcels) throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            return expect(201, dispatcher.post(BATCHES, manifest(no, parcels), dispatcher.csrf())).path("id").asText();
        }
    }

    private int countForBatch(String table, String batchId) {
        String sql = switch (table) {
            case "inbound_receipt" -> "SELECT COUNT(*) FROM inbound_receipt WHERE batch_id = :id";
            case "parcel_event" -> """
                    SELECT COUNT(*) FROM parcel_event
                    WHERE JSON_UNQUOTE(JSON_EXTRACT(details, '$.batchId')) = :id
                    """;
            case "sort_credit" -> """
                    SELECT COUNT(*) FROM sort_credit sc JOIN inbound_receipt r ON r.id = sc.receipt_id WHERE r.batch_id = :id
                    """;
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.sql(sql).param("id", batchId).query(Integer.class).single();
    }

    private static String scan(String id) {
        return BATCHES + "/" + id + "/receipts";
    }

    private static Map<String, String> body(String waybill) {
        return Map.of("waybillNo", waybill);
    }

    private static Map<String, String> line(String waybill, String community) {
        return Map.of("waybillNo", waybill, "street", "测试街道", "community", community);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeoutConfiguration {
        @Bean
        HoldFirstResponse holdFirstResponse() {
            return new HoldFirstResponse();
        }
    }

    static class HoldFirstResponse extends OncePerRequestFilter {
        static final String KEY = "timeout-test-original";
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean held = new AtomicBoolean();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!KEY.equals(request.getHeader("Idempotency-Key")) || !held.compareAndSet(false, true)) {
                chain.doFilter(request, response);
                return;
            }
            var buffered = new ContentCachingResponseWrapper(response);
            chain.doFilter(request, buffered);
            committed.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ServletException(failure);
            }
            buffered.copyBodyToResponse();
        }
    }
}
