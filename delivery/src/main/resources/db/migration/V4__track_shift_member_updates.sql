ALTER TABLE shift_member
    ADD COLUMN updated_by BIGINT NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_shift_member_employee FOREIGN KEY (updated_by) REFERENCES employee_account (id),
    ADD CONSTRAINT ck_shift_member_version CHECK (version >= 0);
