CREATE TABLE plan_adjustment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    parcel_id BIGINT NOT NULL,
    plan_version BIGINT NOT NULL,
    old_worker_id BIGINT,
    new_worker_id BIGINT,
    reason VARCHAR(500) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_adjustment_item FOREIGN KEY (plan_id, parcel_id) REFERENCES plan_item (plan_id, parcel_id),
    CONSTRAINT fk_adjustment_old_worker FOREIGN KEY (old_worker_id) REFERENCES worker_profile (id),
    CONSTRAINT fk_adjustment_new_worker FOREIGN KEY (new_worker_id) REFERENCES worker_profile (id),
    CONSTRAINT fk_adjustment_actor FOREIGN KEY (changed_by) REFERENCES employee_account (id),
    CONSTRAINT uq_adjustment_plan_version_parcel UNIQUE (plan_id, plan_version, parcel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
