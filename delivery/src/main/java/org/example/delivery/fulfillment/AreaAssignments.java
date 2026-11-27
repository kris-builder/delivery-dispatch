package org.example.delivery.fulfillment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.example.delivery.fulfillment.InboundAreaMatcher.PendingReason;
import org.example.delivery.fulfillment.InboundReceipts.ReceiptArea;
import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.AreaRules;
import org.example.delivery.time.BeijingTime;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class AreaAssignments {
    private static final String COMMAND = "MANUAL_AREA_ASSIGNMENT";
    private static final String EVENT = "MANUAL_AREA_ASSIGNED";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AreaRules rules;
    private final InboundReceipts receipts;

    AreaAssignments(JdbcClient jdbc, ObjectMapper json, AreaRules rules, InboundReceipts receipts) {
        this.jdbc = jdbc;
        this.json = json;
        this.rules = rules;
        this.receipts = receipts;
    }

    @Transactional(readOnly = true)
    public List<PendingParcel> pending(EmployeePrincipal employee) {
        InboundReceipts.requireOperator(employee);
        return jdbc.sql("""
                SELECT p.id, p.waybill_no, p.pending_area_reason, b.source_batch_no
                FROM parcel p JOIN inbound_receipt r ON r.active_parcel_id = p.id
                JOIN inbound_batch b ON b.id = r.batch_id
                WHERE p.network_id = :unit AND p.holder_unit_id = :unit
                  AND p.stage = 'IN_STATION' AND p.holder_kind = 'NETWORK'
                  AND p.address_area_id IS NULL AND p.pending_area_reason IS NOT NULL
                ORDER BY r.received_at, p.id
                """).param("unit", employee.unitId()).query((row, index) -> new PendingParcel(row.getString("id"),
                        row.getString("waybill_no"), row.getString("source_batch_no"),
                        PendingReason.valueOf(row.getString("pending_area_reason")))).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment assign(EmployeePrincipal employee, long parcelId, long areaId, String key) {
        InboundReceipts.requireOperator(employee);
        InboundReceipts.requireOperationKey(key);
        if (areaId <= 0) {
            throw InboundBatches.invalid("请选择目标片区。");
        }
        byte[] hash = InboundBatches.hash((parcelId + "\n" + areaId).getBytes(StandardCharsets.UTF_8));
        var operation = receipts.reserveOperation(employee.accountId(), COMMAND, key, hash);
        var parcel = jdbc.sql("""
                SELECT waybill_no, address_area_id, pending_area_reason,
                       stage = 'IN_STATION' AND holder_kind = 'NETWORK' AND holder_unit_id = :unit AS in_station
                FROM parcel WHERE network_id = :unit AND id = :id FOR UPDATE
                """).param("unit", employee.unitId()).param("id", parcelId)
                .query((row, index) -> new Parcel(row.getString("waybill_no"), row.getObject("address_area_id", Long.class),
                        row.getString("pending_area_reason"), row.getBoolean("in_station"))).optional()
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "WAYBILL_NOT_FOUND", "本网点查不到该运单。"));
        if (operation.result() != null) {
            return json.readValue(operation.result(), Assignment.class);
        }
        if (!parcel.inStation()) {
            throw conflict();
        }
        long receipt = jdbc.sql("SELECT id FROM inbound_receipt WHERE active_parcel_id = :parcel")
                .param("parcel", parcelId).query(Long.class).optional().orElseThrow(AreaAssignments::conflict);
        var now = BeijingTime.now();
        Assignment result;
        if (parcel.areaId() != null) {
            if (parcel.areaId() != areaId) {
                throw conflict();
            }
            result = recordedAssignment(receipt);
        } else {
            if (parcel.pendingReason() == null) {
                throw conflict();
            }
            result = applyAssignment(employee, parcelId, areaId, parcel, receipt, operation.id(), now);
        }
        receipts.completeOperation(operation.id(), result, now);
        return result;
    }

    private Assignment recordedAssignment(long receipt) {
        return jdbc.sql("""
                SELECT o.result FROM sort_credit c JOIN parcel_event e ON e.id = c.source_event_id
                JOIN operation_receipt o ON o.id = e.operation_receipt_id
                WHERE c.receipt_id = :receipt AND c.void_event_id IS NULL AND e.event_type = :event
                """).param("receipt", receipt).param("event", EVENT).query(String.class).optional()
                .map(result -> json.readValue(result, Assignment.class)).orElseThrow(AreaAssignments::conflict);
    }

    private Assignment applyAssignment(EmployeePrincipal employee, long parcelId, long areaId, Parcel parcel,
                                       long receipt, long operation, LocalDateTime now) {
        var area = rules.findActiveArea(employee.unitId(), areaId).orElseThrow(() ->
                new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_AREA", "请选择本网点启用的片区。"));
        jdbc.sql("""
                UPDATE parcel SET address_area_id = :area, address_area_name = :name, pending_area_reason = NULL,
                                  version = version + 1, updated_at = :now
                WHERE id = :parcel
                """).param("area", area.id()).param("name", area.name()).param("now", now).param("parcel", parcelId).update();
        var details = json.createObjectNode();
        details.putNull("previousAreaId");
        details.put("previousPendingReason", parcel.pendingReason());
        details.put("areaId", Long.toString(area.id()));
        details.put("areaName", area.name());
        long event = receipts.writeEvent(employee, parcelId, operation, now, EVENT, details);
        receipts.insertSortCredit(employee, receipt, event, now);
        return new Assignment(parcel.waybillNo(), new ReceiptArea(Long.toString(area.id()), area.name()),
                BeijingTime.fromDatabase(now), Long.toString(employee.accountId()));
    }

    private static ApiProblems.Failure conflict() {
        return new ApiProblems.Failure(HttpStatus.CONFLICT, "AREA_ASSIGNMENT_CONFLICT",
                "包裹当前不能补分，请刷新待分区清单。");
    }

    private record Parcel(String waybillNo, Long areaId, String pendingReason, boolean inStation) {}
    record PendingParcel(String parcelId, String waybillNo, String sourceBatchNo, PendingReason pendingAreaReason) {}
    record Assignment(String waybillNo, ReceiptArea area, OffsetDateTime assignedAt, String assignedBy) {}
}
