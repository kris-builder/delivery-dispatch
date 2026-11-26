package org.example.delivery.identity;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("identity-demo")
@Component
class DemoEmployeeData implements ApplicationRunner {
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final String temporaryPassword;

    DemoEmployeeData(DataSource dataSource, JdbcClient jdbc, PasswordEncoder passwords,
                     @Value("${delivery.demo.temporary-password:${DELIVERY_DEMO_TEMPORARY_PASSWORD:}}") String temporaryPassword) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.temporaryPassword = temporaryPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!EmployeeAccounts.validPassword(temporaryPassword)) {
            throw new IllegalArgumentException("Set DELIVERY_DEMO_TEMPORARY_PASSWORD for the identity demo profile (1–72 UTF-8 bytes)");
        }
        new ResourceDatabasePopulator(new ClassPathResource("demo/identity-demo.sql")).execute(dataSource);
        seed("dispatcher-zhou", "JIANGAN-DEMO", "NETWORK_DISPATCHER");
        seed("dispatcher-other", "OTHER-DEMO", "NETWORK_DISPATCHER");
        seed("operator-xu", "JIANGAN-DEMO", "NETWORK_OPERATOR");
        seed("station-leader-a", "PICKUP-DEMO-A", "PICKUP_STATION_LEADER");
        seed("station-leader-b", "PICKUP-DEMO-B", "PICKUP_STATION_LEADER");
    }

    private void seed(String loginName, String unitCode, String role) {
        jdbc.sql("""
                INSERT INTO employee_account (unit_id, login_name, password_hash, role_code)
                SELECT id, :loginName, :hash, :role FROM org_unit WHERE code = :unitCode
                ON DUPLICATE KEY UPDATE login_name = employee_account.login_name
                """).param("loginName", loginName).param("hash", passwords.encode(temporaryPassword))
                .param("role", role).param("unitCode", unitCode).update();
    }
}
