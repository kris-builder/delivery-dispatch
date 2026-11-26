package org.example.delivery.fulfillment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.example.delivery.fulfillment.InboundAreaMatcher.PendingReason;
import org.example.delivery.fulfillment.InboundReceipts.ReceiptArea;
import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WaybillLookup {
    private final JdbcClient jdbc;

    WaybillLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result find(EmployeePrincipal employee, String waybill) {
        InboundBatches.requireNetworkStaff(employee, false);
        var parcel = jdbc.sql("""
                SELECT p.id, p.waybill_no, p.address_area_id, p.address_area_name, p.pending_area_reason,
                       r.received_at, b.id AS batch_id, b.source_batch_no
                FROM parcel p
                LEFT JOIN inbound_receipt r ON r.active_parcel_id = p.id
                LEFT JOIN inbound_batch b ON b.id = r.batch_id AND b.network_id = p.network_id
                WHERE p.network_id = :unit AND p.waybill_no = :waybill
                """).param("unit", employee.unitId()).param("waybill", waybill)
                .query((row, index) -> {
                    var received = row.getObject("received_at", LocalDateTime.class);
                    String batchId = row.getString("batch_id");
                    String areaId = row.getString("address_area_id");
                    String reason = row.getString("pending_area_reason");
                    return new Parcel(row.getLong("id"), row.getString("waybill_no"),
                            received == null ? null : received.toInstant(ZoneOffset.UTC),
                            batchId == null ? null : new Batch(batchId, row.getString("source_batch_no")),
                            received == null || areaId == null ? null : new ReceiptArea(areaId, row.getString("address_area_name")),
                            received == null || reason == null ? null : PendingReason.valueOf(reason));
                }).optional().orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "WAYBILL_NOT_FOUND",
                        "本网点查不到该运单，请核对完整单号。"));

        var expected = jdbc.sql("""
                SELECT b.id, b.source_batch_no FROM inbound_expected e
                JOIN inbound_batch b ON b.id = e.batch_id
                WHERE e.parcel_id = :parcel AND b.network_id = :unit ORDER BY b.id
                """).param("parcel", parcel.id()).param("unit", employee.unitId())
                .query((row, index) -> new Batch(row.getString("id"), row.getString("source_batch_no"))).list();
        var events = jdbc.sql("""
                SELECT event_type, occurred_at FROM parcel_event
                WHERE parcel_id = :parcel AND actor_unit_id = :unit ORDER BY occurred_at, id
                """).param("parcel", parcel.id()).param("unit", employee.unitId())
                .query((row, index) -> new Event(row.getString("event_type"),
                        row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))).list();
        Status status = parcel.firstReceivedAt() == null ? Status.EXPECTED
                : parcel.area() == null ? Status.PENDING_AREA : Status.RECEIVED;
        return new Result(parcel.waybillNo(), status, parcel.firstReceivedAt(), parcel.receivedBatch(),
                parcel.area(), parcel.pendingAreaReason(), expected, events);
    }

    enum Status { EXPECTED, PENDING_AREA, RECEIVED }
    private record Parcel(long id, String waybillNo, Instant firstReceivedAt, Batch receivedBatch,
                          ReceiptArea area, PendingReason pendingAreaReason) {}
    record Batch(String id, String sourceBatchNo) {}
    record Event(String type, Instant occurredAt) {}
    record Result(String waybillNo, Status status, Instant firstReceivedAt, Batch receivedBatch,
                  ReceiptArea area, PendingReason pendingAreaReason, List<Batch> expectedBatches, List<Event> events) {}
}
