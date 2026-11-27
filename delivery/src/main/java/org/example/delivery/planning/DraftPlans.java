package org.example.delivery.planning;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    private Prepared prepare(EmployeePrincipal employee, long batchId, long shiftId, OffsetDateTime at) {
        var parcels = receipts.confirmedHomeReceipts(employee, batchId);
        var scope = new StaffContext(employee.unitId(), employee.role());
        var instant = at.toInstant();
        var members = readAll(page -> station.shiftMembersAt(scope, instant, page, 100)).stream()
                .filter(member -> member.shiftId().equals(Long.toString(shiftId))).toList();
        if (members.isEmpty()) {
            throw new ApiProblems.Failure(HttpStatus.NOT_FOUND, "PLANNING_SHIFT_NOT_FOUND",
                    "在该北京时间查不到本网点所选班次。");
        }
        var shift = new DraftGenerator.Shift(members.getFirst().startsAt().toInstant(),
                members.getFirst().endsAt().toInstant(), members.stream().map(member ->
                        new DraftGenerator.ShiftMember(member.workerId(), DraftGenerator.DutyStatus.valueOf(member.status())))
                        .toList());
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

    private static <T> List<T> readAll(IntFunction<Page<T>> lookup) {
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
        if (input != null && input.isTextual()) {
            String value = input.asText();
            try {
                long id = Long.parseLong(value);
                if (id > 0 && Long.toString(id).equals(value)) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // Return the same input error for malformed and out-of-range identifiers.
            }
        }
        throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "批次及班次须为有效标识。");
    }

    record CreateDraft(JsonNode batchId, JsonNode shiftId, OffsetDateTime at) {}
    record Prepared(long batchId, long shiftId, OffsetDateTime evaluatedAt, List<Line> lines) {}
    record Line(ReceiptSnapshot receipt, DraftGenerator.Area area, DraftGenerator.Item item) {}
}
