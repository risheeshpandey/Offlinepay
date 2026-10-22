-- OPERATOR REFERENCE ONLY. Replace placeholder UUIDs. Do not run this whole file blindly.
-- Investigate root cause first; preserve financial history and immutable event payloads.

-- Requeue ONE failed event after Kafka recovery. Retry identity/payload stay unchanged.
UPDATE outbox_event SET status='PENDING', attempts=0, next_attempt_at=CURRENT_TIMESTAMP,
    lease_until=NULL, lease_token=NULL, published_at=NULL, last_error=NULL
WHERE id='REPLACE-WITH-EVENT-UUID' AND status='FAILED';

-- A known valid DLT event may already be PUBLISHED. Requeue its authoritative source,
-- after checking the DLT cause. Concurrent consumer dedup protects existing notices.
UPDATE outbox_event SET status='PENDING', attempts=0, next_attempt_at=CURRENT_TIMESTAMP,
    lease_until=NULL, lease_token=NULL, published_at=NULL, last_error=NULL
WHERE id='REPLACE-WITH-EVENT-UUID' AND status='PUBLISHED';

-- Retry ONE provider delivery only after fixing provider settings/idempotency support.
UPDATE user_notification SET delivery_status='PENDING', delivery_attempts=0,
    next_attempt_at=CURRENT_TIMESTAMP, lease_until=NULL, lease_token=NULL, last_error=NULL
WHERE id='REPLACE-WITH-NOTIFICATION-UUID' AND delivery_status='FAILED';

-- Resolve only after evidence confirms recovery or deliberate rejection of bad input.
UPDATE dead_letter_event SET status='RESOLVED'
WHERE id='REPLACE-WITH-DEAD-LETTER-UUID' AND status='OPEN';
