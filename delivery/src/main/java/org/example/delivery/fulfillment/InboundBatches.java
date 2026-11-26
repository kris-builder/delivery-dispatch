package org.example.delivery.fulfillment;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class InboundBatches {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    InboundBatches(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    ImportResult importManifest(EmployeePrincipal employee, Manifest manifest) {
        requireNetworkStaff(employee, true);
        String target = requiredText(manifest.targetNetworkCode(), 64, "目标网点");
        String sourceNo = requiredText(manifest.sourceBatchNo(), 64, "来源批次号");
        requireTargetNetwork(employee.unitId(), target);
        List<SourceParcel> parcels = validateParcels(manifest.parcels());
        byte[] fingerprint = hash(json.writeValueAsBytes(parcels));
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        jdbc.sql("""
                INSERT INTO inbound_batch (network_id, source_system, source_batch_no, source_fingerprint, imported_by, imported_at)
                VALUES (:unit, 'SYNTHETIC', :source, :fingerprint, :employee, :now)
                ON DUPLICATE KEY UPDATE id = inbound_batch.id
                """).param("unit", employee.unitId()).param("source", sourceNo).param("fingerprint", fingerprint)
                .param("employee", employee.accountId()).param("now", now).update();
        var batch = jdbc.sql("""
                SELECT id, source_fingerprint FROM inbound_batch
                WHERE network_id = :unit AND source_system = 'SYNTHETIC' AND source_batch_no = :source
                FOR UPDATE
                """).param("unit", employee.unitId()).param("source", sourceNo)
                .query((row, index) -> new StoredManifest(row.getLong("id"), row.getBytes("source_fingerprint"))).single();
        if (!Arrays.equals(fingerprint, batch.fingerprint())) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "INBOUND_MANIFEST_CONFLICT",
                    "同一来源批次已有不同的应到清单，请核对上游明细。");
        }
        boolean alreadyImported = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM inbound_expected WHERE batch_id = :batch)
                """).param("batch", batch.id()).query(Boolean.class).single();
        if (!alreadyImported) {
            for (SourceParcel parcel : parcels) {
                insertExpected(employee.unitId(), batch.id(), parcel, now);
            }
        }
        return new ImportResult(detail(employee, batch.id()), !alreadyImported);
    }

    @Transactional(readOnly = true)
    public List<BatchSummary> list(EmployeePrincipal employee) {
        requireNetworkStaff(employee, false);
        return jdbc.sql("""
                SELECT b.id, b.source_batch_no,
                       (SELECT COUNT(*) FROM inbound_expected e WHERE e.batch_id = b.id) AS expected_count,
                       (SELECT COUNT(*) FROM inbound_receipt r WHERE r.batch_id = b.id AND r.voided_at IS NULL) AS received_count
                FROM inbound_batch b WHERE b.network_id = :unit ORDER BY b.id DESC
                """).param("unit", employee.unitId())
                .query((row, index) -> new BatchSummary(row.getString("id"), row.getString("source_batch_no"),
                        row.getLong("expected_count"), row.getLong("received_count"))).list();
    }

    @Transactional(readOnly = true)
    public BatchDetail detail(EmployeePrincipal employee, long batchId) {
        requireNetworkStaff(employee, false);
        String source = requireBatch(employee.unitId(), batchId);
        var lines = jdbc.sql("""
                SELECT p.waybill_no, e.street, e.community
                FROM inbound_expected e JOIN parcel p ON p.id = e.parcel_id
                WHERE e.batch_id = :batch ORDER BY p.waybill_no
                """).param("batch", batchId).query((row, index) -> new ExpectedLine(row.getString("waybill_no"),
                row.getString("street"), row.getString("community"))).list();
        var counts = jdbc.sql("""
                SELECT COUNT(*) AS received_count, COUNT(p.address_area_id) AS assigned_count
                FROM inbound_receipt r JOIN parcel p ON p.id = r.parcel_id
                WHERE r.batch_id = :batch AND r.voided_at IS NULL
                """).param("batch", batchId)
                .query((row, index) -> new ReceiptCounts(row.getLong("received_count"), row.getLong("assigned_count")))
                .single();
        return new BatchDetail(Long.toString(batchId), source, lines.size(), counts.received(), counts.assigned(),
                counts.received() - counts.assigned(), lines);
    }

    String requireBatch(long unit, long batchId) {
        return jdbc.sql("SELECT source_batch_no FROM inbound_batch WHERE id = :batch AND network_id = :unit")
                .param("batch", batchId).param("unit", unit).query(String.class).optional()
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "INBOUND_BATCH_NOT_FOUND",
                        "本网点查不到该应到批次。"));
    }

    static void requireNetworkStaff(EmployeePrincipal employee, boolean dispatcherOnly) {
        boolean operatorAllowed = !dispatcherOnly && employee != null && "NETWORK_OPERATOR".equals(employee.role());
        if (employee == null || employee.unitId() != employee.networkId()
                || !("NETWORK_DISPATCHER".equals(employee.role()) || operatorAllowed)) {
            throw new SecurityException("Inbound batches require an authorized network employee");
        }
    }

    static String requiredText(JsonNode input, int maxLength, String label) {
        if (input == null || !input.isTextual()) {
            throw invalid(label + "须为非空字符串。");
        }
        return requiredText(input.asText(), maxLength, label);
    }

    static String requiredText(String value, int maxLength, String label) {
        if (value.isBlank() || value.length() > maxLength || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(label + "长度或格式无效。");
        }
        return value;
    }

    static ApiProblems.Failure invalid(String detail) {
        return new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
    }

    static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private void requireTargetNetwork(long unit, String networkCode) {
        boolean valid = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM org_unit WHERE id = :unit AND code = :code AND kind = 'NETWORK' AND active)
                """).param("unit", unit).param("code", networkCode).query(Boolean.class).single();
        if (!valid) {
            throw new SecurityException("The target network must match the employee unit");
        }
    }

    private void insertExpected(long unit, long batch, SourceParcel parcel, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO parcel (network_id, waybill_no, recipient_phone, street, community, address, created_at, updated_at)
                VALUES (:unit, :waybill, :phone, :street, :community, :address, :now, :now)
                ON DUPLICATE KEY UPDATE id = parcel.id
                """).param("unit", unit).param("waybill", parcel.waybillNo()).param("phone", parcel.recipientPhone())
                .param("street", parcel.street()).param("community", parcel.community()).param("address", parcel.address())
                .param("now", now).update();
        jdbc.sql("""
                INSERT INTO inbound_expected (batch_id, parcel_id, recipient_phone, street, community, address, imported_at)
                SELECT :batch, id, :phone, :street, :community, :address, :now FROM parcel
                WHERE network_id = :unit AND waybill_no = :waybill
                """).param("batch", batch).param("unit", unit).param("waybill", parcel.waybillNo())
                .param("phone", parcel.recipientPhone()).param("street", parcel.street()).param("community", parcel.community())
                .param("address", parcel.address()).param("now", now).update();
    }

    private static List<SourceParcel> validateParcels(List<ManifestLine> lines) {
        if (lines == null || lines.isEmpty() || lines.stream().anyMatch(Objects::isNull)) {
            throw invalid("请填写应到运单明细。");
        }
        var waybills = new HashSet<String>();
        return lines.stream().map(line -> {
            String waybill = requiredText(line.waybillNo(), 64, "运单号");
            if (!waybills.add(waybill)) {
                throw invalid("同一清单含重复运单号，请核对上游明细。");
            }
            return new SourceParcel(waybill, optionalText(line.recipientPhone(), 20, "收件联系方式"),
                    optionalText(line.street(), 128, "街道"), optionalText(line.community(), 128, "小区"),
                    optionalText(line.address(), 255, "地址"));
        }).sorted(Comparator.comparing(SourceParcel::waybillNo)).toList();
    }

    private static String optionalText(JsonNode input, int maxLength, String label) {
        if (input == null || input.isNull() || (input.isTextual() && input.asText().isBlank())) {
            return null;
        }
        return requiredText(input, maxLength, label);
    }

    record Manifest(JsonNode targetNetworkCode, JsonNode sourceBatchNo, List<ManifestLine> parcels) {}
    record ManifestLine(JsonNode waybillNo, JsonNode recipientPhone, JsonNode street, JsonNode community, JsonNode address) {}
    private record SourceParcel(String waybillNo, String recipientPhone, String street, String community, String address) {}
    private record StoredManifest(long id, byte[] fingerprint) {}
    private record ReceiptCounts(long received, long assigned) {}
    record ExpectedLine(String waybillNo, String street, String community) {}
    record BatchSummary(String id, String sourceBatchNo, long expectedCount, long receivedCount) {}
    record BatchDetail(String id, String sourceBatchNo, long expectedCount, long receivedCount,
                       long assignedCount, long pendingAreaCount, List<ExpectedLine> expected) {}
    record ImportResult(BatchDetail batch, boolean created) {}
}
