package org.example.delivery.station;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.example.delivery.fulfillment.InboundAreaMatcher;
import org.example.delivery.station.StationQueries.Page;
import org.example.delivery.station.StationQueries.StaffContext;
import org.example.delivery.web.ApiProblems;
import org.springframework.context.annotation.Fallback;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Fallback
@Transactional(readOnly = true)
public class AreaRules implements InboundAreaMatcher {
    private static final String RULES = """
            SELECT r.id, r.street, r.community, r.area_id, a.name AS area_name, a.active AS area_active, r.active
            FROM area_rule r
            LEFT JOIN delivery_area a ON a.id = r.area_id AND a.network_id = r.network_id
            WHERE r.network_id = :network
            """;
    private static final String ACTIVE_AREA = """
            SELECT a.id, a.name FROM delivery_area a
            WHERE a.network_id = :network AND a.id = :area AND a.active = TRUE
            """;
    private static final RowMapper<Rule> RULE_MAPPER = (row, index) -> new Rule(row.getString("id"),
            row.getString("street"), row.getString("community"), row.getString("area_id"),
            row.getString("area_name"), row.getBoolean("area_active"), row.getBoolean("active"));

    private final JdbcClient jdbc;

    AreaRules(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<Rule> list(StaffContext staff, int page, int pageSize) {
        StationAccess.requireNetworkStaff(jdbc, staff, true);
        long total = jdbc.sql("SELECT COUNT(*) FROM area_rule WHERE network_id = :network")
                .param("network", staff.unitId()).query(Long.class).single();
        long offset = (page - 1L) * pageSize;
        List<Rule> items = offset >= total ? List.of() : jdbc.sql(RULES + " ORDER BY r.id LIMIT :size OFFSET :offset")
                .param("network", staff.unitId()).param("size", pageSize).param("offset", offset)
                .query(RULE_MAPPER).list();
        return new Page<>(items, page, pageSize, total);
    }

    public Rule get(StaffContext staff, long id) {
        StationAccess.requireNetworkStaff(jdbc, staff, true);
        return readRule(staff.unitId(), id);
    }

    @Transactional
    public Rule create(StaffContext staff, long actorId, String street, String community, long areaId) {
        StationAccess.requireNetworkStaff(jdbc, staff, true);
        String streetKey = addressPart(street);
        String communityKey = addressPart(community);
        requireActiveArea(staff.unitId(), areaId);
        var key = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                    INSERT INTO area_rule (network_id, street, community, area_id, updated_by)
                    VALUES (:network, :street, :community, :area, :actor)
                    """).param("network", staff.unitId()).param("street", streetKey)
                    .param("community", communityKey).param("area", areaId).param("actor", actorId)
                    .update(key, "id");
        } catch (DuplicateKeyException duplicate) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "AREA_RULE_CONFLICT", "该街道和小区已有规则，请在原规则上修改。");
        }
        return readRule(staff.unitId(), Objects.requireNonNull(key.getKey()).longValue());
    }

    @Transactional
    public Rule change(StaffContext staff, long actorId, long ruleId, long areaId, boolean active) {
        StationAccess.requireNetworkStaff(jdbc, staff, true);
        long oldArea = jdbc.sql("""
                SELECT area_id FROM area_rule WHERE network_id = :network AND id = :id FOR UPDATE
                """).param("network", staff.unitId()).param("id", ruleId).query(Long.class).optional()
                .orElseThrow(AreaRules::ruleNotFound);
        if (active || oldArea != areaId) {
            requireActiveArea(staff.unitId(), areaId);
        }
        jdbc.sql("""
                UPDATE area_rule SET area_id = :area, active = :active, updated_by = :actor
                WHERE network_id = :network AND id = :id
                """).param("area", areaId).param("active", active).param("actor", actorId)
                .param("network", staff.unitId()).param("id", ruleId).update();
        return readRule(staff.unitId(), ruleId);
    }

    /** The caller supplies the network from its trusted context. */
    public Optional<Area> findActiveArea(long networkId, long areaId) {
        return jdbc.sql(ACTIVE_AREA).param("network", networkId).param("area", areaId)
                .query((row, index) -> new Area(row.getLong("id"), row.getString("name"))).optional();
    }

    @Override
    public Match match(long networkId, String street, String community) {
        if (street == null || street.isBlank() || community == null || community.isBlank()) {
            return new Match(null, PendingReason.MISSING_ADDRESS);
        }
        return jdbc.sql("""
                SELECT a.id, a.name FROM area_rule r
                LEFT JOIN delivery_area a ON a.id = r.area_id AND a.network_id = r.network_id AND a.active = TRUE
                WHERE r.network_id = :network AND r.active = TRUE AND r.street = :street AND r.community = :community
                """).param("network", networkId).param("street", street.strip()).param("community", community.strip())
                .query((row, index) -> {
                    Long areaId = row.getObject("id", Long.class);
                    return areaId == null ? new Match(null, PendingReason.MATCH_UNCONFIRMED)
                            : new Match(new Area(areaId, row.getString("name")), null);
                }).optional().orElseGet(() -> new Match(null, PendingReason.NO_EFFECTIVE_RULE));
    }

    private Rule readRule(long network, long id) {
        return jdbc.sql(RULES + " AND r.id = :id").param("network", network).param("id", id)
                .query(RULE_MAPPER).optional().orElseThrow(AreaRules::ruleNotFound);
    }

    private void requireActiveArea(long network, long areaId) {
        jdbc.sql(ACTIVE_AREA + " FOR SHARE").param("network", network).param("area", areaId)
                .query((row, index) -> row.getLong("id")).optional().orElseThrow(() ->
                        new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_AREA", "请选择本网点启用的片区。"));
    }

    private static String addressPart(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 128) {
            throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "街道和小区均须填写，最多 128 个字符。");
        }
        return value.strip();
    }

    private static ApiProblems.Failure ruleNotFound() {
        return new ApiProblems.Failure(HttpStatus.NOT_FOUND, "AREA_RULE_NOT_FOUND", "本网点找不到这条地址规则。");
    }

    public record Rule(String id, String street, String community, String areaId, String areaName,
                       boolean areaActive, boolean active) {}
}
