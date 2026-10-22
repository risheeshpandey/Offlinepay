SELECT json_build_object(
 'consumedEvents', (SELECT COUNT(*) FROM consumed_payment_event),
 'actualNotices', (SELECT COUNT(*) FROM user_notification),
 'duplicateNotices', (SELECT COUNT(*) FROM (
   SELECT event_id,user_id FROM user_notification GROUP BY event_id,user_id HAVING COUNT(*)>1
 ) x),
 'openDeadLetters', (SELECT COUNT(*) FROM dead_letter_event WHERE status='OPEN')
);
