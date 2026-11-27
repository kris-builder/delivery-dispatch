CREATE TABLE delivery_agreement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parcel_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    method VARCHAR(32) NOT NULL,
    target_area_id BIGINT,
    pickup_point_id BIGINT,
    home_address VARCHAR(512),
    confirmation_source VARCHAR(32) NOT NULL,
    confirmed_by BIGINT,
    confirmed_at DATETIME(6) NOT NULL,
    consent_reference VARCHAR(512),
    superseded_at DATETIME(6),
    active_parcel_id BIGINT GENERATED ALWAYS AS (CASE WHEN superseded_at IS NULL THEN parcel_id ELSE NULL END) STORED,
    CONSTRAINT uq_delivery_agreement_version UNIQUE (parcel_id, version),
    CONSTRAINT uq_delivery_agreement_active UNIQUE (active_parcel_id),
    CONSTRAINT fk_delivery_agreement_parcel FOREIGN KEY (parcel_id) REFERENCES parcel (id),
    CONSTRAINT fk_delivery_agreement_area FOREIGN KEY (target_area_id) REFERENCES delivery_area (id),
    CONSTRAINT fk_delivery_agreement_actor FOREIGN KEY (confirmed_by) REFERENCES employee_account (id),
    CONSTRAINT chk_delivery_agreement_destination CHECK (
        (method = 'HOME' AND home_address IS NOT NULL AND CHAR_LENGTH(TRIM(home_address)) > 0 AND pickup_point_id IS NULL)
        OR (method = 'PICKUP' AND pickup_point_id IS NOT NULL AND home_address IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
