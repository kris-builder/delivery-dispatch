package org.example.delivery;

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
import static org.example.delivery.EmployeeSessionTests.TEMP;
import static org.example.delivery.EmployeeSessionTests.UPDATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("identity-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"delivery.demo.temporary-password=Demo-Temporary-001", "spring.application.name=delivery-inbound-batch-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboundBatchTests {
    static final String BATCHES = "/staff/inbound-batches";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    @BeforeAll
    void activateAccounts() throws Exception {
        for (String name : List.of("dispatcher-zhou", "dispatcher-other", "operator-xu", "station-leader-a")) {
            activate(port, name);
        }
    }

    @Test
    void importsOnlyExpectedPiecesAndReplaysTheSameManifest() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou"); var operator = login(port, "operator-xu")) {
            var first = Map.of("waybillNo", "000726050018", "street", "后湖大道", "community", "百步亭花园",
                    "recipientPhone", "+8610000000101");
            var second = Map.of("waybillNo", "000726050021");
            var manifest = manifest("BATCH-IMPORT", List.of(first, second));
            var batch = expect(201, dispatcher.post(BATCHES, manifest, dispatcher.csrf()));
            String id = batch.path("id").asText();
            assertEquals(2, batch.path("expectedCount").asInt());
            assertEquals(0, batch.path("receivedCount").asInt());
            assertEquals("000726050018", batch.path("expected").get(0).path("waybillNo").asText());
            assertEquals("后湖大道", batch.path("expected").get(0).path("street").asText());
            assertEquals(batch, expect(200, operator.get(BATCHES + "/" + id)));
            assertTrue(expect(200, operator.get(BATCHES)).findValues("id").stream()
                    .anyMatch(value -> id.equals(value.asText())));
            assertEquals(batch, expect(200, dispatcher.post(BATCHES,
                    manifest("BATCH-IMPORT", List.of(second, first)), dispatcher.csrf())));
            assertEquals("INBOUND_MANIFEST_CONFLICT", expect(409, dispatcher.post(BATCHES,
                    manifest("BATCH-IMPORT", List.of(first)), dispatcher.csrf())).path("code").asText());
            assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM inbound_expected WHERE batch_id = :id")
                    .param("id", id).query(Integer.class).single());
            assertEquals("+8610000000101", jdbc.sql("SELECT recipient_phone FROM parcel WHERE waybill_no = '000726050018'")
                    .query(String.class).single());
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM parcel WHERE waybill_no IN ('000726050018', '000726050021') AND holder_unit_id IS NOT NULL")
                    .query(Integer.class).single());
        }
    }

    @Test
    void checksSessionRoleCsrfNetworkAndRejectsNumericWaybills() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou"); var external = login(port, "dispatcher-other");
             var operator = login(port, "operator-xu"); var leader = login(port, "station-leader-a")) {
            var manifest = manifest("BATCH-SCOPE", List.of(Map.of("waybillNo", "0007")));
            expect(403, dispatcher.post(BATCHES, manifest, null));
            expect(403, external.post(BATCHES, manifest, external.csrf()));
            expect(403, operator.post(BATCHES, manifest, operator.csrf()));
            expect(403, leader.get(BATCHES));
            expect(400, dispatcher.post(BATCHES, manifest("BATCH-SCOPE", List.of(Map.of("waybillNo", 7))), dispatcher.csrf()));
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM inbound_batch WHERE source_batch_no = 'BATCH-SCOPE'")
                    .query(Integer.class).single());
            var batch = expect(201, dispatcher.post(BATCHES, manifest, dispatcher.csrf()));
            expect(404, external.get(BATCHES + "/" + batch.path("id").asText()));
            assertEquals(0, expect(200, external.get(BATCHES)).size());
        }
    }

    @Test
    void twoExpectedBatchesReferenceOneParcelAndKeepTheirOwnSourceAddresses() throws Exception {
        try (var dispatcher = login(port, "dispatcher-zhou")) {
            var first = expect(201, dispatcher.post(BATCHES, manifest("BATCH-X",
                    List.of(Map.of("waybillNo", "0008", "street", "后湖大道"))), dispatcher.csrf()));
            var other = expect(201, dispatcher.post(BATCHES, manifest("BATCH-Y",
                    List.of(Map.of("waybillNo", "0008", "street", "安居路"))), dispatcher.csrf()));
            assertEquals("后湖大道", first.path("expected").get(0).path("street").asText());
            assertEquals("安居路", other.path("expected").get(0).path("street").asText());
            assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM parcel WHERE waybill_no = '0008'").query(Integer.class).single());
            assertEquals(2, jdbc.sql("""
                    SELECT COUNT(*) FROM inbound_expected e JOIN parcel p ON p.id = e.parcel_id WHERE p.waybill_no = '0008'
                    """).query(Integer.class).single());
        }
    }

    static Map<String, ?> manifest(String code, List<? extends Map<String, ?>> parcels) {
        return Map.of("targetNetworkCode", "JIANGAN-DEMO", "sourceBatchNo", code, "parcels", parcels);
    }

    static void activate(int port, String name) throws Exception {
        try (var client = new AuthHttpClient(port)) {
            expect(200, client.login(name, TEMP));
            expect(200, client.post("/employee-auth/password-changes",
                    Map.of("temporaryPassword", TEMP, "newPassword", UPDATED), client.csrf()));
        }
    }

    static AuthHttpClient login(int port, String name) throws Exception {
        var client = new AuthHttpClient(port);
        try {
            expect(200, client.login(name, UPDATED));
            return client;
        } catch (Exception | AssertionError failure) {
            client.close();
            throw failure;
        }
    }
}
