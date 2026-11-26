-- V1–V7 persisted business DATETIME values in UTC. Keep each represented instant.
UPDATE area_duty
SET updated_at = DATE_ADD(updated_at, INTERVAL 8 HOUR);

UPDATE duty_shift
SET starts_at = DATE_ADD(starts_at, INTERVAL 8 HOUR),
    ends_at = DATE_ADD(ends_at, INTERVAL 8 HOUR);

UPDATE parcel
SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 8 HOUR);

UPDATE inbound_batch
SET imported_at = DATE_ADD(imported_at, INTERVAL 8 HOUR);

UPDATE inbound_expected
SET imported_at = DATE_ADD(imported_at, INTERVAL 8 HOUR);

UPDATE parcel_event
SET occurred_at = DATE_ADD(occurred_at, INTERVAL 8 HOUR);

UPDATE inbound_receipt
SET received_at = DATE_ADD(received_at, INTERVAL 8 HOUR),
    voided_at = DATE_ADD(voided_at, INTERVAL 8 HOUR);

UPDATE sort_credit
SET credited_at = DATE_ADD(credited_at, INTERVAL 8 HOUR);

UPDATE operation_receipt
SET committed_at = DATE_ADD(committed_at, INTERVAL 8 HOUR);

UPDATE operation_receipt o
JOIN inbound_receipt r
  ON r.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(o.result, '$.result.receiptId')) AS UNSIGNED)
SET o.result = JSON_SET(o.result, '$.result.receivedAt',
    CONCAT(DATE_FORMAT(r.received_at, '%Y-%m-%dT%H:%i:%s.%f'), '+08:00'))
WHERE o.command = 'INBOUND_RECEIPT'
  AND JSON_TYPE(JSON_EXTRACT(o.result, '$.result.receivedAt')) = 'STRING';
