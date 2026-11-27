-- 仅用于隔离演示库。先运行 seed-planning-draft-demo.ps1 导入应到和实收，再执行本文件。
-- 逐票显式确认合成约定，保留任何后来已有的约定及其历史。
INSERT INTO delivery_agreement (parcel_id, method, home_address, confirmation_source, confirmed_at, consent_reference)
SELECT p.id, 'HOME',
       CASE p.waybill_no
           WHEN '000726030001' THEN '演示东路草案样例小区 1 号'
           WHEN '000726030002' THEN '草案样例小区 2 号'
       END,
       'SYNTHETIC', '2026-11-25 08:00:00', '配送草案演示确认'
FROM org_unit u
JOIN inbound_batch b ON b.network_id = u.id
JOIN inbound_expected e ON e.batch_id = b.id
JOIN parcel p ON p.id = e.parcel_id AND p.network_id = u.id
WHERE u.code = 'JIANGAN-DEMO' AND b.source_system = 'SYNTHETIC' AND b.source_batch_no = 'PLANNING-03-DEMO'
  AND p.waybill_no IN ('000726030001', '000726030002')
  AND NOT EXISTS (SELECT 1 FROM delivery_agreement a WHERE a.parcel_id = p.id);
