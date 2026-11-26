package org.example.delivery.station;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.example.delivery.station.StationQueries.StaffContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class StationScheduling {
    private final JdbcClient jdbc;

    public StationScheduling(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long schedule(StaffContext staff, long actorId, long workerId, String code, Instant startsAt, Instant endsAt) {
        String timezone = StationAccess.requireNetworkStaff(jdbc, staff, true);
        LocalDateTime start = utcTimestamp(startsAt);
        LocalDateTime end = utcTimestamp(endsAt);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Shift start must be earlier than its end");
        }
        if (code == null || code.isBlank() || code.length() > 32) {
            throw new IllegalArgumentException("Shift code must contain 1 to 32 characters");
        }

        lockCourier(staff, workerId);
        boolean overlaps = jdbc.sql("""
                SELECT s.id FROM shift_member m
                JOIN duty_shift s ON s.id = m.shift_id
                WHERE m.worker_id = :workerId AND s.unit_id = :unitId
                  AND s.starts_at < :end AND :start < s.ends_at
                LIMIT 1 FOR UPDATE
                """).param("workerId", workerId).param("unitId", staff.unitId())
                .param("start", start).param("end", end).query(Long.class).optional().isPresent();
        if (overlaps) {
            throw new ScheduleConflictException("Courier already has an overlapping shift");
        }

        var day = startsAt.atZone(ZoneId.of(timezone)).toLocalDate();
        var parameters = Map.of("unitId", staff.unitId(), "day", day, "code", code, "start", start, "end", end);
        jdbc.sql("""
                INSERT INTO duty_shift (unit_id, service_date, code, starts_at, ends_at)
                VALUES (:unitId, :day, :code, :start, :end)
                ON DUPLICATE KEY UPDATE id = id
                """).params(parameters).update();
        long shiftId = jdbc.sql("""
                SELECT id FROM duty_shift
                WHERE unit_id = :unitId AND service_date = :day AND code = :code
                  AND starts_at = :start AND ends_at = :end
                """).params(parameters).query(Long.class).optional()
                .orElseThrow(() -> new ScheduleConflictException("Shift code already has different hours for that day"));

        jdbc.sql("""
                INSERT INTO shift_member (shift_id, worker_id, duty_status, updated_by)
                VALUES (:shiftId, :workerId, 'ON_DUTY', :actorId)
                """).param("shiftId", shiftId).param("workerId", workerId).param("actorId", actorId).update();
        return shiftId;
    }

    public long setDutyStatus(StaffContext staff, long actorId, long workerId, long shiftId,
                              DutyStatus status, long expectedVersion) {
        StationAccess.requireNetworkStaff(jdbc, staff, true);
        if (status == null || expectedVersion < 0) {
            throw new IllegalArgumentException("Duty status and a nonnegative version are required");
        }
        lockCourier(staff, workerId);
        long currentVersion = jdbc.sql("""
                SELECT m.version FROM shift_member m
                JOIN duty_shift s ON s.id = m.shift_id
                WHERE m.shift_id = :shiftId AND m.worker_id = :workerId AND s.unit_id = :unitId
                FOR UPDATE
                """).param("shiftId", shiftId).param("workerId", workerId).param("unitId", staff.unitId())
                .query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Courier is not a member of this network shift"));
        if (currentVersion != expectedVersion) {
            throw new ShiftVersionConflictException();
        }
        jdbc.sql("""
                UPDATE shift_member SET duty_status = :status, updated_by = :actorId, version = version + 1
                WHERE shift_id = :shiftId AND worker_id = :workerId
                """).param("status", status.name()).param("actorId", actorId)
                .param("shiftId", shiftId).param("workerId", workerId).update();
        return currentVersion + 1;
    }

    private void lockCourier(StaffContext staff, long workerId) {
        // Serialize changes even when the courier has no shift members to lock yet.
        jdbc.sql("""
                SELECT id FROM worker_profile
                WHERE id = :workerId AND unit_id = :unitId AND kind = 'NETWORK_COURIER' AND active = TRUE
                FOR UPDATE
                """).param("workerId", workerId).param("unitId", staff.unitId())
                .query(Long.class).optional()
                .orElseThrow(() -> new SecurityException("An active courier from this network is required"));
    }

    private static LocalDateTime utcTimestamp(Instant time) {
        if (time == null) {
            throw new IllegalArgumentException("Shift start and end are required");
        }
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public enum DutyStatus { ON_DUTY, ON_LEAVE }

    public static class ShiftVersionConflictException extends IllegalStateException {}

    public static class ScheduleConflictException extends IllegalStateException {
        public ScheduleConflictException(String message) {
            super(message);
        }
    }
}
