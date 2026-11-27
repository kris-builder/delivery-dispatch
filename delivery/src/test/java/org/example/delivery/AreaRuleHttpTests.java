package org.example.delivery;

import java.util.Map;

import org.example.delivery.fulfillment.InboundAreaMatcher;
import org.example.delivery.fulfillment.InboundAreaMatcher.Area;
import org.example.delivery.fulfillment.InboundAreaMatcher.Match;
import org.example.delivery.fulfillment.InboundAreaMatcher.PendingReason;
import org.example.delivery.station.AreaRules;
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
        properties = {"delivery.demo.temporary-password=Demo-Temporary-001", "delivery.employee.session-timeout=32m"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AreaRuleHttpTests {
    private static final String RULES = "/staff/area-rules";

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired InboundAreaMatcher matcher;
    @Autowired AreaRules station;

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
    void duplicateAddressKeepsOriginalRuleAndExplicitUpdateUsesNewArea() throws Exception {
        try (var dispatcher = new AuthHttpClient(port)) {
            var session = expect(200, dispatcher.login("dispatcher-zhou", UPDATED));
            String actor = session.path("employeeId").asText();
            String east = areaId(dispatcher, 0);
            String west = areaId(dispatcher, 1);
            var created = expect(201, dispatcher.post(RULES, rule("百步亭花园", east), dispatcher.csrf()));
            String id = created.path("id").asText();
            assertEquals("东片区", created.path("areaName").asText());
            assertEquals(id, expect(200, dispatcher.get(RULES + "/" + id)).path("id").asText());
            assertEquals("AREA_RULE_CONFLICT", expect(409, dispatcher.post(RULES, rule("百步亭花园", west),
                    dispatcher.csrf())).path("code").asText());
            assertEquals(east, ruleArea(id));

            var updated = expect(200, dispatcher.patch(RULES + "/" + id,
                    Map.of("areaId", west, "active", true), dispatcher.csrf()));
            assertEquals(id, updated.path("id").asText());
            assertEquals("西片区", updated.path("areaName").asText());
            assertEquals(west, ruleArea(id));
            assertEquals(new Match(new Area(Long.parseLong(west), "西片区"), null),
                    matcher.match(session.path("unitId").asLong(), "后湖大道", "百步亭花园"));
            assertEquals(actor, jdbc.sql("SELECT updated_by FROM area_rule WHERE id = :id")
                    .param("id", id).query(String.class).single());
        }
    }

    @Test
    void matchesOnlyExactAddressesForTheTrustedNetworkAndRechecksAreaStatus() throws Exception {
        try (var dispatcher = new AuthHttpClient(port); var foreign = new AuthHttpClient(port)) {
            long network = expect(200, dispatcher.login("dispatcher-zhou", UPDATED)).path("unitId").asLong();
            long foreignNetwork = expect(200, foreign.login("dispatcher-other", UPDATED)).path("unitId").asLong();
            String west = areaId(dispatcher, 1);
            String community = "安居苑A区";
            String id = expect(201, dispatcher.post(RULES, rule(community, west), dispatcher.csrf())).path("id").asText();
            assertEquals(new Match(new Area(Long.parseLong(west), "西片区"), null),
                    matcher.match(network, "后湖大道", community));
            assertEquals(new Match(null, PendingReason.MISSING_ADDRESS), matcher.match(network, "后湖大道", " "));
            assertEquals(new Match(null, PendingReason.NO_EFFECTIVE_RULE), matcher.match(network, "后湖大道", "安居苑a区"));
            assertEquals(new Match(null, PendingReason.NO_EFFECTIVE_RULE), matcher.match(foreignNetwork, "后湖大道", community));
            String otherArea = areaId(foreign, 0);
            expect(201, foreign.post(RULES, rule(community, otherArea), foreign.csrf()));
            assertEquals(new Match(new Area(Long.parseLong(otherArea), "对照网点东片区"), null),
                    matcher.match(foreignNetwork, "后湖大道", community));
            assertEquals("西片区", station.findActiveArea(network, Long.parseLong(west)).orElseThrow().name());
            assertTrue(station.findActiveArea(foreignNetwork, Long.parseLong(west)).isEmpty());

            jdbc.sql("UPDATE delivery_area SET active = FALSE WHERE id = :id").param("id", west).update();
            try {
                assertEquals(new Match(null, PendingReason.MATCH_UNCONFIRMED), matcher.match(network, "后湖大道", community));
                assertTrue(station.findActiveArea(network, Long.parseLong(west)).isEmpty());
                expect(200, dispatcher.patch(RULES + "/" + id, Map.of("areaId", west, "active", false), dispatcher.csrf()));
                assertEquals(new Match(null, PendingReason.NO_EFFECTIVE_RULE), matcher.match(network, "后湖大道", community));
            } finally {
                jdbc.sql("UPDATE delivery_area SET active = TRUE WHERE id = :id").param("id", west).update();
            }
        }
    }

    @Test
    void rulesAreIsolatedAndRejectOperatorCsrfAndInvalidTargets() throws Exception {
        try (var dispatcher = new AuthHttpClient(port); var operator = new AuthHttpClient(port);
             var foreign = new AuthHttpClient(port)) {
            String unit = expect(200, dispatcher.login("dispatcher-zhou", UPDATED)).path("unitId").asText();
            expect(200, operator.login("operator-xu", UPDATED));
            expect(200, foreign.login("dispatcher-other", UPDATED));
            String east = areaId(dispatcher, 0);
            String foreignArea = areaId(foreign, 0);
            jdbc.sql("INSERT INTO delivery_area (network_id, code, name, active) VALUES (:unit, 'CLOSED', '已停用', FALSE)")
                    .param("unit", unit).update();
            String closed = jdbc.sql("SELECT id FROM delivery_area WHERE network_id = :unit AND code = 'CLOSED'")
                    .param("unit", unit).query(String.class).single();

            expect(403, operator.get(RULES));
            expect(403, operator.post(RULES, rule("权限测试", east), operator.csrf()));
            assertEquals("CSRF_INVALID", expect(403, dispatcher.post(RULES, rule("权限测试", east), null))
                    .path("code").asText());
            expect(400, dispatcher.post(RULES, rule("权限测试", closed), dispatcher.csrf()));
            expect(400, dispatcher.post(RULES, rule("权限测试", foreignArea), dispatcher.csrf()));
            assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM area_rule WHERE community = '权限测试'").query(Integer.class).single());

            String id = expect(201, dispatcher.post(RULES, rule("网点隔离", east), dispatcher.csrf())).path("id").asText();
            for (var rule : expect(200, foreign.get(RULES)).path("items")) {
                assertNotEquals(id, rule.path("id").asText());
            }
            expect(404, foreign.get(RULES + "/" + id));
            expect(404, foreign.patch(RULES + "/" + id, Map.of("areaId", foreignArea, "active", true), foreign.csrf()));
            expect(403, operator.patch(RULES + "/" + id, Map.of("areaId", east, "active", false), operator.csrf()));
            assertEquals(east, ruleArea(id));
        }
    }

    private static String areaId(AuthHttpClient client, int index) throws Exception {
        return expect(200, client.get("/staff/areas")).path("items").get(index).path("id").asText();
    }

    private String ruleArea(String ruleId) {
        return jdbc.sql("SELECT area_id FROM area_rule WHERE id = :id").param("id", ruleId).query(String.class).single();
    }

    private static Map<String, String> rule(String community, String areaId) {
        return Map.of("street", "后湖大道", "community", community, "areaId", areaId);
    }
}
