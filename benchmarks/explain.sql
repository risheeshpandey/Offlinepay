-- PostgreSQL only. Supply a load-test user UUID using psql -v user_id=... .
-- Plans depend on row counts, statistics and hardware; no optimization is claimed before measurement.
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM wallet WHERE user_id=:'user_id'::uuid;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,event_id,kind,amount_paise,created_at,read_at
FROM user_notification WHERE user_id=:'user_id'::uuid
ORDER BY created_at DESC,id DESC LIMIT 20;
-- Run the wallet query in core DB, notification query in notification DB.
