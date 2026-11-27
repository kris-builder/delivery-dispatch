INSERT INTO parcel (id, network_id, waybill_no, stage, holder_unit_id, created_at, updated_at)
SELECT 9001, network_id, '0000000022', 'AT_NETWORK', network_id,
    '2026-11-25 16:30:00.123456', '2026-11-25 16:30:00.123456'
FROM inbound_batch WHERE source_batch_no = 'V6-DEMO';

INSERT INTO inbound_expected (batch_id, parcel_id, imported_at)
SELECT id, 9001, '2026-11-25 16:30:00.123456'
FROM inbound_batch WHERE source_batch_no = 'V6-DEMO';

INSERT INTO operation_receipt (id, actor_type, actor_id, command, operation_key, request_hash, result, committed_at)
SELECT 9001, 'EMPLOYEE', imported_by, 'INBOUND_RECEIPT', 'legacy-receive', UNHEX(SHA2('legacy', 256)),
    JSON_OBJECT('status', 201, 'result', JSON_OBJECT('receiptId', '9001', 'receivedAt', '2026-11-25T16:30:00.123456Z')),
    '2026-11-25 16:30:00.123456'
FROM inbound_batch WHERE source_batch_no = 'V6-DEMO';

INSERT INTO parcel_event (id, parcel_id, event_type, actor_employee_id, actor_unit_id, operation_receipt_id, occurred_at, details)
SELECT 9001, 9001, 'INBOUND_RECEIVED', imported_by, network_id, 9001, '2026-11-25 16:30:00.123456', JSON_OBJECT()
FROM inbound_batch WHERE source_batch_no = 'V6-DEMO';

INSERT INTO inbound_receipt (id, batch_id, parcel_id, scan_event_id, received_by, received_at)
SELECT 9001, id, 9001, 9001, imported_by, '2026-11-25 16:30:00.123456'
FROM inbound_batch WHERE source_batch_no = 'V6-DEMO';

INSERT INTO sort_credit (receipt_id, unit_id, employee_id, source_event_id, credited_at)
SELECT 9001, network_id, imported_by, 9001, '2026-11-25 16:30:00.123456'
FROM inbound_batch WHERE source_batch_no = 'V6-DEMO';
