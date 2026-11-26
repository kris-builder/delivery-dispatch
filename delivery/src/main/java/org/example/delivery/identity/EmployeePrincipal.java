package org.example.delivery.identity;

public record EmployeePrincipal(long accountId, String role, long unitId, String unitName,
                                String timeZone, long networkId, Long workerId,
                                boolean mustChangePassword, long sessionVersion) {
}
