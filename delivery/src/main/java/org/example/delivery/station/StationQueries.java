package org.example.delivery.station;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StationQueries {
    private static final String AREA_FILTER = """
            FROM delivery_area a
            LEFT JOIN area_duty d ON d.area_id = a.id
            LEFT JOIN worker_profile w ON w.id = d.worker_id
                AND w.unit_id = a.network_id AND w.kind = 'NETWORK_COURIER'
            WHERE a.network_id = :unitId AND a.active = TRUE
            """;

    private static final String ON_DUTY_FILTER = """
            FROM worker_profile w
            WHERE w.unit_id = :unitId AND w.kind = 'NETWORK_COURIER' AND w.active = TRUE
              AND EXISTS (
                  SELECT 1
                  FROM shift_member m
                  JOIN duty_shift s ON s.id = m.shift_id AND s.unit_id = w.unit_id
                  WHERE m.worker_id = w.id AND m.duty_status = 'ON_DUTY'
                    AND s.starts_at <= :at AND :at < s.ends_at
              )
            """;

    private final JdbcClient jdbc;

    public StationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<Area> areas(StaffContext staff, int page, int pageSize) {
        requireNetworkStaff(staff, false);
        return queryPage(
                "SELECT a.id AS id, a.code, a.name, a.active, w.id AS worker_id, w.display_name ",
                AREA_FILTER, Map.of("unitId", staff.unitId()), page, pageSize,
                (row, rowNumber) -> {
                    String workerId = row.getString("worker_id");
                    Worker regularWorker = workerId == null ? null : new Worker(workerId, row.getString("display_name"));
                    return new Area(row.getString("id"), row.getString("code"), row.getString("name"),
                            row.getBoolean("active"), regularWorker);
                });
    }

    public Page<Worker> onDutyWorkers(StaffContext staff, Instant at, int page, int pageSize) {
        requireNetworkStaff(staff, true);
        LocalDateTime atUtc = LocalDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        return queryPage("SELECT w.id, w.display_name ", ON_DUTY_FILTER,
                Map.of("unitId", staff.unitId(), "at", atUtc), page, pageSize,
                (row, rowNumber) -> new Worker(row.getString("id"), row.getString("display_name")));
    }

    private void requireNetworkStaff(StaffContext staff, boolean dispatcherOnly) {
        if (staff == null || !("NETWORK_DISPATCHER".equals(staff.roleCode())
                || (!dispatcherOnly && "NETWORK_OPERATOR".equals(staff.roleCode())))) {
            throw new SecurityException("This station query requires an authorized network employee");
        }
        boolean activeNetwork = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM org_unit WHERE id = :unitId AND kind = 'NETWORK' AND active = TRUE
                )
                """).param("unitId", staff.unitId()).query(Boolean.class).single();
        if (!activeNetwork) {
            throw new SecurityException("This station query requires an active delivery network");
        }
    }

    private <T> Page<T> queryPage(String select, String filter, Map<String, ?> parameters,
            int page, int pageSize, RowMapper<T> mapper) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page must be positive and pageSize must be between 1 and 100");
        }
        long offset = (page - 1L) * pageSize;
        long total = jdbc.sql("SELECT COUNT(*) " + filter).params(parameters).query(Long.class).single();
        List<T> items = offset >= total ? List.of() : jdbc.sql(select + filter + " ORDER BY id LIMIT :limit OFFSET :offset")
                .params(parameters).param("limit", pageSize).param("offset", offset).query(mapper).list();
        return new Page<>(items, page, pageSize, total);
    }

    /** The authenticated role and unit resolved by the server before it invokes these queries. */
    public record StaffContext(long unitId, String roleCode) {}

    public record Page<T>(List<T> items, int page, int pageSize, long total) {}

    public record Area(String id, String code, String name, boolean active, Worker regularWorker) {}

    public record Worker(String id, String displayName) {}
}
