package org.example.delivery.planning;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.StationQueries;
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
    private static final String HOLD_REASON = "DISPATCHER_HOLD";

    private final JdbcClient jdbc;
    private final StationQueries station;

    DraftPlanStore(JdbcClient jdbc, StationQueries station) {
        this.jdbc = jdbc;
        this.station = station;
    }

    @Transactional
    public Detail adjust(EmployeePrincipal employee, long id, long expectedVersion, List<Long> selected,
                         Long workerId, String reason) {
        long network = employee.unitId();
        var draft = jdbc.sql("""
                SELECT shift_id, inbound_batch_id, evaluated_at, status, version
                FROM delivery_plan WHERE id = :id AND network_id = :network FOR UPDATE
                """).param("id", id).param("network", network).query((row, index) -> new LockedDraft(
                row.getLong("shift_id"), row.getLong("inbound_batch_id"),
                BeijingTime.fromDatabase(row.getObject("evaluated_at", LocalDateTime.class)),
                row.getString("status"), row.getLong("version"))).optional().orElseThrow(DraftPlanStore::notFound);
        if (draft.version() != expectedVersion || !"DRAFT".equals(draft.status())) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "DRAFT_VERSION_CONFLICT", "草案已变化，请重新打开后核对。");
        }
        var scope = new StationQueries.StaffContext(network, employee.role());
        var worker = workerId == null ? null : DraftPlans.readAll(page ->
                station.onDutyWorkersForShift(scope, draft.shiftId(), draft.evaluatedAt().toInstant(), page, 100))
                .stream().filter(candidate -> candidate.id().equals(Long.toString(workerId))).findFirst()
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.CONFLICT, "DRAFT_WORKER_UNAVAILABLE",
                        "目标配送员不在本网点所选班次的在岗名单中。"));

        var items = jdbc.sql("""
                SELECT i.parcel_id, i.proposed_worker_id, i.reason_code
                FROM plan_item i
                JOIN parcel p ON p.id = i.parcel_id AND p.version = i.parcel_version
                JOIN inbound_receipt r ON r.parcel_id = p.id AND r.batch_id = :batch AND r.voided_at IS NULL
                JOIN delivery_area a ON a.id = i.target_area_id AND a.network_id = :network AND a.active
                WHERE i.plan_id = :id AND i.parcel_id IN (:selected)
                  AND p.network_id = :network AND p.stage = 'IN_STATION'
                  AND p.holder_kind = 'NETWORK' AND p.holder_unit_id = :network
                ORDER BY i.parcel_id FOR UPDATE OF i, p, r
                """).param("batch", draft.batchId()).param("network", network)
                .param("id", id).param("selected", selected).query((row, index) -> new SelectedItem(
                row.getLong("parcel_id"), row.getObject("proposed_worker_id", Long.class),
                row.getString("reason_code"))).list();
        if (items.size() != selected.size()) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "DRAFT_PARCEL_CHANGED",
                    "所选运单包含当前不可安排的件，请刷新后核对。");
        }

        var changes = items.stream().filter(item -> worker == null
                ? !HOLD_REASON.equals(item.reasonCode())
                : !Objects.equals(item.oldWorkerId(), workerId)).toList();
        if (changes.isEmpty()) {
            return detail(network, id);
        }

        long version = draft.version() + 1;
        LocalDateTime changedAt = BeijingTime.now();
        for (var item : changes) {
            jdbc.sql("""
                    UPDATE plan_item SET proposed_worker_id = :worker, proposed_worker_name = :name, reason_code = :code
                    WHERE plan_id = :plan AND parcel_id = :parcel
                    """).param("worker", workerId).param("name", worker == null ? null : worker.displayName())
                    .param("code", worker == null ? HOLD_REASON : null)
                    .param("plan", id).param("parcel", item.parcelId()).update();
            jdbc.sql("""
                    INSERT INTO plan_adjustment (plan_id, parcel_id, plan_version, old_worker_id, new_worker_id,
                                                 reason, changed_by, changed_at)
                    VALUES (:plan, :parcel, :version, :oldWorker, :worker, :reason, :actor, :changedAt)
                    """).param("plan", id).param("parcel", item.parcelId()).param("version", version)
                    .param("oldWorker", item.oldWorkerId()).param("worker", workerId).param("reason", reason)
                    .param("actor", employee.accountId()).param("changedAt", changedAt).update();
        }
        jdbc.sql("UPDATE delivery_plan SET version = :version WHERE id = :id")
                .param("version", version).param("id", id).update();
        return detail(network, id);
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
                .orElseThrow(DraftPlanStore::notFound);

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
        var adjustments = jdbc.sql("""
                SELECT a.parcel_id, p.waybill_no, a.plan_version, a.old_worker_id, old_worker.display_name AS old_name,
                       a.new_worker_id, new_worker.display_name AS new_name, a.reason, a.changed_by,
                       e.login_name, a.changed_at
                FROM plan_adjustment a
                JOIN parcel p ON p.id = a.parcel_id
                JOIN employee_account e ON e.id = a.changed_by
                LEFT JOIN worker_profile old_worker ON old_worker.id = a.old_worker_id
                LEFT JOIN worker_profile new_worker ON new_worker.id = a.new_worker_id
                WHERE a.plan_id = :id ORDER BY a.plan_version DESC, a.parcel_id
                """).param("id", id).query((row, index) -> new Adjustment(
                row.getString("parcel_id"), row.getString("waybill_no"), row.getString("plan_version"),
                row.getString("old_worker_id"), row.getString("old_name"), row.getString("new_worker_id"),
                row.getString("new_name"), row.getString("reason"), row.getString("changed_by"),
                row.getString("login_name"), BeijingTime.fromDatabase(row.getObject("changed_at", LocalDateTime.class))))
                .list();
        return new Detail(header.id(), header.batchId(), header.batchNumber(), header.shiftId(), header.createdBy(),
                header.createdAt(), header.evaluatedAt(), header.status(), header.version(),
                rows.size(), assigned, rows.size() - assigned, groups, adjustments);
    }

    private static ApiProblems.Failure notFound() {
        return new ApiProblems.Failure(HttpStatus.NOT_FOUND, "DELIVERY_PLAN_NOT_FOUND", "本网点查不到该配送草案。");
    }

    private record LockedDraft(long shiftId, long batchId, OffsetDateTime evaluatedAt, String status, long version) {}
    private record SelectedItem(long parcelId, Long oldWorkerId, String reasonCode) {}
    private record Header(String id, String batchId, String batchNumber, String shiftId, String createdBy,
                          OffsetDateTime createdAt, OffsetDateTime evaluatedAt, String status, String version) {}
    private record AreaKey(String id, String name) {}
    private record DetailRow(AreaKey area, Item item) {}

    record Item(String parcelId, String waybillNo, String parcelVersion, String proposedWorkerId,
                String proposedWorkerName, String pendingReason) {}
    record Adjustment(String parcelId, String waybillNo, String planVersion, String oldWorkerId, String oldWorkerName,
                      String newWorkerId, String newWorkerName, String reason, String changedBy, String changedByName,
                      OffsetDateTime changedAt) {}
    record Group(String areaId, String areaName, int total, int assigned, int pending, List<Item> items) {
        static Group of(AreaKey area, List<Item> items) {
            int assigned = (int) items.stream().filter(item -> item.proposedWorkerId() != null).count();
            return new Group(area.id(), area.name(), items.size(), assigned, items.size() - assigned, List.copyOf(items));
        }
    }
    record Detail(String id, String batchId, String batchNumber, String shiftId, String createdBy, OffsetDateTime createdAt,
                  OffsetDateTime evaluatedAt, String status, String version, int total, int assigned, int pending,
                  List<Group> groups, List<Adjustment> adjustments) {}
}
