INSERT INTO org_unit (kind, code, name, timezone) VALUES
    ('NETWORK', 'JIANGAN-DEMO', '江岸网点（演示）', 'Asia/Shanghai'),
    ('NETWORK', 'OTHER-DEMO', '对照网点（演示）', 'Asia/Shanghai')
ON DUPLICATE KEY UPDATE code = org_unit.code;

INSERT INTO org_unit (kind, parent_id, code, name, timezone)
SELECT 'PICKUP_STATION', network.id, stations.code, stations.name, network.timezone
FROM org_unit network
CROSS JOIN (
    SELECT 'PICKUP-DEMO-A' AS code, '甲驿站（演示）' AS name
    UNION ALL SELECT 'PICKUP-DEMO-B', '乙驿站（演示）'
) stations
WHERE network.code = 'JIANGAN-DEMO'
ON DUPLICATE KEY UPDATE code = org_unit.code;

INSERT INTO delivery_area (network_id, code, name)
SELECT network.id, areas.code, areas.name FROM org_unit network
JOIN (
    SELECT 'JIANGAN-DEMO' AS network_code, 'EAST' AS code, '东片区' AS name
    UNION ALL SELECT 'JIANGAN-DEMO', 'WEST', '西片区'
    UNION ALL SELECT 'OTHER-DEMO', 'EAST', '对照网点东片区'
) areas ON areas.network_code = network.code
ORDER BY network.id, areas.code
ON DUPLICATE KEY UPDATE code = delivery_area.code;

INSERT INTO worker_profile (unit_id, worker_no, kind, display_name)
SELECT network.id, workers.worker_no, 'NETWORK_COURIER', workers.display_name FROM org_unit network
JOIN (
    SELECT 'JIANGAN-DEMO' AS network_code, 'courier-chen' AS worker_no, '陈立' AS display_name
    UNION ALL SELECT 'JIANGAN-DEMO', 'courier-liu', '刘洋'
    UNION ALL SELECT 'OTHER-DEMO', 'courier-wang', '王平'
) workers ON workers.network_code = network.code
ON DUPLICATE KEY UPDATE worker_no = worker_profile.worker_no;

INSERT INTO area_duty (area_id, worker_id, updated_at)
SELECT area.id, worker.id, '2026-11-24 08:00:00' FROM delivery_area area
JOIN org_unit network ON network.id = area.network_id
JOIN worker_profile worker ON worker.unit_id = network.id AND worker.worker_no = CASE
    WHEN network.code = 'OTHER-DEMO' THEN 'courier-wang'
    WHEN area.code = 'EAST' THEN 'courier-chen' ELSE 'courier-liu' END
WHERE (network.code = 'JIANGAN-DEMO' AND area.code IN ('EAST', 'WEST'))
   OR (network.code = 'OTHER-DEMO' AND area.code = 'EAST')
ON DUPLICATE KEY UPDATE area_id = area_duty.area_id;

INSERT INTO duty_shift (unit_id, service_date, code, starts_at, ends_at)
SELECT id, '2026-11-25', 'MORNING', '2026-11-25 08:00:00', '2026-11-25 12:00:00'
FROM org_unit WHERE code IN ('JIANGAN-DEMO', 'OTHER-DEMO')
ON DUPLICATE KEY UPDATE code = duty_shift.code;

INSERT INTO shift_member (shift_id, worker_id, duty_status)
SELECT shift.id, worker.id, 'ON_DUTY' FROM duty_shift shift
JOIN org_unit network ON network.id = shift.unit_id
JOIN worker_profile worker ON worker.unit_id = network.id AND worker.kind = 'NETWORK_COURIER'
WHERE shift.service_date = '2026-11-25' AND shift.code = 'MORNING'
  AND ((network.code = 'JIANGAN-DEMO' AND worker.worker_no IN ('courier-chen', 'courier-liu'))
      OR (network.code = 'OTHER-DEMO' AND worker.worker_no = 'courier-wang'))
ON DUPLICATE KEY UPDATE duty_status = shift_member.duty_status;
