package org.example.delivery;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.example.delivery.AuthHttpClient.expect;
import static org.example.delivery.EmployeeSessionTests.TEMP;
import static org.example.delivery.EmployeeSessionTests.UPDATED;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("identity-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"delivery.demo.temporary-password=Demo-Temporary-001", "delivery.employee.session-timeout=31m"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationSchedulingHttpTests {
    private static final String SHIFTS = "/staff/shifts";
    private static final String AT = "?at=2026-11-25T00:00:00Z";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    @BeforeAll
    void activateAccounts() throws Exception {
        for (String name : new String[]{"dispatcher-zhou", "operator-xu", "dispatcher-other"}) {
            try (var client = new AuthHttpClient(port)) {
                expect(200, client.login(name, TEMP));
                expect(200, client.post("/employee-auth/password-changes",
                        Map.of("temporaryPassword", TEMP, "newPassword", UPDATED), client.csrf()));
            }
        }
    }

    @Test
    void leaveKeepsAreaResponsibilityAndRejectsTheOtherPagesOldVersion() throws Exception {
        try (var first = new AuthHttpClient(port); var second = new AuthHttpClient(port)) {
            String actor = expect(200, first.login("dispatcher-zhou", UPDATED)).path("employeeId").asText();
            expect(200, second.login("dispatcher-zhou", UPDATED));
            var member = expect(200, first.get(SHIFTS + AT)).path("items").get(0);
            var otherPage = expect(200, second.get(SHIFTS + AT)).path("items").get(0);
            assertEquals("陈立", member.path("displayName").asText());
            assertEquals("0", member.path("version").asText());
            assertEquals(member, otherPage);

            String path = statusPath(member.path("shiftId").asText(), member.path("workerId").asText());
            expect(200, first.post(path, status("ON_LEAVE", member.path("version").asText()), first.csrf()));
            assertEquals("SHIFT_VERSION_CONFLICT", expect(409,
                    second.post(path, status("ON_DUTY", otherPage.path("version").asText()), second.csrf()))
                    .path("code").asText());

            var roster = expect(200, first.get("/staff/workers" + AT));
            assertEquals(1, roster.path("total").asInt());
            assertEquals("刘洋", roster.path("items").get(0).path("displayName").asText());
            var east = expect(200, first.get("/staff/areas")).path("items").get(0);
            assertEquals("陈立", east.path("regularWorker").path("displayName").asText());
            assertEquals(1, jdbc.sql("""
                    SELECT COUNT(*) FROM shift_member WHERE worker_id = :worker AND shift_id = :shift
                    AND duty_status = 'ON_LEAVE' AND version = 1 AND updated_by = :actor
                    """).param("worker", member.path("workerId").asText()).param("shift", member.path("shiftId").asText())
                    .param("actor", actor).query(Integer.class).single());
        }
    }

    @Test
    void refusesOperatorForeignDispatcherAndMissingCsrfOnTheSameWriteEntry() throws Exception {
        try (var dispatcher = new AuthHttpClient(port); var operator = new AuthHttpClient(port);
             var foreign = new AuthHttpClient(port)) {
            expect(200, dispatcher.login("dispatcher-zhou", UPDATED));
            expect(200, operator.login("operator-xu", UPDATED));
            expect(200, foreign.login("dispatcher-other", UPDATED));
            var liu = expect(200, dispatcher.get(SHIFTS + AT)).path("items").get(1);
            var path = statusPath(liu.path("shiftId").asText(), liu.path("workerId").asText());
            var body = status("ON_LEAVE", liu.path("version").asText());
            assertEquals("FORBIDDEN", expect(403, operator.post(path, body, operator.csrf())).path("code").asText());
            assertEquals("FORBIDDEN", expect(403, foreign.post(path, body, foreign.csrf())).path("code").asText());
            assertEquals("CSRF_INVALID", expect(403, dispatcher.post(path, body, null)).path("code").asText());
            assertEquals(liu, expect(200, dispatcher.get(SHIFTS + AT)).path("items").get(1));
        }
    }

    @Test
    void dispatcherSchedulesAnExistingCourierAndStoresTheSessionActor() throws Exception {
        try (var dispatcher = new AuthHttpClient(port)) {
            var actor = expect(200, dispatcher.login("dispatcher-zhou", UPDATED)).path("employeeId").asText();
            var couriers = expect(200, dispatcher.get("/staff/shift-couriers"));
            assertEquals(2, couriers.path("total").asInt());
            String worker = couriers.path("items").get(0).path("id").asText();
            var payload = Map.of("workerId", worker, "code", "AFTERNOON", "startsAt", "2026-11-25T04:00:00Z",
                    "endsAt", "2026-11-25T08:00:00Z");
            var shift = expect(200, dispatcher.post(SHIFTS, payload, dispatcher.csrf())).path("shiftId").asText();
            assertEquals("SHIFT_CONFLICT", expect(409, dispatcher.post(SHIFTS, payload, dispatcher.csrf()))
                    .path("code").asText());
            assertEquals(1, jdbc.sql("""
                    SELECT COUNT(*) FROM shift_member WHERE shift_id = :shift AND worker_id = :worker
                    AND duty_status = 'ON_DUTY' AND updated_by = :actor AND version = 0
                    """).param("shift", shift).param("worker", worker).param("actor", actor).query(Integer.class).single());
        }
    }

    private static String statusPath(String shiftId, String workerId) {
        return SHIFTS + "/" + shiftId + "/members/" + workerId + "/duty-status";
    }

    private static Map<String, String> status(String value, String version) {
        return Map.of("status", value, "expectedVersion", version);
    }
}
