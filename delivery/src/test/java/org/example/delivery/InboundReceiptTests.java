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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.delivery.fulfillment.InboundAreaMatcher;
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

@Import({TestcontainersConfiguration.class, InboundReceiptTests.MatchingContract.class})
@ActiveProfiles("identity-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "delivery.demo.temporary-password=Demo-Temporary-001")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboundReceiptTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired HoldFirstResponse timeout;

    @BeforeAll
    void activateAccounts() throws Exception {
        for (String account : List.of("dispatcher-zhou", "operator-xu")) {
            activate(port, account);
        }
        addEmployee("operator-local-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        addEmployee("operator-foreign-test", "OTHER-DEMO", "NETWORK_OPERATOR");
        addEmployee("sorter-test", "PICKUP-DEMO-A", "PICKUP_STATION_SORTER");
        addEmployee("operator-rollback-test", "JIANGAN-DEMO", "NETWORK_OPERATOR");
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
    static class MatchingContract {
        @Bean
        InboundAreaMatcher matchKnownFixtureAddresses(JdbcClient jdbc) {
            return (network, street, community) -> {
                String code = switch (community) {
                    case "东", "百步亭花园" -> "EAST";
                    case "西", "安居苑" -> "WEST";
                    default -> null;
                };
                if (code == null) {
                    return new InboundAreaMatcher.Match(null, InboundAreaMatcher.PendingReason.NO_EFFECTIVE_RULE);
                }
                return jdbc.sql("SELECT id, name FROM delivery_area WHERE network_id = :network AND code = :code AND active")
                        .param("network", network).param("code", code).query((row, index) ->
                                new InboundAreaMatcher.Match(new InboundAreaMatcher.Area(row.getLong("id"),
                                        row.getString("name")), null)).single();
            };
        }

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
