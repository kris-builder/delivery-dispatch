CREATE TABLE org_unit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    kind VARCHAR(32) NOT NULL,
    parent_id BIGINT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    business_phone VARCHAR(32) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uq_org_unit_code UNIQUE (code),
    CONSTRAINT fk_org_unit_parent FOREIGN KEY (parent_id) REFERENCES org_unit (id),
    CONSTRAINT ck_org_unit_kind CHECK (kind IN ('NETWORK', 'PICKUP_STATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
