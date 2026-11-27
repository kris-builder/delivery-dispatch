package org.example.delivery.fulfillment;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.time.BeijingTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class HomeStagingCandidates {
    private final InboundBatches batches;
    private final JdbcClient jdbc;

    HomeStagingCandidates(InboundBatches batches, JdbcClient jdbc) {
        this.batches = batches;
        this.jdbc = jdbc;
    }

    public List<Candidate> find(EmployeePrincipal employee, long batchId) {
        InboundBatches.requireNetworkStaff(employee, false);
        long unitId = employee.unitId();
        batches.requireBatch(unitId, batchId);

        return jdbc.sql("""
                SELECT p.id, p.waybill_no, p.version, area.id AS area_id, area.name AS area_name,
                       a.id AS agreement_id, a.version AS agreement_version, a.method, a.confirmed_at
                FROM inbound_receipt r
                JOIN parcel p ON p.id = r.parcel_id
                JOIN delivery_area area ON area.id = p.address_area_id AND area.network_id = :unit AND area.active
                JOIN delivery_agreement a ON a.active_parcel_id = p.id AND a.method = 'HOME'
                WHERE r.batch_id = :batch AND r.voided_at IS NULL AND p.network_id = :unit
                    AND p.holder_kind = 'NETWORK' AND p.holder_unit_id = :unit AND p.stage = 'IN_STATION'
                    AND p.pending_area_reason IS NULL
                    AND NOT EXISTS (
                        SELECT 1 FROM inbound_quarantine q WHERE q.active_parcel_id = p.id
                    )
                ORDER BY p.id
                """).param("batch", batchId).param("unit", unitId)
                .query((row, index) -> new Candidate(row.getLong("id"), row.getString("waybill_no"),
                        row.getLong("version"), new Area(row.getString("area_id"), row.getString("area_name")),
                        new Agreement(row.getString("agreement_id"), row.getString("agreement_version"),
                                row.getString("method"),
                                BeijingTime.fromDatabase(row.getObject("confirmed_at", LocalDateTime.class)))))
                .list();
    }

    public record Candidate(@JsonFormat(shape = JsonFormat.Shape.STRING) long parcelId, String waybillNo,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) long parcelVersion,
                            Area area, Agreement agreement) {}

    public record Area(String id, String name) {}

    public record Agreement(String id, String version, String method, OffsetDateTime confirmedAt) {}
}
