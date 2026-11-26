package org.example.delivery.identity;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.delivery.web.ApiProblems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class EmployeeAuthController {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmployeeAuthController.class);

    private final EmployeeAccounts accounts;
    private final AuthenticationManager authentication;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrfTokens;
    private final int timeoutSeconds;

    EmployeeAuthController(EmployeeAccounts accounts, AuthenticationManager authentication,
                           HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrfTokens,
                           @Value("${delivery.employee.session-timeout:30m}") Duration timeout) {
        this.accounts = accounts;
        this.authentication = authentication;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
        this.timeoutSeconds = Math.toIntExact(timeout.toSeconds());
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Employee session timeout must be at least one second");
        }
    }

    @GetMapping("/auth/csrf")
    Csrf csrf(CsrfToken csrf) {
        return new Csrf(csrf.getHeaderName(), csrf.getToken());
    }

    @GetMapping("/auth/session")
    EmployeeSession current(@AuthenticationPrincipal EmployeePrincipal employee) {
        return EmployeeSession.from(employee);
    }

    @PostMapping("/employee-auth/login")
    EmployeeSession login(@RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        Authentication employee = authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(input.loginName(), input.password()));
        return establish(employee, request, response);
    }

    @PostMapping("/employee-auth/password-changes")
    EmployeeSession changePassword(@AuthenticationPrincipal EmployeePrincipal employee, @RequestBody PasswordChange input,
                                   HttpServletRequest request, HttpServletResponse response) {
        EmployeePrincipal updated = accounts.changeTemporaryPassword(employee, input.temporaryPassword(), input.newPassword());
        LOGGER.info("employee_password_changed actorEmployeeId={} targetEmployeeId={} requestId={}",
                updated.accountId(), updated.accountId(), response.getHeader(ApiProblems.REQUEST_ID));
        return establish(EmployeeSecurity.employeeAuthentication(updated), request, response);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(Authentication employee, HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, employee);
    }

    private EmployeeSession establish(Authentication employee, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession();
        request.changeSessionId();
        session.setMaxInactiveInterval(timeoutSeconds);
        csrfTokens.saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(employee);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return EmployeeSession.from((EmployeePrincipal) employee.getPrincipal());
    }

    record Login(String loginName, String password) {}
    record PasswordChange(String temporaryPassword, String newPassword) {}
    record Csrf(String headerName, String token) {}

    record EmployeeSession(String type, String employeeId, String role, String unitId, String unitName,
                           String timeZone, String networkId, String workerId, boolean mustChangePassword) {
        static EmployeeSession from(EmployeePrincipal employee) {
            return new EmployeeSession("EMPLOYEE", Long.toString(employee.accountId()), employee.role(),
                    Long.toString(employee.unitId()), employee.unitName(), employee.timeZone(),
                    Long.toString(employee.networkId()), employee.workerId() == null ? null : employee.workerId().toString(),
                    employee.mustChangePassword());
        }
    }
}
