package org.example.delivery.identity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.example.delivery.web.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EmployeeAccounts {
    private static final String ACTIVE_ACCOUNTS = """
            SELECT a.id, a.role_code, a.worker_id, a.password_hash, a.must_change_password, a.session_version,
                   u.id AS unit_id, u.name AS unit_name, u.timezone, n.id AS network_id
            FROM employee_account a
            JOIN org_unit u ON u.id = a.unit_id AND u.active = TRUE
                AND u.kind = CASE WHEN a.role_code LIKE 'NETWORK%' THEN 'NETWORK' ELSE 'PICKUP_STATION' END
            JOIN org_unit n ON n.id = COALESCE(u.parent_id, u.id) AND n.kind = 'NETWORK' AND n.active = TRUE
                AND (u.kind <> 'NETWORK' OR u.parent_id IS NULL)
            LEFT JOIN worker_profile w ON w.id = a.worker_id AND w.unit_id = u.id
                AND w.kind = CASE WHEN a.role_code = 'NETWORK_TRANSFER_DRIVER' THEN 'TRANSFER_DRIVER' ELSE a.role_code END
            WHERE a.status = 'ACTIVE' AND (a.worker_id IS NULL OR w.id IS NOT NULL)
            """;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final String unknownAccountHash;

    EmployeeAccounts(JdbcClient jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.unknownAccountHash = passwords.encode(UUID.randomUUID().toString());
    }

    EmployeePrincipal authenticate(String loginName, String password) {
        if (loginName == null || loginName.isBlank() || loginName.length() > 64 || !validPassword(password)) {
            throw invalidCredentials();
        }
        Optional<Account> account = findActive("a.login_name", loginName.strip());
        String hash = account.map(Account::passwordHash).orElse(unknownAccountHash);
        if (!passwords.matches(password, hash) || account.isEmpty()) {
            throw invalidCredentials();
        }
        return account.orElseThrow().principal();
    }

    boolean isCurrent(EmployeePrincipal principal) {
        return findActive("a.id", principal.accountId()).map(Account::principal)
                .filter(principal::equals).isPresent();
    }

    @Transactional
    public EmployeePrincipal changeTemporaryPassword(EmployeePrincipal employee, String temporary, String replacement) {
        if (!validPassword(replacement) || replacement.equals(temporary)) {
            throw new ApiProblems.Failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请输入不同的新密码，最多 72 个 UTF-8 字节。");
        }
        Account account = findActive("a.id", employee.accountId())
                .filter(current -> current.principal().equals(employee))
                .orElseThrow(EmployeeAccounts::expired);
        if (!employee.mustChangePassword()) {
            throw new ApiProblems.Failure(HttpStatus.CONFLICT, "PASSWORD_ALREADY_CHANGED", "该账号已完成临时密码修改。");
        }
        if (!validPassword(temporary) || !passwords.matches(temporary, account.passwordHash())) {
            throw invalidCredentials();
        }
        int updated = jdbc.sql("""
                UPDATE employee_account SET password_hash = :hash, must_change_password = FALSE,
                    session_version = session_version + 1
                WHERE id = :id AND status = 'ACTIVE' AND session_version = :version AND must_change_password = TRUE
                """).param("id", employee.accountId()).param("version", employee.sessionVersion())
                .param("hash", passwords.encode(replacement)).update();
        if (updated != 1) {
            throw expired();
        }
        return findActive("a.id", employee.accountId()).map(Account::principal).orElseThrow(EmployeeAccounts::expired);
    }

    static boolean validPassword(String password) {
        return password != null && !password.isBlank() && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private Optional<Account> findActive(String column, Object value) {
        return jdbc.sql(ACTIVE_ACCOUNTS + " AND " + column + " = :value")
                .param("value", value)
                .query((row, index) -> new Account(new EmployeePrincipal(row.getLong("id"), row.getString("role_code"),
                        row.getLong("unit_id"), row.getString("unit_name"), row.getString("timezone"), row.getLong("network_id"),
                        row.getObject("worker_id", Long.class), row.getBoolean("must_change_password"),
                        row.getLong("session_version")), row.getString("password_hash")))
                .optional();
    }

    private static ApiProblems.Failure invalidCredentials() {
        return new ApiProblems.Failure(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录名或密码不正确。");
    }

    private static ApiProblems.Failure expired() {
        return new ApiProblems.Failure(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请重新登录。");
    }

    private record Account(EmployeePrincipal principal, String passwordHash) {}
}
