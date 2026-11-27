package org.example.delivery.identity;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.delivery.web.ApiProblems;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class EmployeeSecurity {
    private static final String EMPLOYEE_AUTHORITY = "EMPLOYEE";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager employeeAuthenticationManager(EmployeeAccounts accounts) {
        return request -> employeeAuthentication(accounts.authenticate((String) request.getPrincipal(),
                (String) request.getCredentials()));
    }

    static UsernamePasswordAuthenticationToken employeeAuthentication(EmployeePrincipal employee) {
        return UsernamePasswordAuthenticationToken.authenticated(employee, null,
                List.of(new SimpleGrantedAuthority(EMPLOYEE_AUTHORITY)));
    }

    @Bean
    HttpSessionSecurityContextRepository sessionContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, HttpSessionSecurityContextRepository contexts,
                                            HttpSessionCsrfTokenRepository csrfTokens, EmployeeAccounts accounts,
                                            ObjectMapper json) {
        return http
                .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .addFilterBefore(new EmployeeRequestFilter(accounts, json), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/system/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/employee-auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").hasAuthority(EMPLOYEE_AUTHORITY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout", "/api/v1/employee-auth/password-changes")
                        .hasAuthority(EMPLOYEE_AUTHORITY)
                        .requestMatchers("/api/v1/staff/**", "/api/v1/operations/INBOUND_RECEIPT/**")
                        .hasAuthority(EMPLOYEE_AUTHORITY)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, denied) -> ApiProblems.write(json, response,
                                HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "请登录后继续。"))
                        .accessDeniedHandler((request, response, denied) -> {
                            if (denied instanceof CsrfException) {
                                ApiProblems.write(json, response, HttpStatus.FORBIDDEN, "CSRF_INVALID", "会话校验失败，请刷新后确认操作。");
                            } else {
                                ApiProblems.write(json, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "当前身份无权执行该操作。");
                            }
                        }))
                .build();
    }

    private static final class EmployeeRequestFilter extends OncePerRequestFilter {
        private static final Set<String> LIMITED_SESSION_ROUTES = Set.of(
                "GET /api/v1/auth/csrf", "GET /api/v1/auth/session", "GET /api/v1/system/health",
                "POST /api/v1/auth/logout", "POST /api/v1/employee-auth/password-changes");

        private final EmployeeAccounts accounts;
        private final ObjectMapper json;

        private EmployeeRequestFilter(EmployeeAccounts accounts, ObjectMapper json) {
            this.accounts = accounts;
            this.json = json;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(ApiProblems.REQUEST_ID, UUID.randomUUID().toString());
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof EmployeePrincipal employee) {
                boolean health = request.getMethod().equals("GET") && request.getServletPath().equals("/api/v1/system/health");
                if (!health && !accounts.isCurrent(employee)) {
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    ApiProblems.write(json, response, HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请重新登录。");
                    return;
                }
                if (employee.mustChangePassword()
                        && !LIMITED_SESSION_ROUTES.contains(request.getMethod() + " " + request.getServletPath())) {
                    ApiProblems.write(json, response, HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码。");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
