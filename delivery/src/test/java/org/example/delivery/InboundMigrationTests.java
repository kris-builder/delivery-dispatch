package org.example.delivery;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    void upgradesExistingIdentityAndStationDataThenPreservesExpectedOnlyStateFromV6() {
        long operatorId = jdbc.sql("SELECT id FROM employee_account WHERE login_name = 'operator-xu'").query(Long.class).single();
        long areaId = jdbc.sql("""
                SELECT d.area_id FROM area_duty d JOIN org_unit u ON u.id = (SELECT network_id FROM delivery_area WHERE id = d.area_id)
                WHERE u.code = 'JIANGAN-DEMO' ORDER BY d.area_id LIMIT 1
                """).query(Long.class).single();
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        jdbc.sql("""
                INSERT INTO inbound_batch (network_id, source_system, source_batch_no, source_fingerprint, imported_by, imported_at)
                SELECT unit_id, 'SYNTHETIC', 'V6-DEMO', UNHEX(SHA2('sample', 256)), id, UTC_TIMESTAMP(6)
                FROM employee_account WHERE id = :actor
                """).param("actor", operatorId).update();
        jdbc.sql("""
                INSERT INTO parcel (network_id, waybill_no, street, community, created_at, updated_at)
                SELECT network_id, '0000000021', '后湖大道', '百步亭花园', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM inbound_batch WHERE source_batch_no = 'V6-DEMO'
                """).update();
        jdbc.sql("""
                INSERT INTO inbound_expected (batch_id, parcel_id, street, community, imported_at)
                SELECT b.id, p.id, p.street, p.community, UTC_TIMESTAMP(6)
                FROM inbound_batch b JOIN parcel p ON p.network_id = b.network_id
                WHERE b.source_batch_no = 'V6-DEMO' AND p.waybill_no = '0000000021'
                """).update();
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertEquals(operatorId, jdbc.sql("SELECT imported_by FROM inbound_batch WHERE source_batch_no = 'V6-DEMO'")
                .query(Long.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM area_duty WHERE area_id = :area")
                .param("area", areaId).query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM parcel p JOIN inbound_expected e ON e.parcel_id = p.id
                WHERE p.waybill_no = '0000000021' AND p.stage = 'EXPECTED' AND p.holder_unit_id IS NULL
                      AND p.address_area_id IS NULL AND e.community = '百步亭花园'
                """).query(Integer.class).single());
        for (String table : new String[] {"inbound_receipt", "parcel_event", "sort_credit"}) {
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single());
        }
    }
}
