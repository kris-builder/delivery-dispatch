package org.example.delivery.planning;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.example.delivery.fulfillment.PlanningDemoReceipts;
import org.example.delivery.fulfillment.PlanningDemoReceipts.ReceiptSnapshot;
import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.StationQueries;
import org.example.delivery.station.StationQueries.Page;
import org.example.delivery.station.StationQueries.StaffContext;
import org.example.delivery.time.BeijingTime;
import org.example.delivery.web.ApiProblems;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
@Profile("planning-demo")
class DraftPlans {
    private final StationQueries station;
    private final PlanningDemoReceipts receipts;
    private final DraftPlanStore store;
    private final TransactionTemplate readSnapshot;

    DraftPlans(StationQueries station, PlanningDemoReceipts receipts, DraftPlanStore store,
               PlatformTransactionManager transactions) {
        this.station = station;
        this.receipts = receipts;
        this.store = store;
        this.readSnapshot = new TransactionTemplate(transactions);
        this.readSnapshot.setReadOnly(true);
        this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    DraftPlanStore.Detail create(EmployeePrincipal employee, CreateDraft input) {
        requireDispatcher(employee);
        long batchId = positiveId(input.batchId());
        long shiftId = positiveId(input.shiftId());
        if (input.at() == null) {
            throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请选择班次查询时间。");
        }
        var prepared = readSnapshot.execute(status -> prepare(employee, batchId, shiftId, input.at()));
        return store.save(employee, prepared);
    }

    DraftPlanStore.Detail detail(EmployeePrincipal employee, long id) {
        requireDispatcher(employee);
        return store.detail(employee.unitId(), id);
    }

    DraftPlanStore.Detail adjust(EmployeePrincipal employee, long id, AdjustDraft input) {
        requireDispatcher(employee);
        long version = decimal(input.expectedVersion(), true, "草案版本");
        boolean hold = input.action() != null && "HOLD".equals(input.action().asText());
        if (input.action() != null && !hold) {
            throw invalid("草案调整动作无效。");
        }
        if (hold && input.workerId() != null && !input.workerId().isNull()) {
            throw invalid("留站时无需指定配送员。");
        }
        Long workerId = hold ? null : decimal(input.workerId(), false, "目标配送员");
        if (input.parcelIds() == null || input.parcelIds().isEmpty()) {
            throw invalid("请选择需要调整的运单。");
        }
        var selected = input.parcelIds().stream().map(parcel -> decimal(parcel, false, "包裹标识")).toList();
        if (new HashSet<>(selected).size() != selected.size()) {
            throw invalid("同一包裹只能选择一次。");
        }
        if (input.reason() == null || !input.reason().isTextual()) {
            throw invalid("请填写调整原因。");
        }
        String reason = input.reason().asText().strip();
        if (reason.isBlank() || reason.length() > 500) {
            throw invalid("调整原因须为 1 到 500 个字符。");
        }
        return store.adjust(employee, id, version, selected, workerId, reason);
    }

    Page<StationQueries.Worker> supportWorkers(EmployeePrincipal employee, long id, int page, int pageSize) {
        requireDispatcher(employee);
        validatePage(page, pageSize);
        var draft = store.detail(employee.unitId(), id);
        return station.onDutyWorkersForShift(new StaffContext(employee.unitId(), employee.role()),
                Long.parseLong(draft.shiftId()), draft.evaluatedAt().toInstant(), page, pageSize);
    }

    Page<StationQueries.Shift> eligibleShifts(EmployeePrincipal employee, Instant at, int page, int pageSize) {
        requireDispatcher(employee);
        validatePage(page, pageSize);
        return station.shiftsAt(new StaffContext(employee.unitId(), employee.role()), at, page, pageSize);
    }

    private Prepared prepare(EmployeePrincipal employee, long batchId, long shiftId, OffsetDateTime at) {
        var parcels = receipts.confirmedHomeReceipts(employee, batchId);
        var scope = new StaffContext(employee.unitId(), employee.role());
        var instant = at.toInstant();
        var selectedShift = station.shiftAt(scope, shiftId, instant)
                .orElseThrow(() -> new ApiProblems.Failure(HttpStatus.NOT_FOUND, "PLANNING_SHIFT_NOT_FOUND",
                        "在该北京时间查不到本网点所选班次。"));
        var available = readAll(page -> station.onDutyWorkersForShift(scope, shiftId, instant, page, 100));
        var shift = new DraftGenerator.Shift(selectedShift.startsAt().toInstant(),
                selectedShift.endsAt().toInstant(), available.stream().map(worker ->
                        new DraftGenerator.ShiftMember(worker.id(), DraftGenerator.DutyStatus.ON_DUTY)).toList());
        var areas = readAll(page -> station.areas(scope, page, 100)).stream().collect(Collectors.toMap(
                StationQueries.Area::id, area -> new DraftGenerator.Area(area.id(), area.name(),
                        area.regularWorker() == null ? null : new DraftGenerator.Worker(
                                area.regularWorker().id(), area.regularWorker().displayName()))));
        var home = new DraftGenerator.Agreement(DraftGenerator.Method.HOME, null, null);
        var draft = new DraftGenerator().generate(areas, parcels.stream().map(parcel ->
                new DraftGenerator.Parcel(parcel.waybillNo(), DraftGenerator.State.AVAILABLE, parcel.addressAreaId(), home))
                .toList(), shift, instant);
        if (draft.total() == 0) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "NO_DRAFT_ITEMS", "该批次暂无已实收的草案样例件。");
        }
        var byWaybill = parcels.stream().collect(Collectors.toMap(ReceiptSnapshot::waybillNo, Function.identity()));
        var lines = draft.groups().stream().flatMap(group -> group.items().stream().map(item ->
                new Line(byWaybill.get(item.waybillNo()), group.area(), item))).toList();
        return new Prepared(batchId, shiftId, BeijingTime.fromDatabase(BeijingTime.forDatabase(instant)), lines);
    }

    static <T> List<T> readAll(IntFunction<Page<T>> lookup) {
        List<T> items = new ArrayList<>();
        int pageNumber = 1;
        Page<T> page;
        do {
            page = lookup.apply(pageNumber++);
            items.addAll(page.items());
        } while (items.size() < page.total());
        return items;
    }

    private static void requireDispatcher(EmployeePrincipal employee) {
        if (employee == null || !"NETWORK_DISPATCHER".equals(employee.role())
                || employee.unitId() != employee.networkId()) {
            throw new SecurityException("Drafts require a network dispatcher");
        }
    }

    private static long positiveId(JsonNode input) {
        return decimal(input, false, "批次及班次");
    }

    private static long decimal(JsonNode input, boolean allowZero, String field) {
        if (input != null && input.isTextual()) {
            String value = input.asText();
            try {
                long id = Long.parseLong(value);
                if (id >= (allowZero ? 0 : 1) && Long.toString(id).equals(value)) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // Return the same input error for malformed and out-of-range identifiers.
            }
        }
        throw invalid("请提交有效的" + field + "。");
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("页码至少为 1，每页条数须在 1 到 100 之间。");
        }
    }

    private static ApiProblems.Failure invalid(String detail) {
        return new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
    }

    record AdjustDraft(JsonNode action, JsonNode expectedVersion, List<JsonNode> parcelIds, JsonNode workerId,
                       JsonNode reason) {}
    record CreateDraft(JsonNode batchId, JsonNode shiftId, OffsetDateTime at) {}
    record Prepared(long batchId, long shiftId, OffsetDateTime evaluatedAt, List<Line> lines) {}
    record Line(ReceiptSnapshot receipt, DraftGenerator.Area area, DraftGenerator.Item item) {}
}
