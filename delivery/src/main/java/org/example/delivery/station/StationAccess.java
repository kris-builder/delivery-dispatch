package org.example.delivery.station;

import org.example.delivery.station.StationQueries.StaffContext;
import org.springframework.jdbc.core.simple.JdbcClient;

final class StationAccess {
    private StationAccess() {}

    static String requireNetworkStaff(JdbcClient jdbc, StaffContext staff, boolean dispatcherOnly) {
        if (staff == null || !("NETWORK_DISPATCHER".equals(staff.roleCode())
                || (!dispatcherOnly && "NETWORK_OPERATOR".equals(staff.roleCode())))) {
            throw new SecurityException("This station operation requires an authorized network employee");
        }
        return jdbc.sql("SELECT timezone FROM org_unit WHERE id = :unitId AND kind = 'NETWORK' AND active = TRUE")
                .param("unitId", staff.unitId()).query(String.class).optional()
                .orElseThrow(() -> new SecurityException("This station operation requires an active delivery network"));
    }
}
