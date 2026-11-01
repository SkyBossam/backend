-- A positive live status must have a durable detail record even if the event
-- uploader missed its start. Keep the same occurrence/notification rules.
ALTER FUNCTION observe_sky_eye_cry(uuid,jsonb) RENAME TO observe_sky_eye_cry_v10;
CREATE FUNCTION observe_sky_eye_cry(device uuid, audio jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE at_time timestamptz;
BEGIN
 PERFORM 1 FROM devices WHERE id=device FOR UPDATE;
 at_time:=(audio->>'observedAt')::timestamptz;
 IF coalesce((audio->>'available')::boolean,false) AND coalesce((audio->>'cryDetected')::boolean,false)
    AND at_time IS NOT NULL AND NOT EXISTS (
     SELECT 1 FROM detection_events WHERE source_id=device AND type='cry' AND NOT cry_retired
      AND started_at<=at_time AND
       ((ended_at IS NULL AND cry_end_reason IS DISTINCT FROM 'interrupted' AND last_observed_at>=at_time-interval '8 seconds')
        OR ended_at>=at_time)
    ) THEN
  PERFORM project_sky_eye_cry(device,jsonb_build_object('kind','cry_started','episodeKey','status:' || at_time::text,'observedAt',at_time,'startedAt',at_time));
 END IF;
 PERFORM observe_sky_eye_cry_v10(device,audio);
END $$;

-- A delayed model start may precede the first positive status by a few seconds.
-- Reuse that status record and its ID, preserving links and caregiver feedback.
ALTER FUNCTION project_sky_eye_cry(uuid,jsonb) RENAME TO project_sky_eye_cry_v10;
CREATE FUNCTION project_sky_eye_cry(device uuid, body jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE began timestamptz; candidate uuid;
BEGIN
 PERFORM 1 FROM devices WHERE id=device FOR UPDATE;
 IF body->>'kind'='cry_started' AND body->>'episodeKey' NOT LIKE 'status:%'
    AND NOT EXISTS (SELECT 1 FROM cry_episode_links WHERE device_id=device AND episode_key=body->>'episodeKey') THEN
  began:=coalesce((body->>'startedAt')::timestamptz,(body->>'observedAt')::timestamptz);
  SELECT e.id INTO candidate FROM detection_events e
   WHERE e.source_id=device AND e.type='cry' AND NOT e.cry_retired
    AND e.started_at>began AND e.started_at<=began+interval '8 seconds'
    AND EXISTS (SELECT 1 FROM cry_episode_links l WHERE l.event_id=e.id AND l.episode_key LIKE 'status:%')
    AND NOT EXISTS (SELECT 1 FROM cry_episode_links l WHERE l.event_id=e.id AND l.episode_key NOT LIKE 'status:%')
    AND NOT EXISTS (SELECT 1 FROM detection_events older WHERE older.source_id=device AND older.type='cry' AND NOT older.cry_retired
     AND older.started_at<=began AND (older.ended_at>=began OR older.ended_at IS NULL AND older.last_observed_at>=began-interval '8 seconds'))
   ORDER BY e.started_at LIMIT 1;
  IF candidate IS NOT NULL THEN
   UPDATE detection_events SET started_at=began WHERE id=candidate;
   UPDATE cry_alert_groups SET opened_at=least(opened_at,began) WHERE id=(SELECT cry_alert_id FROM detection_events WHERE id=candidate);
   INSERT INTO cry_episode_links(device_id,episode_key,event_id,started_at,ended_at)
    SELECT device,body->>'episodeKey',id,began,ended_at FROM detection_events WHERE id=candidate;
   RETURN;
  END IF;
 END IF;
 PERFORM project_sky_eye_cry_v10(device,body);
END $$;
