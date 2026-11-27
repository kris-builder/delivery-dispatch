package org.example.delivery.fulfillment;

import java.util.List;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.web.ApiProblems;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads the receipts for the isolated draft demo with predefined home-delivery agreements. */
@Service
@Profile("planning-demo")
public class PlanningDemoReceipts {
    private final JdbcClient jdbc;

    public PlanningDemoReceipts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReceiptSnapshot> confirmedHomeReceipts(EmployeePrincipal employee, long batchId) {
        InboundBatches.requireNetworkStaff(employee, true);
        boolean demoBatch = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM inbound_batch b JOIN org_unit u ON u.id = b.network_id
                    WHERE b.id = :batch AND b.network_id = :unit AND u.code = 'JIANGAN-DEMO'
                      AND b.source_system = 'SYNTHETIC' AND b.source_batch_no = 'PLANNING-03-DEMO'
                )
                """).param("batch", batchId).param("unit", employee.unitId()).query(Boolean.class).single();
        if (!demoBatch) {
            throw new ApiProblems.Failure(HttpStatus.NOT_FOUND, "PLANNING_BATCH_NOT_FOUND",
                    "本网点查不到该草案样例批次。");
        }
        return jdbc.sql("""
                SELECT p.id, p.waybill_no, p.address_area_id, p.version
                FROM inbound_receipt r JOIN parcel p ON p.id = r.parcel_id
                WHERE r.batch_id = :batch AND r.voided_at IS NULL AND p.network_id = :unit
                  AND p.stage = 'IN_STATION' AND p.holder_kind = 'NETWORK' AND p.holder_unit_id = :unit
                  AND p.waybill_no IN ('000726030001', '000726030002', '000726030003')
                ORDER BY p.id
                """).param("batch", batchId).param("unit", employee.unitId())
                .query((row, index) -> new ReceiptSnapshot(row.getLong("id"), row.getString("waybill_no"),
                        row.getString("address_area_id"), row.getLong("version"))).list();
    }

    public record ReceiptSnapshot(long parcelId, String waybillNo, String addressAreaId, long version) {}
}
