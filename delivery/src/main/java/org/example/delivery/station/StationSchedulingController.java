package org.example.delivery.station;

import java.time.Instant;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.StationQueries.Page;
import org.example.delivery.station.StationQueries.ShiftMember;
import org.example.delivery.station.StationQueries.StaffContext;
import org.example.delivery.station.StationQueries.Worker;
import org.example.delivery.station.StationScheduling.DutyStatus;
import org.example.delivery.station.StationScheduling.ScheduleConflictException;
import org.example.delivery.station.StationScheduling.ShiftVersionConflictException;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
class StationSchedulingController {
    private final StationQueries queries;
    private final StationScheduling scheduling;

    StationSchedulingController(StationQueries queries, StationScheduling scheduling) {
        this.queries = queries;
        this.scheduling = scheduling;
    }

    @GetMapping("/shift-couriers")
    Page<Worker> couriers(@AuthenticationPrincipal EmployeePrincipal employee,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "20") int pageSize) {
        StationQueryController.requireValidPage(page, pageSize);
        return queries.couriersForScheduling(context(employee), page, pageSize);
    }

    @GetMapping("/shifts")
    Page<ShiftMember> shifts(@AuthenticationPrincipal EmployeePrincipal employee, @RequestParam Instant at,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int pageSize) {
        StationQueryController.requireValidPage(page, pageSize);
        return queries.shiftMembersAt(context(employee), at, page, pageSize);
    }

    @PostMapping("/shifts")
    Scheduled schedule(@AuthenticationPrincipal EmployeePrincipal employee, @RequestBody ScheduleInput input) {
        try {
            long id = scheduling.schedule(context(employee), employee.accountId(), parseId(input.workerId()),
                    input.code(), input.startsAt(), input.endsAt());
            return new Scheduled(Long.toString(id));
        } catch (ScheduleConflictException conflict) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "SHIFT_CONFLICT", "此人已有重叠班次，或同名班次的时间不一致。");
        } catch (IllegalArgumentException invalid) {
            throw invalidSchedulingRequest();
        }
    }

    @PostMapping("/shifts/{shiftId}/members/{workerId}/duty-status")
    Updated updateDutyStatus(@AuthenticationPrincipal EmployeePrincipal employee,
                             @PathVariable long shiftId, @PathVariable long workerId, @RequestBody StatusInput input) {
        try {
            long version = scheduling.setDutyStatus(context(employee), employee.accountId(), workerId, shiftId,
                    input.status(), parseUnsigned(input.expectedVersion()));
            return new Updated(Long.toString(version));
        } catch (ShiftVersionConflictException conflict) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "SHIFT_VERSION_CONFLICT", "班次状态已被修改，请核对最新名单后再提交。");
        } catch (IllegalArgumentException invalid) {
            throw invalidSchedulingRequest();
        }
    }

    private static ApiProblems.Failure invalidSchedulingRequest() {
        return new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请检查人员、班次和提交参数。");
    }

    private static StaffContext context(EmployeePrincipal employee) {
        return new StaffContext(employee.unitId(), employee.role());
    }

    private static long parseId(String value) {
        long id = parseUnsigned(value);
        if (id == 0) throw new IllegalArgumentException();
        return id;
    }

    private static long parseUnsigned(String value) {
        if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }

    record ScheduleInput(String workerId, String code, Instant startsAt, Instant endsAt) {}
    record StatusInput(DutyStatus status, String expectedVersion) {}
    record Scheduled(String shiftId) {}
    record Updated(String version) {}
}
