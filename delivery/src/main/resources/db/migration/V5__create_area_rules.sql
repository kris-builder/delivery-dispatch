CREATE TABLE area_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    network_id BIGINT NOT NULL,
    street VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    community VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    area_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_area_rule_address UNIQUE (network_id, street, community),
    CONSTRAINT fk_area_rule_network FOREIGN KEY (network_id) REFERENCES org_unit (id),
    CONSTRAINT fk_area_rule_area FOREIGN KEY (area_id) REFERENCES delivery_area (id),
    CONSTRAINT fk_area_rule_employee FOREIGN KEY (updated_by) REFERENCES employee_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
