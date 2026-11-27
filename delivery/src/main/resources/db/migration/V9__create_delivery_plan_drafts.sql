CREATE TABLE delivery_plan (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    network_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    inbound_batch_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    evaluated_at DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_plan_network FOREIGN KEY (network_id) REFERENCES org_unit (id),
    CONSTRAINT fk_plan_shift FOREIGN KEY (shift_id) REFERENCES duty_shift (id),
    CONSTRAINT fk_plan_batch FOREIGN KEY (inbound_batch_id) REFERENCES inbound_batch (id),
    CONSTRAINT fk_plan_creator FOREIGN KEY (created_by) REFERENCES employee_account (id),
    KEY ix_plan_network_shift_status (network_id, shift_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE plan_item (
    plan_id BIGINT NOT NULL,
    parcel_id BIGINT NOT NULL,
    target_area_id BIGINT,
    target_area_name VARCHAR(128),
    proposed_worker_id BIGINT,
    proposed_worker_name VARCHAR(128),
    parcel_version BIGINT NOT NULL,
    reason_code VARCHAR(32),
    PRIMARY KEY (plan_id, parcel_id),
    CONSTRAINT fk_plan_item_plan FOREIGN KEY (plan_id) REFERENCES delivery_plan (id),
    CONSTRAINT fk_plan_item_parcel FOREIGN KEY (parcel_id) REFERENCES parcel (id),
    CONSTRAINT fk_plan_item_area FOREIGN KEY (target_area_id) REFERENCES delivery_area (id),
    CONSTRAINT fk_plan_item_worker FOREIGN KEY (proposed_worker_id) REFERENCES worker_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
