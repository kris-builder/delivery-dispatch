package org.example.delivery.fulfillment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.time.BeijingTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SortCredits {
    private final JdbcClient jdbc;

    SortCredits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Statement findMine(EmployeePrincipal employee, LocalDate date) {
        InboundReceipts.requireOperator(employee);
        LocalDate day = date == null ? BeijingTime.now().toLocalDate() : date;
        var items = jdbc.sql("""
                SELECT c.receipt_id, p.waybill_no, c.credited_at, e.event_type
                FROM sort_credit c
                JOIN inbound_receipt r ON r.id = c.receipt_id
                JOIN parcel p ON p.id = r.parcel_id
                JOIN parcel_event e ON e.id = c.source_event_id
                WHERE c.unit_id = :unit AND c.employee_id = :employee
                  AND c.credited_at >= :start AND c.credited_at < :end
                  AND c.void_event_id IS NULL AND r.voided_at IS NULL
                ORDER BY c.credited_at DESC, c.receipt_id DESC
                """).param("unit", employee.unitId()).param("employee", employee.accountId())
                .param("start", day.atStartOfDay()).param("end", day.plusDays(1).atStartOfDay())
                .query((row, index) -> new Credit(row.getString("receipt_id"), row.getString("waybill_no"),
                        BeijingTime.fromDatabase(row.getObject("credited_at", LocalDateTime.class)),
                        row.getString("event_type"))).list();
        return new Statement(day, items.size(), items);
    }

    record Credit(String receiptId, String waybillNo, OffsetDateTime creditedAt, String source) {}
    record Statement(LocalDate businessDate, int effectiveCount, List<Credit> items) {}
}
