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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("identity-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "delivery.demo.temporary-password=Demo-Temporary-001")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationAuthorizationTests {
    private static final String AREAS = "/staff/areas";
    private static final String WORKERS = "/staff/workers?at=2026-11-25T00:00:00Z";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    @BeforeAll
    void activateDemoAccountsThroughTheHttpEntryPoint() throws Exception {
        for (String name : new String[] {"dispatcher-zhou", "dispatcher-other", "operator-xu", "station-leader-a"}) {
            try (var client = new AuthHttpClient(port)) {
                expect(200, client.login(name, TEMP));
                expect(200, client.post("/employee-auth/password-changes", Map.of("temporaryPassword", TEMP,
                        "newPassword", UPDATED), client.csrf()));
            }
        }
    }

    @Test
    void bothDispatchersSeeOnlyTheirOwnAreasAndWorkers() throws Exception {
        try (var jiangan = loggedIn("dispatcher-zhou"); var other = loggedIn("dispatcher-other")) {
            String foreignUnit = expect(200, other.get("/auth/session")).path("unitId").asText();
            var localAreas = expect(200, jiangan.get(AREAS + "?unitId=" + foreignUnit + "&pageSize=1"));
            assertEquals(2, localAreas.path("total").asInt());
            assertEquals(1, localAreas.path("items").size());
            assertEquals("EAST", localAreas.path("items").get(0).path("code").asText());
            var foreignAreas = expect(200, other.get(AREAS));
            assertEquals(1, foreignAreas.path("total").asInt());
            assertEquals("EAST", foreignAreas.path("items").get(0).path("code").asText());
            assertNotEquals(localAreas.path("items").get(0).path("id"), foreignAreas.path("items").get(0).path("id"));
            var localRoster = expect(200, jiangan.get(WORKERS + "&unitId=" + foreignUnit));
            assertEquals(2, localRoster.path("total").asInt());
            assertTrue(localRoster.path("items").toString().contains("陈立"));
            var foreignRoster = expect(200, other.get(WORKERS));
            assertEquals(1, foreignRoster.path("total").asInt());
            assertEquals("王平", foreignRoster.path("items").get(0).path("displayName").asText());
        }
    }

    @Test
    void operatorCanReadAreasButCannotUseDispatcherEndpointAndStationCannotReadNetworkAreas() throws Exception {
        try (var operator = loggedIn("operator-xu"); var leader = loggedIn("station-leader-a");
             var anonymous = new AuthHttpClient(port)) {
            assertEquals(2, expect(200, operator.get(AREAS)).path("total").asInt());
            assertEquals("FORBIDDEN", expect(403, operator.get(WORKERS + "&role=NETWORK_DISPATCHER")).path("code").asText());
            assertEquals("FORBIDDEN", expect(403, leader.get(AREAS)).path("code").asText());
            expect(401, anonymous.get(WORKERS));
            assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM delivery_area").query(Integer.class).single());
        }
    }

    @Test
    void takingLeaveChangesTheHttpRosterButPreservesTheRegularWorker() throws Exception {
        try (var dispatcher = loggedIn("dispatcher-zhou")) {
            var east = expect(200, dispatcher.get(AREAS)).path("items").get(0);
            String workerId = east.path("regularWorker").path("id").asText();
            jdbc.sql("UPDATE shift_member SET duty_status = 'ON_LEAVE' WHERE worker_id = :id").param("id", workerId).update();
            try {
                var roster = expect(200, dispatcher.get(WORKERS));
                assertEquals(1, roster.path("total").asInt());
                assertEquals("刘洋", roster.path("items").get(0).path("displayName").asText());
                assertEquals(east, expect(200, dispatcher.get(AREAS)).path("items").get(0));
                assertEquals(workerId, jdbc.sql("SELECT worker_id FROM area_duty WHERE area_id = :area")
                        .param("area", east.path("id").asText()).query(String.class).single());
            } finally {
                jdbc.sql("UPDATE shift_member SET duty_status = 'ON_DUTY' WHERE worker_id = :id").param("id", workerId).update();
            }
        }
    }

    @Test
    void queryRejectsInvalidTimeAndPaginationAndUsesHalfOpenShiftInterval() throws Exception {
        try (var dispatcher = loggedIn("dispatcher-zhou")) {
            expect(400, dispatcher.get("/staff/workers?at=invalid"));
            expect(400, dispatcher.get("/staff/workers"));
            expect(400, dispatcher.get(AREAS + "?pageSize=101"));
            assertEquals(0, expect(200, dispatcher.get("/staff/workers?at=2026-11-24T23:59:59Z")).path("total").asInt());
            assertEquals(2, expect(200, dispatcher.get(WORKERS)).path("total").asInt());
            assertEquals(0, expect(200, dispatcher.get("/staff/workers?at=2026-11-25T04:00:00Z")).path("total").asInt());
        }
    }

    private AuthHttpClient loggedIn(String loginName) throws Exception {
        var client = new AuthHttpClient(port);
        try {
            expect(200, client.login(loginName, UPDATED));
            return client;
        } catch (Exception | AssertionError failure) {
            client.close();
            throw failure;
        }
    }
}
