package org.example.delivery.station;

import java.time.Instant;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.StationQueries.Area;
import org.example.delivery.station.StationQueries.Page;
import org.example.delivery.station.StationQueries.StaffContext;
import org.example.delivery.station.StationQueries.Worker;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
class StationQueryController {
    private final StationQueries station;

    StationQueryController(StationQueries station) {
        this.station = station;
    }

    @GetMapping("/areas")
    Page<Area> areas(@AuthenticationPrincipal EmployeePrincipal employee,
                     @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        requireValidPage(page, pageSize);
        return station.areas(new StaffContext(employee.unitId(), employee.role()), page, pageSize);
    }

    @GetMapping("/workers")
    Page<Worker> workers(@AuthenticationPrincipal EmployeePrincipal employee, @RequestParam Instant at,
                         @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        requireValidPage(page, pageSize);
        return station.onDutyWorkers(new StaffContext(employee.unitId(), employee.role()), at, page, pageSize);
    }

    static void requireValidPage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "页码至少为 1，每页条数须在 1 到 100 之间。");
        }
    }
}
