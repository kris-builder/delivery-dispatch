package org.example.delivery;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;

import org.example.delivery.station.StationQueries;
import org.example.delivery.station.StationQueries.StaffContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.flyway.target=1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationQueryTests {

    private static final StaffContext OPERATOR = new StaffContext(101, "NETWORK_OPERATOR");
    private static final StaffContext DISPATCHER = new StaffContext(101, "NETWORK_DISPATCHER");

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private StationQueries station;

    private final JsonMapper json = JsonMapper.builder().build();
    private JsonNode example;
    private Instant start;

    @BeforeAll
    void upgradeDatabaseWithAnExistingNetwork() {
        example = json.readTree(Path.of("..", "fixtures", "station-01-query.json").toFile());
        start = Instant.parse(example.path("at").stringValue());
        jdbc.sql("""
                INSERT INTO org_unit (id, kind, code, name, timezone)
                VALUES (101, 'NETWORK', 'JIANGAN-DEMO', '江岸网点（演示）', 'Asia/Shanghai')
                """).update();

        Flyway.configure().dataSource(dataSource).target("2").load().migrate();
        new ResourceDatabasePopulator(new ClassPathResource("station-query-seed.sql")).execute(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void upgradesV1AndPreservesTheExistingNetwork() {
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1
                """).query(Integer.class).single(), "Expected the station query migration");
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 1
                """).query(Integer.class).single(), "Expected the identity migration");
        assertEquals("JIANGAN-DEMO", jdbc.sql("SELECT code FROM org_unit WHERE id = 101")
                .query(String.class).single());
        assertEquals("courier-chen", jdbc.sql("SELECT worker_no FROM worker_profile WHERE id = 301")
                .query(String.class).single());
    }

    @Test
    void listsOnlyActiveAreasForTheEmployeesNetwork() {
        assertEquals(example.path("areas"), responseJson(station.areas(OPERATOR, 1, 20)));

        var otherStation = station.areas(new StaffContext(102, "NETWORK_OPERATOR"), 1, 20);
        assertEquals(List.of("204", "205"), otherStation.items().stream().map(StationQueries.Area::id).toList());
        assertNull(otherStation.items().get(1).regularWorker());
    }

    @Test
    void listsOnlyActiveNetworkCouriersForTheDispatchersNetwork() {
        assertEquals(example.path("workers").path("onDuty"),
                responseJson(station.onDutyWorkers(DISPATCHER, start, 1, 20)));
    }

    @Test
    void takingLeaveChangesAvailabilityAndPreservesRegularResponsibility() {
        assertEquals(example.path("workers").path("onDuty"),
                responseJson(station.onDutyWorkers(DISPATCHER, start, 1, 20)));
        assertEquals(1, changeChenDutyStatus("ON_LEAVE"));
        try {
            assertEquals(example.path("areas"), responseJson(station.areas(OPERATOR, 1, 20)));
            assertEquals(example.path("workers").path("onLeave"),
                    responseJson(station.onDutyWorkers(DISPATCHER, start, 1, 20)));
            assertEquals(301L, jdbc.sql("SELECT worker_id FROM area_duty WHERE area_id = 201")
                    .query(Long.class).single());
        } finally {
            changeChenDutyStatus("ON_DUTY");
        }
    }

    @Test
    void shiftIncludesItsStartAndExcludesItsEnd() {
        var zone = ZoneId.of(example.path("timeZone").stringValue());
        assertEquals(8, start.atZone(zone).getHour());
        var end = start.atZone(zone).withHour(12).toInstant();

        assertEquals(0, station.onDutyWorkers(DISPATCHER, start.minusNanos(1000), 1, 20).total());
        assertEquals(2, station.onDutyWorkers(DISPATCHER, start, 1, 20).total());
        assertEquals(2, station.onDutyWorkers(DISPATCHER, end.minusNanos(1000), 1, 20).total());
        assertEquals(0, station.onDutyWorkers(DISPATCHER, end, 1, 20).total());
    }

    @Test
    void rejectsAnOperatorReadingStaffingAndANonNetworkUnitReadingAreas() {
        assertThrows(SecurityException.class, () -> station.onDutyWorkers(OPERATOR, start, 1, 20));
        assertThrows(SecurityException.class,
                () -> station.areas(new StaffContext(103, "NETWORK_DISPATCHER"), 1, 20));
        assertThrows(SecurityException.class,
                () -> station.areas(new StaffContext(104, "NETWORK_OPERATOR"), 1, 20));
    }

    @Test
    void pagesOnlyTheFilteredResultsInStableOrder() {
        var areas = station.areas(DISPATCHER, 2, 1);
        assertEquals(2, areas.page());
        assertEquals(1, areas.pageSize());
        assertEquals(2, areas.total());
        assertEquals("202", areas.items().getFirst().id());

        var workers = station.onDutyWorkers(DISPATCHER, start, 2, 1);
        assertEquals(2, workers.total());
        assertEquals("302", workers.items().getFirst().id());
        var empty = station.onDutyWorkers(DISPATCHER, start, 3, 1);
        assertEquals(2, empty.total());
        assertEquals(List.of(), empty.items());
        assertThrows(IllegalArgumentException.class, () -> station.areas(DISPATCHER, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> station.onDutyWorkers(DISPATCHER, start, 1, 101));
    }

    private int changeChenDutyStatus(String status) {
        return jdbc.sql("""
                UPDATE shift_member SET duty_status = :status WHERE shift_id = 401 AND worker_id = 301
                """).param("status", status).update();
    }

    private JsonNode responseJson(Object response) {
        return json.readTree(json.writeValueAsBytes(response));
    }
}
