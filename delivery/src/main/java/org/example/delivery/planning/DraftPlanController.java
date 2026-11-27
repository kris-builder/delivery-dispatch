package org.example.delivery.planning;

import java.net.URI;
import java.time.Instant;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.StationQueries;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(DraftPlanController.PATH)
@Profile("planning-demo")
class DraftPlanController {
    static final String PATH = "/api/v1/staff/delivery-plans";
    private final DraftPlans drafts;

    DraftPlanController(DraftPlans drafts) {
        this.drafts = drafts;
    }

    @PostMapping
    ResponseEntity<DraftPlanStore.Detail> create(@AuthenticationPrincipal EmployeePrincipal employee,
                                               @RequestBody DraftPlans.CreateDraft input) {
        var result = drafts.create(employee, input);
        return ResponseEntity.created(URI.create(PATH + "/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    DraftPlanStore.Detail detail(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id) {
        return drafts.detail(employee, id);
    }

    @GetMapping("/eligible-shifts")
    StationQueries.Page<StationQueries.Shift> eligibleShifts(@AuthenticationPrincipal EmployeePrincipal employee,
                                                          @RequestParam Instant at,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int pageSize) {
        return drafts.eligibleShifts(employee, at, page, pageSize);
    }
}
