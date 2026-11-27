package org.example.delivery.fulfillment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.example.delivery.fulfillment.InboundAreaMatcher.Match;
import org.example.delivery.fulfillment.InboundAreaMatcher.PendingReason;
import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.time.BeijingTime;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class InboundReceipts {
    static final String COMMAND = "INBOUND_RECEIPT";
    private static final String RECEIPTS = """
            SELECT r.id, r.batch_id, p.waybill_no, r.received_by, r.received_at, r.voided_at,
                   p.address_area_id, p.address_area_name, p.pending_area_reason, p.stage
            FROM inbound_receipt r JOIN parcel p ON p.id = r.parcel_id
            """;
    private static final RowMapper<Receipt> RECEIPT_MAPPER = (row, index) -> {
        String areaId = row.getString("address_area_id");
        String reason = row.getString("pending_area_reason");
        String status = "QUARANTINED".equals(row.getString("stage")) ? "QUARANTINED"
                : areaId == null ? "PENDING_AREA" : "RECEIVED";
        return new Receipt(status, row.getString("id"), row.getString("batch_id"),
                row.getString("waybill_no"), BeijingTime.fromDatabase(row.getObject("received_at", LocalDateTime.class)),
                row.getString("received_by"), areaId == null ? null : new ReceiptArea(areaId, row.getString("address_area_name")),
                reason == null ? null : PendingReason.valueOf(reason), row.getObject("voided_at") != null);
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final InboundBatches batches;
    private final Optional<InboundAreaMatcher> matcher;

    InboundReceipts(JdbcClient jdbc, ObjectMapper json, InboundBatches batches, Optional<InboundAreaMatcher> matcher) {
        this.jdbc = jdbc;
        this.json = json;
        this.batches = batches;
        this.matcher = matcher;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Operation scan(EmployeePrincipal employee, long batchId, String waybill, String key) {
        requireOperator(employee);
        requireOperationKey(key);
        batches.requireBatch(employee.unitId(), batchId);
        byte[] hash = InboundBatches.hash((batchId + "\n" + waybill).getBytes(StandardCharsets.UTF_8));
        var operation = reserveOperation(employee.accountId(), COMMAND, key, hash);
        if (operation.result() != null) {
            return json.readValue(operation.result(), Operation.class);
        }
        var parcel = lockParcel(employee.unitId(), waybill).orElseThrow(InboundReceipts::unlisted);
        var active = activeReceipt(parcel.id());
        if (active.isEmpty() && "QUARANTINED".equals(parcel.stage())) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "WAYBILL_QUARANTINED", "此件已暂存，等待上游核对。");
        }
        LocalDateTime now = BeijingTime.now();
        Operation response = active.isPresent()
                ? new Operation(200, active.get().asDuplicate())
                : new Operation(201, createReceipt(employee, batchId, parcel.id(), waybill, operation.id(), now, false));
        completeOperation(operation.id(), response, now);
        return response;
    }

    @Transactional(readOnly = true)
    public Receipt receipt(EmployeePrincipal employee, long id) {
        InboundBatches.requireNetworkStaff(employee, false);
        return jdbc.sql(RECEIPTS + " WHERE r.id = :id AND p.network_id = :network")
                .param("id", id).param("network", employee.unitId()).query(RECEIPT_MAPPER).optional()
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "INBOUND_RECEIPT_NOT_FOUND",
                        "本网点查不到该入站记录。"));
    }

    @Transactional(readOnly = true)
    public Operation operation(EmployeePrincipal employee, String key) {
        InboundBatches.requireNetworkStaff(employee, false);
        requireOperationKey(key);
        var operation = jdbc.sql("""
                SELECT result FROM operation_receipt
                WHERE actor_type = 'EMPLOYEE' AND actor_id = :actor AND command = :command AND operation_key = :key
                """).param("actor", employee.accountId()).param("command", COMMAND).param("key", key).query(String.class)
                .optional().map(result -> json.readValue(result, Operation.class))
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND",
                        "未查到这次操作的已提交结果。"));
        batches.requireBatch(employee.unitId(), Long.parseLong(operation.result().batchId()));
        return operation;
    }

    void completeOperation(long id, Object result, LocalDateTime now) {
        jdbc.sql("UPDATE operation_receipt SET result = :result, committed_at = :now WHERE id = :id")
                .param("result", json.writeValueAsString(result)).param("now", now).param("id", id).update();
    }

    StoredOperation reserveOperation(long actor, String command, String key, byte[] hash) {
        jdbc.sql("""
                INSERT INTO operation_receipt (actor_type, actor_id, command, operation_key, request_hash)
                VALUES ('EMPLOYEE', :actor, :command, :key, :hash)
                ON DUPLICATE KEY UPDATE id = operation_receipt.id
                """).param("actor", actor).param("command", command).param("key", key).param("hash", hash).update();
        var operation = jdbc.sql("""
                SELECT id, request_hash, result FROM operation_receipt
                WHERE actor_type = 'EMPLOYEE' AND actor_id = :actor AND command = :command AND operation_key = :key
                FOR UPDATE
                """).param("actor", actor).param("command", command).param("key", key)
                .query((row, i) -> new StoredOperation(row.getLong("id"), row.getBytes("request_hash"), row.getString("result")))
                .single();
        if (!Arrays.equals(hash, operation.hash())) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "此操作标识已用于不同的请求。");
        }
        return operation;
    }

    Optional<LockedParcel> lockParcel(long unit, String waybill) {
        return jdbc.sql("SELECT id, stage FROM parcel WHERE network_id = :network AND waybill_no = :waybill FOR UPDATE")
                .param("network", unit).param("waybill", waybill)
                .query((row, index) -> new LockedParcel(row.getLong("id"), row.getString("stage"))).optional();
    }

    Optional<Receipt> activeReceipt(long parcel) {
        return jdbc.sql(RECEIPTS + " WHERE r.active_parcel_id = :parcel")
                .param("parcel", parcel).query(RECEIPT_MAPPER).optional();
    }

    Receipt createReceipt(EmployeePrincipal employee, long batch, long parcel, String waybill, long operation,
                          LocalDateTime now, boolean damaged) {
        ExpectedAddress expected = jdbc.sql("""
                SELECT recipient_phone, street, community, address FROM inbound_expected
                WHERE batch_id = :batch AND parcel_id = :parcel
                """).param("batch", batch).param("parcel", parcel).query((row, index) -> new ExpectedAddress(
                        row.getString("recipient_phone"), row.getString("street"), row.getString("community"),
                        row.getString("address"))).optional().orElseThrow(() -> wrongBatch(parcel));
        Match match = damaged ? null : matchAddress(employee.unitId(), expected);
        Long areaId = match == null || match.area() == null ? null : match.area().id();
        String name = areaId == null ? null : match.area().name();
        PendingReason pending = match == null ? null : match.pendingReason();
        jdbc.sql("""
                UPDATE parcel SET stage = :stage, holder_kind = 'NETWORK', holder_unit_id = :unit,
                    recipient_phone = :phone, street = :street, community = :community, address = :address,
                    address_area_id = :area, address_area_name = :areaName, pending_area_reason = :pending,
                    version = version + 1, updated_at = :now
                WHERE id = :parcel
                """).param("stage", damaged ? "QUARANTINED" : "IN_STATION")
                .param("unit", employee.unitId()).param("phone", expected.phone()).param("street", expected.street())
                .param("community", expected.community()).param("address", expected.address()).param("area", areaId)
                .param("areaName", name).param("pending", pending == null ? null : pending.name())
                .param("now", now).param("parcel", parcel).update();
        long event = insertEvent(employee, batch, parcel, operation, now, areaId, pending, damaged);
        var receiptKey = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO inbound_receipt (batch_id, parcel_id, scan_event_id, received_by, received_at, matched_area_id)
                VALUES (:batch, :parcel, :event, :employee, :now, :area)
                """).param("batch", batch).param("parcel", parcel).param("event", event)
                .param("employee", employee.accountId()).param("now", now).param("area", areaId)
                .update(receiptKey, "id");
        long receipt = Objects.requireNonNull(receiptKey.getKey()).longValue();
        if (areaId != null) {
            insertSortCredit(employee, receipt, event, now);
        }
        String status = damaged ? "QUARANTINED" : areaId == null ? "PENDING_AREA" : "RECEIVED";
        return new Receipt(status, Long.toString(receipt), Long.toString(batch),
                waybill, BeijingTime.fromDatabase(now), Long.toString(employee.accountId()),
                areaId == null ? null : new ReceiptArea(areaId.toString(), name), pending, false);
    }

    private long insertEvent(EmployeePrincipal employee, long batch, long parcel, long operation, LocalDateTime now,
                             Long area, PendingReason pending, boolean damaged) {
        var details = json.createObjectNode();
        details.put("batchId", Long.toString(batch));
        if (area != null) {
            details.put("areaId", area.toString());
        } else if (pending != null) {
            details.put("pendingAreaReason", pending.name());
        }
        return writeEvent(employee, parcel, operation, now, damaged ? "INBOUND_QUARANTINED" : "INBOUND_RECEIVED", details);
    }

    long writeEvent(EmployeePrincipal employee, long parcel, long operation, LocalDateTime now,
                    String type, JsonNode details) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO parcel_event (parcel_id, event_type, actor_employee_id, actor_unit_id, operation_receipt_id,
                    occurred_at, details)
                VALUES (:parcel, :type, :actor, :unit, :operation, :now, :details)
                """).param("parcel", parcel).param("type", type).param("actor", employee.accountId()).param("unit", employee.unitId())
                .param("operation", operation).param("now", now).param("details", json.writeValueAsString(details))
                .update(key, "id");
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    void insertSortCredit(EmployeePrincipal employee, long receipt, long event, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO sort_credit (receipt_id, unit_id, employee_id, source_event_id, credited_at)
                VALUES (:receipt, :unit, :employee, :event, :now)
                """).param("receipt", receipt).param("unit", employee.unitId()).param("employee", employee.accountId())
                .param("event", event).param("now", now).update();
    }

    private Match matchAddress(long network, ExpectedAddress address) {
        if (address.street() == null || address.community() == null) {
            return new Match(null, PendingReason.MISSING_ADDRESS);
        }
        return matcher.orElseThrow(() -> new ApiProblems.Failure(HttpStatus.SERVICE_UNAVAILABLE,
                        "DEPENDENCY_UNAVAILABLE", "片区匹配服务尚未就绪，请稍后用原操作重试。"))
                .match(network, address.street(), address.community());
    }

    boolean isExpected(long parcel) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inbound_expected WHERE parcel_id = :parcel)")
                .param("parcel", parcel).query(Boolean.class).single();
    }

    private ApiProblems.Failure wrongBatch(long parcel) {
        return isExpected(parcel) ? batchMismatch() : unlisted();
    }

    static ApiProblems.Failure batchMismatch() {
        return new ApiProblems.Failure(HttpStatus.CONFLICT, "INBOUND_BATCH_MISMATCH",
                "此运单在本网点另一批次的应到清单，切换到对应批次后再扫。");
    }

    private static ApiProblems.Failure unlisted() {
        return new ApiProblems.Failure(HttpStatus.CONFLICT, "WAYBILL_NOT_EXPECTED",
                "此运单不在本网点应到清单，请核对上游明细。");
    }

    static void requireOperator(EmployeePrincipal employee) {
        InboundBatches.requireNetworkStaff(employee, false);
        if (!"NETWORK_OPERATOR".equals(employee.role())) {
            throw new SecurityException("This inbound operation requires a network operator");
        }
    }

    static void requireOperationKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._~-]{1,64}")) {
            throw InboundBatches.invalid("请提供有效的操作标识。");
        }
    }

    private record ExpectedAddress(String phone, String street, String community, String address) {}
    record LockedParcel(long id, String stage) {}
    record StoredOperation(long id, byte[] hash, String result) {}
    record Operation(int status, Receipt result) {}
    record ReceiptArea(String id, String name) {}
    record Receipt(String status, String receiptId, String batchId, String waybillNo, OffsetDateTime receivedAt,
                   String receivedBy, ReceiptArea area, PendingReason pendingAreaReason, boolean voided) {
        Receipt asDuplicate() {
            if ("QUARANTINED".equals(status)) return this;
            return new Receipt("ALREADY_RECEIVED", receiptId, batchId, waybillNo, receivedAt,
                    receivedBy, area, pendingAreaReason, voided);
        }
    }
}
