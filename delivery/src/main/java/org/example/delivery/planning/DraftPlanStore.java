package org.example.delivery.planning;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.time.BeijingTime;
import org.example.delivery.web.ApiProblems;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("planning-demo")
class DraftPlanStore {
    private final JdbcClient jdbc;

    DraftPlanStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Detail save(EmployeePrincipal employee, DraftPlans.Prepared prepared) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO delivery_plan (network_id, shift_id, inbound_batch_id, created_by, created_at, evaluated_at)
                VALUES (:network, :shift, :batch, :creator, :createdAt, :evaluatedAt)
                """).param("network", employee.unitId()).param("shift", prepared.shiftId())
                .param("batch", prepared.batchId()).param("creator", employee.accountId())
                .param("createdAt", BeijingTime.now()).param("evaluatedAt", prepared.evaluatedAt().toLocalDateTime())
                .update(key, "id");
        long id = key.getKey().longValue();
        for (var line : prepared.lines()) {
            writeItem(id, employee.unitId(), prepared.batchId(), line);
        }
        return detail(employee.unitId(), id);
    }

    private void writeItem(long plan, long network, long batch, DraftPlans.Line line) {
        var worker = line.item().proposedWorker();
        var area = line.area();
        var reason = line.item().pendingReason();
        int inserted = jdbc.sql("""
                INSERT INTO plan_item (plan_id, parcel_id, target_area_id, target_area_name,
                                      proposed_worker_id, proposed_worker_name, parcel_version, reason_code)
                SELECT :plan, p.id, :areaId, :areaName, :workerId, :workerName, p.version, :reason
                FROM parcel p JOIN inbound_receipt r ON r.parcel_id = p.id
                WHERE p.id = :parcel AND p.version = :version AND p.network_id = :unit
                  AND r.batch_id = :batch AND r.voided_at IS NULL
                  AND p.stage = 'IN_STATION' AND p.holder_kind = 'NETWORK' AND p.holder_unit_id = :unit
                """).param("plan", plan).param("parcel", line.receipt().parcelId())
                .param("version", line.receipt().version()).param("unit", network).param("batch", batch)
                .param("areaId", area == null ? null : Long.valueOf(area.id()))
                .param("areaName", area == null ? null : area.name())
                .param("workerId", worker == null ? null : Long.valueOf(worker.id()))
                .param("workerName", worker == null ? null : worker.displayName())
                .param("reason", reason == null ? null : reason.name()).update();
        if (inserted != 1) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "DRAFT_PARCEL_CHANGED",
                    "运单 " + line.receipt().waybillNo() + " 的在站状态或版本已变化，请重新生成。");
        }
    }

    @Transactional(readOnly = true)
    public Detail detail(long network, long id) {
        Header header = jdbc.sql("""
                SELECT d.id, d.inbound_batch_id, b.source_batch_no, d.shift_id, d.created_by, d.created_at,
                       d.evaluated_at, d.status, d.version
                FROM delivery_plan d JOIN inbound_batch b ON b.id = d.inbound_batch_id
                WHERE d.id = :id AND d.network_id = :network
                """).param("id", id).param("network", network).query((row, index) -> new Header(
                row.getString("id"), row.getString("inbound_batch_id"), row.getString("source_batch_no"),
                row.getString("shift_id"), row.getString("created_by"),
                BeijingTime.fromDatabase(row.getObject("created_at", LocalDateTime.class)),
                BeijingTime.fromDatabase(row.getObject("evaluated_at", LocalDateTime.class)),
                row.getString("status"), row.getString("version"))).optional()
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "DELIVERY_PLAN_NOT_FOUND",
                        "本网点查不到该配送草案。"));

        var rows = jdbc.sql("""
                SELECT i.parcel_id, i.parcel_version, p.waybill_no, i.target_area_id, i.target_area_name,
                       i.proposed_worker_id, i.proposed_worker_name, i.reason_code
                FROM plan_item i JOIN parcel p ON p.id = i.parcel_id
                WHERE i.plan_id = :id ORDER BY i.target_area_id IS NULL, i.target_area_id, i.parcel_id
                """).param("id", id).query((row, index) -> new DetailRow(
                new AreaKey(row.getString("target_area_id"), row.getString("target_area_name")),
                new Item(row.getString("parcel_id"), row.getString("waybill_no"), row.getString("parcel_version"),
                        row.getString("proposed_worker_id"), row.getString("proposed_worker_name"),
                        row.getString("reason_code")))).list();

        Map<AreaKey, List<Item>> byArea = new LinkedHashMap<>();
        for (var row : rows) {
            byArea.computeIfAbsent(row.area(), unused -> new ArrayList<>()).add(row.item());
        }
        var groups = byArea.entrySet().stream().map(entry -> Group.of(entry.getKey(), entry.getValue())).toList();
        int assigned = groups.stream().mapToInt(Group::assigned).sum();
        return new Detail(header.id(), header.batchId(), header.batchNumber(), header.shiftId(), header.createdBy(),
                header.createdAt(), header.evaluatedAt(), header.status(), header.version(),
                rows.size(), assigned, rows.size() - assigned, groups);
    }

    private record Header(String id, String batchId, String batchNumber, String shiftId, String createdBy,
                          OffsetDateTime createdAt, OffsetDateTime evaluatedAt, String status, String version) {}
    private record AreaKey(String id, String name) {}
    private record DetailRow(AreaKey area, Item item) {}

    record Item(String parcelId, String waybillNo, String parcelVersion, String proposedWorkerId,
                String proposedWorkerName, String pendingReason) {}
    record Group(String areaId, String areaName, int total, int assigned, int pending, List<Item> items) {
        static Group of(AreaKey area, List<Item> items) {
            int assigned = (int) items.stream().filter(item -> item.proposedWorkerId() != null).count();
            return new Group(area.id(), area.name(), items.size(), assigned, items.size() - assigned, List.copyOf(items));
        }
    }
    record Detail(String id, String batchId, String batchNumber, String shiftId, String createdBy, OffsetDateTime createdAt,
                  OffsetDateTime evaluatedAt, String status, String version, int total, int assigned, int pending,
                  List<Group> groups) {}
}
