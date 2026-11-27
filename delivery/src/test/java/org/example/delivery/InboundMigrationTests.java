package org.example.delivery;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("identity-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.target=3", "delivery.demo.temporary-password=Demo-Temporary-001"
})
class InboundMigrationTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;

    @Test
    void preservesExpectedAndReceivedParcelsWhenUpgradingLegacyTimestamps() {
        long operatorId = jdbc.sql("SELECT id FROM employee_account WHERE login_name = 'operator-xu'").query(Long.class).single();
        long areaId = jdbc.sql("""
                SELECT d.area_id FROM area_duty d JOIN org_unit u ON u.id = (SELECT network_id FROM delivery_area WHERE id = d.area_id)
                WHERE u.code = 'JIANGAN-DEMO' ORDER BY d.area_id LIMIT 1
                """).query(Long.class).single();
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        jdbc.sql("""
                INSERT INTO inbound_batch (network_id, source_system, source_batch_no, source_fingerprint, imported_by, imported_at)
                SELECT unit_id, 'SYNTHETIC', 'V6-DEMO', UNHEX(SHA2('sample', 256)), id, '2026-11-25 16:30:00.123456'
                FROM employee_account WHERE id = :actor
                """).param("actor", operatorId).update();
        jdbc.sql("""
                INSERT INTO parcel (network_id, waybill_no, street, community, created_at, updated_at)
                SELECT network_id, '0000000021', '后湖大道', '百步亭花园',
                    '2026-11-25 16:30:00.123456', '2026-11-25 16:30:00.123456'
                FROM inbound_batch WHERE source_batch_no = 'V6-DEMO'
                """).update();
        jdbc.sql("""
                INSERT INTO inbound_expected (batch_id, parcel_id, street, community, imported_at)
                SELECT b.id, p.id, p.street, p.community, '2026-11-25 16:30:00.123456'
                FROM inbound_batch b JOIN parcel p ON p.network_id = b.network_id
                WHERE b.source_batch_no = 'V6-DEMO' AND p.waybill_no = '0000000021'
                """).update();
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        new ResourceDatabasePopulator(new ClassPathResource("beijing-legacy-receipt.sql")).execute(dataSource);
        Flyway.configure().dataSource(dataSource).target("9").load().migrate();
        String receiptState = """
                SELECT CONCAT(r.received_at, '|', c.credited_at, '|', c.employee_id, '|', p.stage)
                FROM inbound_receipt r JOIN sort_credit c ON c.receipt_id = r.id JOIN parcel p ON p.id = r.parcel_id
                WHERE r.id = 9001
                """;
        String priorReceipt = jdbc.sql(receiptState).query(String.class).single();
        Flyway.configure().dataSource(dataSource).target("11").load().migrate();
        assertEquals(priorReceipt, jdbc.sql(receiptState).query(String.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM inbound_quarantine").query(Integer.class).single());
        jdbc.sql("""
                INSERT INTO inbound_quarantine (batch_id, temporary_no, kind, note, reported_by, reported_at, operation_receipt_id)
                SELECT batch_id, 'IN-LEGACY', 'MISSING_LABEL', '面单模糊', received_by, received_at, 9001
                FROM inbound_receipt WHERE id = 9001
                """).update();
        Flyway.configure().dataSource(dataSource).target("12").load().migrate();
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM delivery_agreement").query(Integer.class).single());
        assertEquals(priorReceipt, jdbc.sql(receiptState).query(String.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM inbound_quarantine WHERE temporary_no = 'IN-LEGACY'
                  AND status = 'UPSTREAM_REVIEW' AND reported_at = '2026-11-26 00:30:00.123456'
                """).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM inbound_quarantine_review").query(Integer.class).single());
        assertEquals(operatorId, jdbc.sql("SELECT imported_by FROM inbound_batch WHERE source_batch_no = 'V6-DEMO'")
                .query(Long.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM area_duty WHERE area_id = :area")
                .param("area", areaId).query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM parcel p JOIN inbound_expected e ON e.parcel_id = p.id
                WHERE p.waybill_no = '0000000021' AND p.stage = 'EXPECTED' AND p.holder_unit_id IS NULL
                      AND p.address_area_id IS NULL AND e.community = '百步亭花园'
                """).query(Integer.class).single());
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM inbound_receipt r JOIN parcel p ON p.id = r.parcel_id
                WHERE p.waybill_no = '0000000021'
                """).query(Integer.class).single());
        String beijing = "2026-11-26 00:30:00.123456";
        assertEquals(beijing, jdbc.sql("""
                SELECT DATE_FORMAT(imported_at, '%Y-%m-%d %H:%i:%s.%f')
                FROM inbound_batch WHERE source_batch_no = 'V6-DEMO'
                """).query(String.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM inbound_receipt r
                JOIN parcel_event e ON e.id = r.scan_event_id
                JOIN sort_credit c ON c.receipt_id = r.id
                JOIN operation_receipt o ON o.id = e.operation_receipt_id
                WHERE r.id = 9001 AND r.received_at = :beijing AND r.voided_at IS NULL
                  AND e.occurred_at = :beijing AND c.credited_at = :beijing AND o.committed_at = :beijing
                """).param("beijing", beijing).query(Integer.class).single());
        assertEquals("2026-11-26T00:30:00.123456+08:00", jdbc.sql("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(result, '$.result.receivedAt'))
                FROM operation_receipt WHERE id = 9001
                """).query(String.class).single());
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertEquals(beijing, jdbc.sql("""
                SELECT DATE_FORMAT(received_at, '%Y-%m-%d %H:%i:%s.%f') FROM inbound_receipt WHERE id = 9001
                """).query(String.class).single());
    }
}
