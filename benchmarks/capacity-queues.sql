-- Low-frequency live sample; do not log pg_stat_activity.query or credentials.
SELECT json_build_object(
 'utc', CURRENT_TIMESTAMP,
 'unpublished', (SELECT COUNT(*) FROM outbox_event WHERE status<>'PUBLISHED'),
 'failedEvents', (SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'),
 'lockWaiters', (SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'),
 'activeConnections', (SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active')
);
