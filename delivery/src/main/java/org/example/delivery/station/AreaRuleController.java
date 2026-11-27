package org.example.delivery.station;

import java.net.URI;

import org.example.delivery.identity.EmployeePrincipal;
import org.example.delivery.station.AreaRules.Rule;
import org.example.delivery.station.StationQueries.Page;
import org.example.delivery.station.StationQueries.StaffContext;
import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AreaRuleController.PATH)
class AreaRuleController {
    static final String PATH = "/api/v1/staff/area-rules";
    private final AreaRules rules;

    AreaRuleController(AreaRules rules) {
        this.rules = rules;
    }

    @GetMapping
    Page<Rule> list(@AuthenticationPrincipal EmployeePrincipal employee,
                    @RequestParam(defaultValue = "1") int page,
                    @RequestParam(defaultValue = "20") int pageSize) {
        StationQueryController.requireValidPage(page, pageSize);
        return rules.list(context(employee), page, pageSize);
    }

    @GetMapping("/{id}")
    Rule get(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id) {
        return rules.get(context(employee), id);
    }

    @PostMapping
    ResponseEntity<Rule> create(@AuthenticationPrincipal EmployeePrincipal employee, @RequestBody CreateRule input) {
        Rule rule = rules.create(context(employee), employee.accountId(), input.street(), input.community(),
                areaId(input.areaId()));
        return ResponseEntity.created(URI.create(PATH + "/" + rule.id())).body(rule);
    }

    @PatchMapping("/{id}")
    Rule change(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id, @RequestBody ChangeRule input) {
        if (input.active() == null) {
            throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请选择规则的启用状态。");
        }
        return rules.change(context(employee), employee.accountId(), id, areaId(input.areaId()), input.active());
    }

    private static long areaId(String id) {
        try {
            long areaId = Long.parseLong(id);
            if (areaId > 0) return areaId;
        } catch (NumberFormatException invalid) {
            // Report malformed and nonpositive IDs with the same client error.
        }
        throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请选择有效的片区标识。");
    }

    private static StaffContext context(EmployeePrincipal employee) {
        return new StaffContext(employee.unitId(), employee.role());
    }

    record CreateRule(String street, String community, String areaId) {}
    record ChangeRule(String areaId, Boolean active) {}
}
