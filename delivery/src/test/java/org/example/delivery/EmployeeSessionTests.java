package org.example.delivery;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.example.delivery.AuthHttpClient.expect;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"delivery.demo.temporary-password=Demo-Temporary-001", "delivery.employee.session-timeout=3s"})
@ActiveProfiles("identity-demo")
class EmployeeSessionTests {
    static final String TEMP = "Demo-Temporary-001";
    static final String UPDATED = "Demo-Updated-001";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext web;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;

    @Test
    void temporaryPasswordOnlyGrantsLimitedSessionAndChangingItRotatesCredentials() throws Exception {
        try (var client = new AuthHttpClient(port); var second = new AuthHttpClient(port)) {
            client.csrf();
            String anonymous = client.sessionId();
            var staff = expect(200, client.login("dispatcher-zhou", TEMP));
            assertEquals("EMPLOYEE", staff.path("type").asText());
            assertEquals("NETWORK_DISPATCHER", staff.path("role").asText());
            assertEquals(staff.path("unitId"), staff.path("networkId"));
            assertTrue(staff.path("mustChangePassword").asBoolean());
            assertNotEquals(anonymous, client.sessionId());
            String limited = client.sessionId();

            expect(200, second.login("dispatcher-zhou", TEMP));
            assertTrue(expect(200, client.get("/auth/session")).path("mustChangePassword").asBoolean());
            assertEquals("PASSWORD_CHANGE_REQUIRED", expect(403, client.get("/staff/areas")).path("code").asText());
            var csrf = client.csrf();
            var payload = Map.of("temporaryPassword", TEMP, "newPassword", UPDATED);
            assertEquals("CSRF_INVALID", expect(403, client.post("/employee-auth/password-changes", payload, null)).path("code").asText());
            assertTrue(mustChange("dispatcher-zhou"));
            assertFalse(expect(200, client.post("/employee-auth/password-changes", payload, csrf)).path("mustChangePassword").asBoolean());
            assertNotEquals(limited, client.sessionId());
            assertNotEquals(csrf.path("token"), client.csrf().path("token"));
            assertFalse(mustChange("dispatcher-zhou"));
            String hash = jdbc.sql("SELECT password_hash FROM employee_account WHERE login_name = 'dispatcher-zhou'")
                    .query(String.class).single();
            assertTrue(hash.startsWith("$2"));
            assertFalse(hash.contains(UPDATED));
            expect(401, second.get("/auth/session"));
            expect(401, second.login("dispatcher-zhou", TEMP));
            expect(200, second.login("dispatcher-zhou", UPDATED));
        }
    }

    @Test
    void logoutExpiresSessionAndRequiresValidCsrf() throws Exception {
        try (var client = new AuthHttpClient(port)) {
            expect(401, client.get("/auth/session"));
            assertEquals("INVALID_CREDENTIALS", expect(401, client.login("dispatcher-other", "wrong")).path("code").asText());
            expect(200, client.login("dispatcher-other", TEMP));
            var wrong = Map.of("headerName", "X-CSRF-TOKEN", "token", "bad-token");
            var bad = new tools.jackson.databind.ObjectMapper().valueToTree(wrong);
            assertEquals("CSRF_INVALID", expect(403, client.post("/auth/logout", Map.of(), bad)).path("code").asText());
            expect(200, client.get("/auth/session"));
            assertEquals(204, client.post("/auth/logout", Map.of(), client.csrf()).statusCode());
            expect(401, client.get("/auth/session"));
            assertFalse(client.csrf().path("token").asText().isBlank());
        }
    }

    @Test
    void stationSessionUsesItsParentAndEventuallyExpiresAtIdle() throws Exception {
        try (var client = new AuthHttpClient(port)) {
            var staff = expect(200, client.login("station-leader-a", TEMP));
            assertEquals("PICKUP_STATION_LEADER", staff.path("role").asText());
            assertNotEquals(staff.path("unitId"), staff.path("networkId"));
            Thread.sleep(Duration.ofMillis(3500));
            expect(401, client.get("/auth/session"));
        }
    }

    @Test
    void recipientSessionWithValidCsrfCannotUseEmployeeWriteEndpoint() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(security).build();
        var recipient = UsernamePasswordAuthenticationToken.authenticated("recipient-demo", null,
                List.of(new SimpleGrantedAuthority("RECIPIENT")));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(recipient));
        var csrf = new ObjectMapper().readTree(mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/staff/accounts").session(session)
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText()))
                .andExpect(status().isForbidden());
        assertEquals(5, jdbc.sql("SELECT COUNT(*) FROM employee_account").query(Integer.class).single());
    }

    @Test
    void concurrentTemporaryPasswordChangesOnlyCommitOnce() throws Exception {
        try (var first = new AuthHttpClient(port); var second = new AuthHttpClient(port);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            expect(200, first.login("station-leader-b", TEMP));
            expect(200, second.login("station-leader-b", TEMP));
            var firstCsrf = first.csrf();
            var secondCsrf = second.csrf();
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> {
                start.await();
                return first.post("/employee-auth/password-changes", Map.of("temporaryPassword", TEMP,
                        "newPassword", "Demo-Change-A"), firstCsrf).statusCode();
            });
            var b = pool.submit(() -> {
                start.await();
                return second.post("/employee-auth/password-changes", Map.of("temporaryPassword", TEMP,
                        "newPassword", "Demo-Change-B"), secondCsrf).statusCode();
            });
            start.countDown();
            int firstStatus = a.get();
            int secondStatus = b.get();
            assertEquals(List.of(200, 401), java.util.stream.Stream.of(firstStatus, secondStatus).sorted().toList());
            assertEquals(1L, jdbc.sql("SELECT session_version FROM employee_account WHERE login_name = 'station-leader-b'")
                    .query(Long.class).single());
            try (var verification = new AuthHttpClient(port)) {
                expect(200, verification.login("station-leader-b", firstStatus == 200 ? "Demo-Change-A" : "Demo-Change-B"));
            }
        }
    }

    private boolean mustChange(String login) {
        return jdbc.sql("SELECT must_change_password FROM employee_account WHERE login_name = :login")
                .param("login", login).query(Boolean.class).single();
    }
}
