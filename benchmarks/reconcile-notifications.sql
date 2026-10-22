-- Run in the isolated notification database after outbox + Kafka consumer drain.
-- Compare counts to core operation count / expected_notices from reconcile-core.sql.
SELECT COUNT(*) AS consumed_events FROM consumed_payment_event;
SELECT COUNT(*) AS actual_notices FROM user_notification;
SELECT COUNT(*) AS duplicate_notices FROM (
  SELECT event_id,user_id FROM user_notification GROUP BY event_id,user_id HAVING COUNT(*) > 1
) problems;
SELECT COUNT(*) AS open_dead_letters FROM dead_letter_event WHERE status='OPEN';
