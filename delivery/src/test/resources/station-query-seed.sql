INSERT INTO org_unit (id, kind, parent_id, code, name, timezone, active) VALUES
    (102, 'NETWORK', NULL, 'OTHER-DEMO', '其他网点', 'Asia/Shanghai', TRUE),
    (103, 'PICKUP_STATION', 101, 'PICKUP-DEMO', '江岸驿站', 'Asia/Shanghai', TRUE),
    (104, 'NETWORK', NULL, 'INACTIVE-DEMO', '已停用网点', 'Asia/Shanghai', FALSE);

INSERT INTO delivery_area (id, network_id, code, name, active) VALUES
    (201, 101, 'EAST', '东片区', TRUE),
    (202, 101, 'WEST', '西片区', TRUE),
    (203, 101, 'OLD', '旧片区', FALSE),
    (204, 102, 'EAST', '外网点东片区', TRUE),
    (205, 102, 'NEW', '外网点新片区', TRUE);

INSERT INTO worker_profile (id, unit_id, worker_no, kind, display_name, active) VALUES
    (301, 101, 'courier-chen', 'NETWORK_COURIER', '陈立', TRUE),
    (302, 101, 'courier-liu', 'NETWORK_COURIER', '刘洋', TRUE),
    (303, 102, 'courier-wang', 'NETWORK_COURIER', '王平', TRUE),
    (304, 103, 'courier-lin', 'PICKUP_STATION_COURIER', '林清', TRUE),
    (305, 101, 'courier-zheng', 'NETWORK_COURIER', '郑宁', FALSE),
    (306, 101, 'driver-qian', 'TRANSFER_DRIVER', '钱明', TRUE),
    (307, 101, 'courier-sun', 'NETWORK_COURIER', '孙兰', TRUE);

INSERT INTO area_duty (area_id, worker_id, updated_at) VALUES
    (201, 301, '2026-11-24 00:00:00'),
    (202, 302, '2026-11-24 00:00:00'),
    (204, 303, '2026-11-24 00:00:00');

INSERT INTO duty_shift (id, unit_id, service_date, code, starts_at, ends_at) VALUES
    (401, 101, '2026-11-25', 'MORNING', '2026-11-25 00:00:00', '2026-11-25 04:00:00'),
    (402, 102, '2026-11-25', 'MORNING', '2026-11-25 00:00:00', '2026-11-25 04:00:00'),
    (403, 103, '2026-11-25', 'MORNING', '2026-11-25 00:00:00', '2026-11-25 04:00:00'),
    (404, 101, '2026-11-25', 'OVERLAP', '2026-11-25 00:00:00', '2026-11-25 02:00:00');

INSERT INTO shift_member (shift_id, worker_id, duty_status) VALUES
    (401, 301, 'ON_DUTY'), (401, 302, 'ON_DUTY'),
    (401, 305, 'ON_DUTY'), (401, 306, 'ON_DUTY'),
    (402, 303, 'ON_DUTY'), (402, 307, 'ON_DUTY'), (403, 304, 'ON_DUTY'),
    (404, 302, 'ON_DUTY');
