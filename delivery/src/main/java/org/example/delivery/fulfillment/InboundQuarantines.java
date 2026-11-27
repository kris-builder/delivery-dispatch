package org.example.delivery.fulfillment;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.time.BeijingTime;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class InboundQuarantines {
    private static final String COMMAND = "INBOUND_QUARANTINE";

    static final RowMapper<Quarantine> MAPPER = (row, index) -> new Quarantine(row.getString("id"), row.getString("batch_id"),
            row.getString("waybill_no"), row.getString("temporary_no"), Kind.valueOf(row.getString("kind")),
            row.getString("status"), row.getString("note"), row.getString("evidence_ref"), row.getString("reported_by"),
            BeijingTime.fromDatabase(row.getObject("reported_at", LocalDateTime.class)));

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final InboundBatches batches;
    private final InboundReceipts receipts;

    InboundQuarantines(JdbcClient jdbc, ObjectMapper json, InboundBatches batches, InboundReceipts receipts) {
        this.jdbc = jdbc;
        this.json = json;
        this.batches = batches;
        this.receipts = receipts;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Quarantine register(EmployeePrincipal employee, long batch, Input input, String key) {
        InboundReceipts.requireOperator(employee);
        InboundReceipts.requireOperationKey(key);
        batches.requireBatch(employee.unitId(), batch);
        var report = validate(input);
        byte[] hash = InboundBatches.hash(json.writeValueAsBytes(new Request(batch, report)));
        var operation = receipts.reserveOperation(employee.accountId(), COMMAND, key, hash);
        if (operation.result() != null) {
            return json.readValue(operation.result(), Quarantine.class);
        }
        var now = BeijingTime.now();
        Quarantine result = report.kind() == Kind.MISSING_LABEL
                ? insert(employee, batch, report, null, null, "IN-" + operation.id(), operation.id(), now)
                : registerKnown(employee, batch, report, operation.id(), now);
        receipts.completeOperation(operation.id(), result, now);
        return result;
    }

    private Quarantine registerKnown(EmployeePrincipal employee, long batch, Report report, long operation,
                                    LocalDateTime now) {
        long unit = employee.unitId();
        if (report.kind() == Kind.UNLISTED) {
            // Use the same network/waybill row as import and receipt so the three writers serialize.
            jdbc.sql("""
                    INSERT INTO parcel (network_id, waybill_no, created_at, updated_at)
                    VALUES (:unit, :waybill, :now, :now)
                    ON DUPLICATE KEY UPDATE id = parcel.id
                    """).param("unit", unit).param("waybill", report.waybill()).param("now", now).update();
        }
        var parcel = receipts.lockParcel(unit, report.waybill()).orElseThrow(() ->
                new ApiProblems.Failure(HttpStatus.CONFLICT, "WAYBILL_NOT_EXPECTED", "请先核对该运单的应到批次。"));
        var existing = jdbc.sql("SELECT * FROM inbound_quarantine WHERE active_parcel_id = :parcel")
                .param("parcel", parcel.id()).query(MAPPER).optional();
        if (existing.isPresent()) {
            if (existing.get().kind() != report.kind()) {
                throw new ApiProblems.Failure(HttpStatus.CONFLICT, "WAYBILL_QUARANTINED", "此件已隔离待核。");
            }
            return existing.get();
        }
        if (receipts.activeReceipt(parcel.id()).isPresent()) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "WAYBILL_ALREADY_RECEIVED", "此件已有实收，请核对现场记录。");
        }

        if (report.kind() == Kind.DAMAGED) {
            var receipt = receipts.createReceipt(employee, batch, parcel.id(), report.waybill(), operation, now, true);
            return insert(employee, batch, report, parcel.id(), Long.parseLong(receipt.receiptId()), null, operation, now);
        }

        boolean expectedInSelected = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM inbound_expected WHERE batch_id = :batch AND parcel_id = :parcel)
                """).param("batch", batch).param("parcel", parcel.id()).query(Boolean.class).single();
        if (expectedInSelected) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "WAYBILL_ALREADY_EXPECTED", "此件在所选批次清单中，请按到货情况办理实收。");
        }
        if (receipts.isExpected(parcel.id())) {
            throw InboundReceipts.batchMismatch();
        }
        jdbc.sql("""
                UPDATE parcel SET stage = 'QUARANTINED', holder_kind = 'NETWORK', holder_unit_id = :unit,
                                  version = version + 1, updated_at = :now
                WHERE id = :parcel
                """).param("unit", unit).param("now", now).param("parcel", parcel.id()).update();
        return insert(employee, batch, report, parcel.id(), null, null, operation, now);
    }

    private Quarantine insert(EmployeePrincipal employee, long batch, Report report, Long parcel, Long receipt,
                              String temporaryNo, long operation, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO inbound_quarantine (batch_id, parcel_id, receipt_id, waybill_no, temporary_no, kind,
                                                note, evidence_ref, reported_by, reported_at, operation_receipt_id)
                VALUES (:batch, :parcel, :receipt, :waybill, :temporary, :kind, :note, :evidence, :employee, :now, :operation)
                """).param("batch", batch).param("parcel", parcel).param("receipt", receipt)
                .param("waybill", report.waybill()).param("temporary", temporaryNo).param("kind", report.kind().name())
                .param("note", report.note()).param("evidence", report.evidenceRef())
                .param("employee", employee.accountId()).param("now", now).param("operation", operation).update();
        return jdbc.sql("SELECT * FROM inbound_quarantine WHERE operation_receipt_id = :operation")
                .param("operation", operation).query(MAPPER).single();
    }

    private static Report validate(Input input) {
        if (input.kind() == null) throw InboundBatches.invalid("请选择隔离类型。");
        String waybill = null;
        if (input.kind() == Kind.MISSING_LABEL) {
            if (input.waybillNo() != null && !input.waybillNo().isNull()) {
                throw InboundBatches.invalid("无法辨认面单时，请用临时编号登记。");
            }
        } else {
            waybill = InboundBatches.requiredText(input.waybillNo(), 64, "运单号");
        }
        if (input.kind() == Kind.UNLISTED && !Boolean.TRUE.equals(input.physicalConfirmed())) {
            throw InboundBatches.invalid("请确认清单外实物确实在场。");
        }
        String note = input.note();
        if (note == null || note.isBlank()) {
            if (input.kind() != Kind.UNLISTED) throw InboundBatches.invalid("请填写现场说明。");
            note = "";
        } else {
            note = InboundBatches.requiredText(note, 500, "现场说明");
        }
        String evidence = input.evidenceRef() == null || input.evidenceRef().isBlank() ? null
                : InboundBatches.requiredText(input.evidenceRef(), 255, "凭证编号");
        return new Report(input.kind(), waybill, note, evidence);
    }

    enum Kind { DAMAGED, MISSING_LABEL, UNLISTED }
    record Input(Kind kind, JsonNode waybillNo, String note, String evidenceRef, Boolean physicalConfirmed) {}
    private record Report(Kind kind, String waybill, String note, String evidenceRef) {}
    private record Request(long batch, Report report) {}
    record Quarantine(String id, String batchId, String waybillNo, String temporaryNo, Kind kind, String status,
                      String note, String evidenceRef, String reportedBy, OffsetDateTime reportedAt) {}
}
