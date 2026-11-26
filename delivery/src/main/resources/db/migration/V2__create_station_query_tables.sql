CREATE TABLE delivery_area (
    id BIGINT NOT NULL AUTO_INCREMENT,
    network_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uq_delivery_area_code UNIQUE (network_id, code),
    CONSTRAINT fk_delivery_area_network FOREIGN KEY (network_id) REFERENCES org_unit (id),
    KEY ix_delivery_area_network_active (network_id, active, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE worker_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    unit_id BIGINT NOT NULL,
    worker_no VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uq_worker_profile_number UNIQUE (unit_id, worker_no),
    CONSTRAINT fk_worker_profile_unit FOREIGN KEY (unit_id) REFERENCES org_unit (id),
    CONSTRAINT ck_worker_profile_kind CHECK (kind IN ('NETWORK_COURIER', 'PICKUP_STATION_COURIER', 'TRANSFER_DRIVER')),
    KEY ix_worker_profile_unit_kind_active (unit_id, kind, active, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE area_duty (
    area_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (area_id),
    CONSTRAINT fk_area_duty_area FOREIGN KEY (area_id) REFERENCES delivery_area (id),
    CONSTRAINT fk_area_duty_worker FOREIGN KEY (worker_id) REFERENCES worker_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE duty_shift (
    id BIGINT NOT NULL AUTO_INCREMENT,
    unit_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    code VARCHAR(32) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_duty_shift_code UNIQUE (unit_id, service_date, code),
    CONSTRAINT fk_duty_shift_unit FOREIGN KEY (unit_id) REFERENCES org_unit (id),
    CONSTRAINT ck_duty_shift_interval CHECK (starts_at < ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shift_member (
    shift_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    duty_status VARCHAR(16) NOT NULL,
    PRIMARY KEY (shift_id, worker_id),
    CONSTRAINT fk_shift_member_shift FOREIGN KEY (shift_id) REFERENCES duty_shift (id),
    CONSTRAINT fk_shift_member_worker FOREIGN KEY (worker_id) REFERENCES worker_profile (id),
    CONSTRAINT ck_shift_member_status CHECK (duty_status IN ('ON_DUTY', 'ON_LEAVE')),
    KEY ix_shift_member_worker_status (worker_id, duty_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
