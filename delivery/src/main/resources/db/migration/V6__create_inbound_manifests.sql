CREATE TABLE parcel (
    id BIGINT NOT NULL AUTO_INCREMENT,
    network_id BIGINT NOT NULL,
    waybill_no VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    recipient_phone VARCHAR(20),
    street VARCHAR(128),
    community VARCHAR(128),
    address VARCHAR(255),
    stage VARCHAR(32) NOT NULL DEFAULT 'EXPECTED',
    holder_unit_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_parcel_network_waybill UNIQUE (network_id, waybill_no),
    CONSTRAINT fk_parcel_network FOREIGN KEY (network_id) REFERENCES org_unit (id),
    CONSTRAINT fk_parcel_holder_unit FOREIGN KEY (holder_unit_id) REFERENCES org_unit (id),
    KEY ix_parcel_holder_stage (holder_unit_id, stage, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbound_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    network_id BIGINT NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    source_batch_no VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    source_fingerprint BINARY(32) NOT NULL,
    imported_by BIGINT NOT NULL,
    imported_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_inbound_batch_source UNIQUE (network_id, source_system, source_batch_no),
    CONSTRAINT fk_inbound_batch_network FOREIGN KEY (network_id) REFERENCES org_unit (id),
    CONSTRAINT fk_inbound_batch_importer FOREIGN KEY (imported_by) REFERENCES employee_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbound_expected (
    batch_id BIGINT NOT NULL,
    parcel_id BIGINT NOT NULL,
    recipient_phone VARCHAR(20),
    street VARCHAR(128),
    community VARCHAR(128),
    address VARCHAR(255),
    imported_at DATETIME(6) NOT NULL,
    PRIMARY KEY (batch_id, parcel_id),
    CONSTRAINT fk_expected_batch FOREIGN KEY (batch_id) REFERENCES inbound_batch (id),
    CONSTRAINT fk_expected_parcel FOREIGN KEY (parcel_id) REFERENCES parcel (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
