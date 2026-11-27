package org.example.delivery.fulfillment;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.example.delivery.fulfillment.InboundQuarantines.Kind;
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
class InboundQuarantineReviews {
    private static final String COMMAND = "INBOUND_QUARANTINE_REVIEW";
    static final RowMapper<Review> MAPPER = (row, index) -> new Review(row.getString("quarantine_id"),
            row.getString("status"), row.getString("waybill_no"), row.getString("temporary_no"),
            row.getString("target_batch_id"), row.getString("receipt_id"), row.getString("evidence"),
            BeijingTime.fromDatabase(row.getObject("verified_at", LocalDateTime.class)), row.getString("reviewed_by"),
            BeijingTime.fromDatabase(row.getObject("reviewed_at", LocalDateTime.class)));

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final InboundBatches batches;
    private final InboundReceipts receipts;

    InboundQuarantineReviews(JdbcClient jdbc, ObjectMapper json, InboundBatches batches, InboundReceipts receipts) {
        this.jdbc = jdbc;
        this.json = json;
        this.batches = batches;
        this.receipts = receipts;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Review review(EmployeePrincipal employee, long batch, long id, Input input, String key) {
        InboundBatches.requireNetworkStaff(employee, true);
        InboundReceipts.requireOperationKey(key);
        batches.requireBatch(employee.unitId(), batch);
        var request = validate(input);
        byte[] hash = InboundBatches.hash(json.writeValueAsBytes(new Request(batch, id, request)));
        var operation = receipts.reserveOperation(employee.accountId(), COMMAND, key, hash);
        if (operation.result() != null) {
            return json.readValue(operation.result(), Review.class);
        }
        var pending = jdbc.sql("""
                SELECT parcel_id, waybill_no, temporary_no, kind, status, reported_by
                FROM inbound_quarantine WHERE id = :id AND batch_id = :batch FOR UPDATE
                """).param("id", id).param("batch", batch)
                .query((row, index) -> new Pending(row.getObject("parcel_id", Long.class), row.getString("waybill_no"),
                        row.getString("temporary_no"), Kind.valueOf(row.getString("kind")), row.getString("status"),
                        row.getLong("reported_by"))).optional()
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "INBOUND_QUARANTINE_NOT_FOUND",
                        "本网点查不到该隔离记录。"));
        var now = BeijingTime.now();
        Review result;
        if (!"UPSTREAM_REVIEW".equals(pending.status())) {
            result = originalReview(id, hash);
        } else if (pending.kind() == Kind.DAMAGED) {
            throw blocked("破损件仍有异常拦截，请走破损处置。");
        } else if (request.decision() == Decision.RETURN) {
            result = returnToUpstream(employee, id, pending, request, operation.id(), now);
        } else {
            result = release(employee, id, pending, request, operation.id(), now);
        }
        receipts.completeOperation(operation.id(), result, now);
        return result;
    }

    private Review release(EmployeePrincipal employee, long id, Pending pending, Verified request, long operation,
                           LocalDateTime now) {
        long unit = employee.unitId();
        long targetBatch = request.targetBatch();
        batches.requireBatch(unit, targetBatch);

        String waybill = pending.kind() == Kind.UNLISTED ? pending.waybill() : request.waybill();
        if (waybill == null) throw InboundBatches.invalid("请填写核实后的真实运单号。");
        if (request.waybill() != null && !waybill.equals(request.waybill())) {
            throw InboundBatches.invalid("核对的运单号与暂存记录不一致。");
        }
        var parcel = receipts.lockParcel(unit, waybill).orElseThrow(() -> blocked("查不到该真实运单的应到记录。"));
        if (receipts.activeReceipt(parcel.id()).isPresent()) {
            throw blocked("该运单已有有效实收，请核对是否为同一实物。");
        }
        if (pending.kind() == Kind.UNLISTED) {
            if (!"QUARANTINED".equals(parcel.stage()) || !pending.parcelId().equals(parcel.id())) {
                throw blocked("该暂存件的当前状态不支持放行。");
            }
            supplementExpected(targetBatch, parcel.id(), request, now);
        } else if (!"EXPECTED".equals(parcel.stage())) {
            throw blocked("该运单仍有其他拦截，请先核对异常状态。");
        }
        // The dispatcher records the verification and receipt. The on-site operator retains the sorting credit.
        var receipt = receipts.createReceipt(employee, targetBatch, parcel.id(), waybill, operation, now, false,
                pending.reportedBy());
        return record(employee, id, request, "RELEASED", parcel.id(), waybill,
                Long.parseLong(receipt.receiptId()), operation, now);
    }

    private void supplementExpected(long targetBatch, long parcel, Verified request, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO inbound_expected (batch_id, parcel_id, recipient_phone, street, community, address, imported_at)
                SELECT :batch, id, recipient_phone, COALESCE(:street, street), COALESCE(:community, community), address, :now
                FROM parcel WHERE id = :parcel
                ON DUPLICATE KEY UPDATE parcel_id = inbound_expected.parcel_id
                """).param("batch", targetBatch).param("parcel", parcel).param("street", request.street())
                .param("community", request.community()).param("now", now).update();
    }

    private Review returnToUpstream(EmployeePrincipal employee, long id, Pending pending, Verified request,
                                    long operation, LocalDateTime now) {
        if (pending.parcelId() != null) {
            var parcel = receipts.lockParcel(employee.unitId(), pending.waybill()).orElseThrow(() ->
                    blocked("查不到需要交回的暂存件。"));
            if (!"QUARANTINED".equals(parcel.stage()) || receipts.activeReceipt(parcel.id()).isPresent()) {
                throw blocked("该件的当前状态不支持交回。");
            }
            jdbc.sql("""
                    UPDATE parcel SET stage = 'RETURNED_UPSTREAM', holder_kind = NULL, holder_unit_id = NULL,
                                      version = version + 1, updated_at = :now WHERE id = :id
                    """).param("now", now).param("id", parcel.id()).update();
            var details = json.createObjectNode();
            details.put("quarantineId", Long.toString(id));
            receipts.writeEvent(employee, parcel.id(), operation, now, "INBOUND_RETURNED_UPSTREAM", details);
        }
        return record(employee, id, request, "RETURNED", pending.parcelId(), pending.waybill(), null, operation, now);
    }

    private Review record(EmployeePrincipal employee, long id, Verified request, String status,
                          Long parcel, String waybill, Long receipt, long operation, LocalDateTime now) {
        var verifiedAt = request.verifiedAt() == null ? now : request.verifiedAt().toLocalDateTime();
        jdbc.sql("""
                INSERT INTO inbound_quarantine_review (quarantine_id, operation_receipt_id, status, parcel_id, receipt_id,
                    target_batch_id, waybill_no, evidence, verified_at, reviewed_at, reviewed_by)
                VALUES (:id, :operation, :status, :parcel, :receipt, :batch, :waybill, :evidence, :verified, :now, :actor)
                """).param("id", id).param("operation", operation).param("status", status)
                .param("parcel", parcel).param("receipt", receipt).param("batch", request.targetBatch())
                .param("waybill", waybill).param("evidence", request.evidence()).param("verified", verifiedAt)
                .param("now", now).param("actor", employee.accountId()).update();
        jdbc.sql("UPDATE inbound_quarantine SET status = :status WHERE id = :id")
                .param("status", status).param("id", id).update();
        return jdbc.sql("""
                SELECT r.*, q.temporary_no FROM inbound_quarantine_review r
                JOIN inbound_quarantine q ON q.id = r.quarantine_id WHERE r.quarantine_id = :id
                """).param("id", id).query(MAPPER).single();
    }

    private Review originalReview(long id, byte[] hash) {
        return jdbc.sql("""
                SELECT o.request_hash, o.result FROM inbound_quarantine_review r
                JOIN operation_receipt o ON o.id = r.operation_receipt_id WHERE r.quarantine_id = :id
                """).param("id", id).query((row, index) ->
                new Previous(row.getBytes("request_hash"), row.getString("result")))
                .optional().filter(previous -> Arrays.equals(hash, previous.hash()))
                .map(previous -> json.readValue(previous.result(), Review.class))
                .orElseThrow(() -> blocked("该隔离记录已核对，请查看原核对结果。"));
    }

    private static Verified validate(Input input) {
        if (input.decision() == null) throw InboundBatches.invalid("请选择核对结果。");
        if (input.evidence() == null) throw InboundBatches.invalid("请填写上游核实依据。");
        String evidence = InboundBatches.requiredText(input.evidence(), 500, "上游核实依据");
        var verifiedAt = input.verifiedAt();
        if (verifiedAt != null && !verifiedAt.getOffset().equals(ZoneOffset.ofHours(8))) {
            throw InboundBatches.invalid("核实时间须使用北京时间（+08:00）。");
        }
        if (verifiedAt != null) {
            verifiedAt = BeijingTime.fromDatabase(BeijingTime.forDatabase(verifiedAt.toInstant()));
        }
        if (input.decision() == Decision.RETURN) {
            return new Verified(Decision.RETURN, null, null, evidence, verifiedAt, null, null);
        }
        if (!Boolean.TRUE.equals(input.identityConfirmed()) || input.targetBatchId() == null || input.targetBatchId() <= 0) {
            throw InboundBatches.invalid("请确认核对实物身份并选择核实后的批次。");
        }
        String waybill = input.waybillNo() == null || input.waybillNo().isNull() ? null
                : InboundBatches.requiredText(input.waybillNo(), 64, "真实运单号");
        return new Verified(Decision.RELEASE, input.targetBatchId(), waybill, evidence, verifiedAt,
                addressPart(input.street(), "街道"), addressPart(input.community(), "小区"));
    }

    private static String addressPart(String value, String label) {
        return value == null || value.isBlank() ? null : InboundBatches.requiredText(value, 128, label);
    }

    private static ApiProblems.Failure blocked(String detail) {
        return new ApiProblems.Failure(HttpStatus.CONFLICT, "INBOUND_REVIEW_BLOCKED", detail);
    }

    enum Decision { RELEASE, RETURN }
    record Input(Decision decision, Long targetBatchId, JsonNode waybillNo, Boolean identityConfirmed, String evidence,
                 OffsetDateTime verifiedAt, String street, String community) {}
    private record Verified(Decision decision, Long targetBatch, String waybill, String evidence,
                            OffsetDateTime verifiedAt, String street, String community) {}
    private record Request(long batch, long id, Verified verified) {}
    private record Pending(Long parcelId, String waybill, String temporaryNo, Kind kind, String status, long reportedBy) {}
    private record Previous(byte[] hash, String result) {}
    record Review(String quarantineId, String status, String waybillNo, String temporaryNo, String targetBatchId,
                  String receiptId, String evidence, OffsetDateTime verifiedAt, String reviewedBy, OffsetDateTime reviewedAt) {}
}
