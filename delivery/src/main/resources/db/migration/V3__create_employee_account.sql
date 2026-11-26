CREATE TABLE employee_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    unit_id BIGINT NOT NULL,
    worker_id BIGINT NULL,
    login_name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    session_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_employee_account_login UNIQUE (login_name),
    CONSTRAINT uq_employee_account_worker UNIQUE (worker_id),
    CONSTRAINT fk_employee_account_unit FOREIGN KEY (unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_employee_account_worker FOREIGN KEY (worker_id) REFERENCES worker_profile (id),
    CONSTRAINT ck_employee_account_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_employee_account_role_worker CHECK (
        (role_code IN ('NETWORK_COURIER', 'PICKUP_STATION_COURIER', 'NETWORK_TRANSFER_DRIVER') AND worker_id IS NOT NULL)
        OR (role_code IN ('NETWORK_DISPATCHER', 'NETWORK_OPERATOR', 'PICKUP_STATION_SORTER', 'PICKUP_STATION_LEADER')
            AND worker_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
