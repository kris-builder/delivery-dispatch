ALTER TABLE parcel
    ADD COLUMN address_area_id BIGINT,
    ADD COLUMN address_area_name VARCHAR(128),
    ADD COLUMN pending_area_reason VARCHAR(32),
    ADD COLUMN holder_kind VARCHAR(32),
    ADD CONSTRAINT fk_parcel_address_area FOREIGN KEY (address_area_id) REFERENCES delivery_area (id);

CREATE TABLE operation_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_type VARCHAR(16) NOT NULL,
    actor_id BIGINT NOT NULL,
    command VARCHAR(48) NOT NULL,
    operation_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    result JSON,
    committed_at DATETIME(6),
    CONSTRAINT uq_operation_key UNIQUE (actor_type, actor_id, command, operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE parcel_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parcel_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_employee_id BIGINT NOT NULL,
    actor_unit_id BIGINT NOT NULL,
    operation_receipt_id BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    details JSON NOT NULL,
    CONSTRAINT fk_event_parcel FOREIGN KEY (parcel_id) REFERENCES parcel (id),
    CONSTRAINT fk_event_employee FOREIGN KEY (actor_employee_id) REFERENCES employee_account (id),
    CONSTRAINT fk_event_unit FOREIGN KEY (actor_unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_event_operation FOREIGN KEY (operation_receipt_id) REFERENCES operation_receipt (id),
    KEY ix_parcel_event_timeline (parcel_id, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbound_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    parcel_id BIGINT NOT NULL,
    scan_event_id BIGINT NOT NULL,
    received_by BIGINT NOT NULL,
    received_at DATETIME(6) NOT NULL,
    matched_area_id BIGINT,
    void_event_id BIGINT,
    voided_at DATETIME(6),
    active_parcel_id BIGINT GENERATED ALWAYS AS (CASE WHEN voided_at IS NULL THEN parcel_id ELSE NULL END) STORED,
    CONSTRAINT uq_inbound_active_parcel UNIQUE (active_parcel_id),
    CONSTRAINT uq_inbound_scan_event UNIQUE (scan_event_id),
    CONSTRAINT fk_inbound_receipt_batch FOREIGN KEY (batch_id) REFERENCES inbound_batch (id),
    CONSTRAINT fk_inbound_receipt_parcel FOREIGN KEY (parcel_id) REFERENCES parcel (id),
    CONSTRAINT fk_inbound_receipt_event FOREIGN KEY (scan_event_id) REFERENCES parcel_event (id),
    CONSTRAINT fk_inbound_receipt_employee FOREIGN KEY (received_by) REFERENCES employee_account (id),
    CONSTRAINT fk_inbound_receipt_area FOREIGN KEY (matched_area_id) REFERENCES delivery_area (id),
    CONSTRAINT fk_inbound_void_event FOREIGN KEY (void_event_id) REFERENCES parcel_event (id),
    KEY ix_inbound_batch_active (batch_id, voided_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sort_credit (
    receipt_id BIGINT NOT NULL PRIMARY KEY,
    unit_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    source_event_id BIGINT NOT NULL,
    credited_at DATETIME(6) NOT NULL,
    void_event_id BIGINT,
    CONSTRAINT fk_sort_credit_receipt FOREIGN KEY (receipt_id) REFERENCES inbound_receipt (id),
    CONSTRAINT fk_sort_credit_unit FOREIGN KEY (unit_id) REFERENCES org_unit (id),
    CONSTRAINT fk_sort_credit_employee FOREIGN KEY (employee_id) REFERENCES employee_account (id),
    CONSTRAINT fk_sort_credit_event FOREIGN KEY (source_event_id) REFERENCES parcel_event (id),
    CONSTRAINT fk_sort_credit_void_event FOREIGN KEY (void_event_id) REFERENCES parcel_event (id),
    KEY ix_sort_credit_employee_day (unit_id, employee_id, credited_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
