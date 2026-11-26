package org.example.delivery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.example.delivery.station.StationQueries;
import org.example.delivery.station.StationQueries.StaffContext;
import org.example.delivery.station.StationQueries.Worker;
import org.example.delivery.station.StationScheduling;
import org.example.delivery.station.StationScheduling.ScheduleConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

import static org.example.delivery.station.StationScheduling.DutyStatus.ON_DUTY;
import static org.example.delivery.station.StationScheduling.DutyStatus.ON_LEAVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(statements = """
        INSERT INTO org_unit (id, kind, code, name, timezone)
        VALUES (101, 'NETWORK', 'JIANGAN-DEMO', '江岸网点', 'Asia/Shanghai');
        """, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/station-query-seed.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = """
        INSERT INTO employee_account (id, unit_id, login_name, password_hash, role_code)
        VALUES (501, 101, 'fixture-dispatcher', 'unused-service-fixture', 'NETWORK_DISPATCHER');
        """, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class StationSchedulingTests {
    private static final long ACTOR = 501;
    private static final StaffContext DISPATCHER = new StaffContext(101, "NETWORK_DISPATCHER");
    private static final StaffContext OPERATOR = new StaffContext(101, "NETWORK_OPERATOR");
    private static final long CHEN = 301;
    private static final long LIU = 302;

    @Autowired
    private StationScheduling scheduling;
    @Autowired
    private StationQueries queries;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void recordsWholeShiftLeaveAndKeepsTheRegularAreaOwner() {
        Instant start = Instant.parse("2026-11-26T00:00:00Z");
        Instant end = start.plus(4, ChronoUnit.HOURS);
        long shift = scheduling.schedule(DISPATCHER, ACTOR, CHEN, "MORNING", start, end);
        assertEquals(shift, scheduling.schedule(DISPATCHER, ACTOR, LIU, "MORNING", start, end));
        assertEquals(List.of("陈立", "刘洋"), onDuty(start));

        scheduling.setDutyStatus(DISPATCHER, ACTOR, CHEN, shift, ON_LEAVE, 0);
        assertEquals("ON_LEAVE", status(CHEN, shift));
        assertEquals("陈立", queries.areas(OPERATOR, 1, 20).items().getFirst().regularWorker().displayName());
        assertEquals(List.of("刘洋"), onDuty(start));
        assertEquals(List.of("刘洋"), onDuty(end.minusSeconds(1)));

        scheduling.setDutyStatus(DISPATCHER, ACTOR, CHEN, shift, ON_DUTY, 1);
        assertEquals(List.of("陈立", "刘洋"), onDuty(start));
    }

    @Test
    void rejectsForeignCouriersWrongRoleAndInvalidIntervalsWithoutChanges() {
        Instant start = Instant.parse("2026-11-27T00:00:00Z");
        Instant end = start.plusSeconds(3600);
        assertThrows(SecurityException.class, () -> scheduling.schedule(OPERATOR, ACTOR, CHEN, "WRONG", start, end));
        assertThrows(SecurityException.class, () -> scheduling.schedule(DISPATCHER, ACTOR, 303, "WRONG", start, end));
        assertThrows(SecurityException.class, () -> scheduling.schedule(DISPATCHER, ACTOR, 304, "WRONG", start, end));
        assertThrows(IllegalArgumentException.class, () -> scheduling.schedule(DISPATCHER, ACTOR, CHEN, "WRONG", start, start));
        assertThrows(IllegalArgumentException.class, () -> scheduling.schedule(DISPATCHER, ACTOR, CHEN, "WRONG", end, start));
        assertThrows(SecurityException.class, () -> scheduling.setDutyStatus(OPERATOR, ACTOR, CHEN, 401, ON_LEAVE, 0));
        assertThrows(SecurityException.class, () -> scheduling.setDutyStatus(DISPATCHER, ACTOR, 303, 402, ON_LEAVE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> scheduling.setDutyStatus(DISPATCHER, ACTOR, CHEN, 402, ON_LEAVE, 0));
        assertEquals(0, shiftCount("2026-11-27"));
        assertEquals("ON_DUTY", status(CHEN, 401));
        assertEquals("ON_DUTY", status(303, 402));
    }

    @Test
    void rejectsOverlapEvenForLeaveAndAllowsAdjacentShifts() {
        Instant start = Instant.parse("2026-11-28T00:00:00Z");
        Instant end = start.plus(4, ChronoUnit.HOURS);
        long first = scheduling.schedule(DISPATCHER, ACTOR, CHEN, "FIRST", start, end);
        scheduling.setDutyStatus(DISPATCHER, ACTOR, CHEN, first, ON_LEAVE, 0);
        assertThrows(ScheduleConflictException.class, () -> scheduling.schedule(DISPATCHER, ACTOR, CHEN, "OVERLAP",
                start.plus(2, ChronoUnit.HOURS), end.plus(2, ChronoUnit.HOURS)));
        long second = scheduling.schedule(DISPATCHER, ACTOR, CHEN, "SECOND", end, end.plus(4, ChronoUnit.HOURS));
        assertEquals(2, shiftCount("2026-11-28"));
        assertEquals("ON_DUTY", status(CHEN, second));
        assertEquals(List.of(), onDuty(end.minusSeconds(1)));
        assertEquals(List.of("陈立"), onDuty(end));
        assertEquals(List.of(), queries.onDutyWorkersForShift(DISPATCHER, first, end, 1, 20).items());
        assertEquals(List.of("陈立"), queries.onDutyWorkersForShift(DISPATCHER, second, end, 1, 20)
                .items().stream().map(Worker::displayName).toList());
    }

    @Test
    void concurrentOverlappingRequestsCommitOnlyOneShiftAndMember() throws Exception {
        Instant start = Instant.parse("2026-11-29T00:00:00Z");
        var startTogether = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> submit(startTogether, "EARLY", start));
            var second = pool.submit(() -> submit(startTogether, "LATE", start.plus(2, ChronoUnit.HOURS)));
            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(id -> id > 0).count());
            assertEquals(1, outcomes.stream().filter(id -> id == 0).count());
        }
        assertEquals(1, shiftCount("2026-11-29"));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM shift_member m JOIN duty_shift s ON s.id = m.shift_id
                WHERE m.worker_id = 301 AND s.service_date = '2026-11-29'
                """).query(Integer.class).single());
    }

    private long submit(CyclicBarrier startTogether, String code, Instant start) throws Exception {
        startTogether.await(5, TimeUnit.SECONDS);
        try {
            return scheduling.schedule(DISPATCHER, ACTOR, CHEN, code, start, start.plus(4, ChronoUnit.HOURS));
        } catch (ScheduleConflictException conflict) {
            return 0;
        }
    }

    private List<String> onDuty(Instant at) {
        return queries.onDutyWorkers(DISPATCHER, at, 1, 20).items().stream().map(Worker::displayName).toList();
    }

    private int shiftCount(String serviceDate) {
        return jdbc.sql("SELECT COUNT(*) FROM duty_shift WHERE unit_id = 101 AND service_date = :day")
                .param("day", serviceDate).query(Integer.class).single();
    }

    private String status(long workerId, long shiftId) {
        return jdbc.sql("SELECT duty_status FROM shift_member WHERE worker_id = :workerId AND shift_id = :shiftId")
                .param("workerId", workerId).param("shiftId", shiftId).query(String.class).single();
    }
}
