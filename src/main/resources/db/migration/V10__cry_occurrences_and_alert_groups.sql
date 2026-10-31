-- Physical occurrences and caregiver notifications have independent lifetimes.
CREATE TABLE cry_alert_groups (
 id uuid PRIMARY KEY, source_id uuid NOT NULL REFERENCES devices(id),
 child_id uuid NOT NULL, workspace_id uuid NOT NULL,
 opened_at timestamptz NOT NULL, closed_at timestamptz, notified boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX cry_one_waiting_alert ON cry_alert_groups(source_id,child_id) WHERE closed_at IS NULL;
ALTER TABLE detection_events ADD COLUMN cry_alert_id uuid REFERENCES cry_alert_groups(id);
ALTER TABLE detection_events ADD COLUMN cry_analysis jsonb;
ALTER TABLE detection_events ADD COLUMN cry_end_reason text;
ALTER TABLE detection_events ADD COLUMN cry_retired boolean NOT NULL DEFAULT false;
ALTER TABLE detection_events ADD COLUMN cry_quiet_since timestamptz;

CREATE FUNCTION attach_cry_alert(device uuid, child uuid, workspace uuid, event_uuid uuid, began timestamptz)
RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE alert_uuid uuid;
BEGIN
 PERFORM 1 FROM devices WHERE id=device FOR UPDATE;
 -- Also honors responses retained during the V9 projection rebuild.
 UPDATE cry_alert_groups g SET closed_at=f.confirmed FROM
  (SELECT e.cry_alert_id,max(c.updated_at) AS confirmed FROM care_feedback c JOIN detection_events e ON e.id=c.event_id
   WHERE e.source_id=device AND c.response='stopped' GROUP BY e.cry_alert_id) f
  WHERE g.id=f.cry_alert_id AND g.closed_at IS NULL;
 -- Delayed uploads belong to the already handled period, not a fresh alert.
 SELECT id INTO alert_uuid FROM cry_alert_groups WHERE source_id=device AND child_id=child
 AND opened_at<=began AND closed_at>=began ORDER BY opened_at DESC LIMIT 1;
 IF alert_uuid IS NULL THEN
  SELECT id INTO alert_uuid FROM cry_alert_groups WHERE source_id=device AND child_id=child AND closed_at IS NULL;
 END IF;
 IF alert_uuid IS NULL THEN
  alert_uuid:=event_uuid;
  INSERT INTO cry_alert_groups(id,source_id,child_id,workspace_id,opened_at) VALUES(alert_uuid,device,child,workspace,began);
 ELSE
  UPDATE cry_alert_groups SET opened_at=least(opened_at,began) WHERE id=alert_uuid;
 END IF;
 RETURN alert_uuid;
END $$;
CREATE FUNCTION cry_alert_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.type='cry' THEN
  NEW.cry_alert_id:=attach_cry_alert(NEW.source_id,NEW.child_id,NEW.workspace_id,NEW.id,NEW.started_at);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_cry_alert_insert BEFORE INSERT ON detection_events FOR EACH ROW EXECUTE FUNCTION cry_alert_insert();

-- Preserve original IDs/feedback. The old projection is archived in place, then
-- reconstructed from immutable raw events; no recordings or feedback are deleted.
CREATE TABLE cry_projection_v9_backup AS SELECT * FROM cry_episode_links;
UPDATE detection_events SET cry_retired=true WHERE id IN (SELECT event_id FROM cry_episode_links);
DELETE FROM cry_episode_links;

CREATE OR REPLACE FUNCTION project_sky_eye_cry(device uuid, body jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE event_uuid uuid; began timestamptz; finished timestamptz; observed timestamptz;
 previous detection_events%ROWTYPE; matching_end jsonb; key text; reason text;
BEGIN
 IF body->>'kind' NOT IN ('cry_started','cry_ended') THEN RETURN; END IF;
 PERFORM 1 FROM devices WHERE id=device FOR UPDATE;
 key:=body->>'episodeKey'; observed:=(body->>'observedAt')::timestamptz;
 began:=coalesce((body->>'startedAt')::timestamptz,observed);
 SELECT event_id INTO event_uuid FROM cry_episode_links WHERE device_id=device AND episode_key=key;
 IF body->>'kind'='cry_ended' THEN
  finished:=coalesce((body->>'endedAt')::timestamptz,observed);
  reason:=coalesce(body->>'endReason','quiet');
  -- The shipped model emits different IDs for candidate start/end. Match the
  -- preceding occurrence, never a future one or another child/device binding.
  IF event_uuid IS NULL THEN
   SELECT id INTO event_uuid FROM detection_events WHERE source_id=device AND type='cry' AND NOT cry_retired
    AND started_at<=finished AND (ended_at IS NULL OR ended_at>=finished)
    ORDER BY started_at DESC,id DESC LIMIT 1;
  END IF;
  IF event_uuid IS NULL AND body->>'startedAt' IS NOT NULL THEN
   PERFORM project_sky_eye_cry(device,body || jsonb_build_object('kind','cry_started','observedAt',began,'endedAt',NULL));
   SELECT event_id INTO event_uuid FROM cry_episode_links WHERE device_id=device AND episode_key=key;
  END IF;
  IF event_uuid IS NULL THEN RETURN; END IF; -- Keep unmatched ends in the raw log.
  INSERT INTO cry_episode_links VALUES(device,key,event_uuid,began,finished) ON CONFLICT(device_id,episode_key) DO UPDATE SET ended_at=excluded.ended_at;
  UPDATE detection_events SET ended_at=greatest(ended_at,finished),cry_end_reason=reason,last_observed_at=greatest(last_observed_at,observed) WHERE id=event_uuid
   AND finished>=(SELECT max(l.started_at) FROM cry_episode_links l WHERE l.event_id=event_uuid);
  RETURN;
 END IF;
 IF event_uuid IS NOT NULL THEN RETURN; END IF; -- Replays cannot reopen an interval.
 SELECT * INTO previous FROM detection_events WHERE source_id=device AND type='cry' AND NOT cry_retired
  AND started_at<=began ORDER BY started_at DESC,id DESC LIMIT 1 FOR UPDATE;
 IF previous.id IS NOT NULL AND
   ((previous.ended_at IS NULL AND previous.cry_end_reason IS DISTINCT FROM 'interrupted' AND began<=previous.last_observed_at+interval '8 seconds') OR
    (previous.cry_end_reason='quiet' AND began<=previous.ended_at+interval '3 seconds')) THEN
  event_uuid:=previous.id;
  UPDATE detection_events SET ended_at=NULL,cry_end_reason=NULL,last_observed_at=greatest(last_observed_at,began) WHERE id=event_uuid;
 ELSE
  -- Reuse the old aggregate ID for its first occurrence so links and feedback survive.
  SELECT b.event_id INTO event_uuid FROM cry_projection_v9_backup b JOIN detection_events e ON e.id=b.event_id
   WHERE b.device_id=device AND b.episode_key=key AND e.cry_retired LIMIT 1;
  IF event_uuid IS NULL THEN event_uuid:=md5('sky-eye-cry:' || device::text || ':' || key)::uuid; END IF;
  IF previous.id IS NOT NULL AND previous.ended_at IS NULL THEN
   UPDATE detection_events SET cry_end_reason='interrupted' WHERE id=previous.id;
  END IF;
  INSERT INTO detection_events(id,workspace_id,workspace_child_id,child_id,source_id,type,severity,status,started_at,last_observed_at,verifiable,summary,recommendation_status)
   SELECT event_uuid,workspace_id,workspace_child_id,child_id,id,'cry','caution','new',began,observed,true,'울음이 감지되었어요. 아이의 상태를 확인해 주세요.','unavailable' FROM devices WHERE id=device
   ON CONFLICT(id) DO UPDATE SET started_at=excluded.started_at,ended_at=NULL,last_observed_at=excluded.last_observed_at,cry_retired=false,cry_end_reason=NULL,
    cry_alert_id=coalesce(detection_events.cry_alert_id,excluded.cry_alert_id);
 END IF;
 INSERT INTO cry_episode_links VALUES(device,key,event_uuid,began,NULL) ON CONFLICT(device_id,episode_key) DO NOTHING;
 -- An end may have arrived before its start. Reconcile it without inventing an end.
 SELECT s.body INTO matching_end FROM sky_eye_events s WHERE s.device_id=device AND s.body->>'kind'='cry_ended'
  AND coalesce((s.body->>'endedAt')::timestamptz,s.observed_at)>=began
  AND ((s.body->>'episodeKey')=key OR s.body->>'startedAt' IS NULL)
  AND NOT EXISTS(SELECT 1 FROM sky_eye_events next_start WHERE next_start.device_id=device AND next_start.body->>'kind'='cry_started'
   AND next_start.observed_at>began+interval '8 seconds' AND next_start.observed_at<s.observed_at)
  ORDER BY coalesce((s.body->>'endedAt')::timestamptz,s.observed_at),s.event_id LIMIT 1;
 IF matching_end IS NOT NULL THEN PERFORM project_sky_eye_cry(device,matching_end); END IF;
END $$;

DO $$ DECLARE entry record; BEGIN
 FOR entry IN SELECT device_id,body FROM sky_eye_events WHERE body->>'kind' IN ('cry_started','cry_ended') ORDER BY observed_at,event_id
 LOOP PERFORM project_sky_eye_cry(entry.device_id,entry.body); END LOOP;
 FOR entry IN SELECT * FROM detection_events WHERE type='cry' AND cry_alert_id IS NULL ORDER BY started_at,id
 LOOP UPDATE detection_events SET cry_alert_id=attach_cry_alert(entry.source_id,entry.child_id,entry.workspace_id,entry.id,entry.started_at) WHERE id=entry.id; END LOOP;
END $$;

CREATE FUNCTION cry_response_closes_alert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.response='stopped' THEN
  UPDATE cry_alert_groups SET closed_at=coalesce(closed_at,NEW.updated_at)
   WHERE id=(SELECT cry_alert_id FROM detection_events WHERE id=NEW.event_id);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_cry_response_alert AFTER INSERT OR UPDATE ON care_feedback FOR EACH ROW EXECUTE FUNCTION cry_response_closes_alert();
UPDATE cry_alert_groups g SET closed_at=f.confirmed FROM
 (SELECT e.cry_alert_id,max(f.updated_at) AS confirmed FROM care_feedback f JOIN detection_events e ON e.id=f.event_id WHERE f.response='stopped' GROUP BY e.cry_alert_id) f WHERE g.id=f.cry_alert_id;
UPDATE cry_alert_groups g SET notified=true WHERE EXISTS(SELECT 1 FROM outbox_messages o JOIN detection_events e ON e.id=o.aggregate_id WHERE e.cry_alert_id=g.id);

-- Status observations enrich only the matching occurrence. Old records never
-- borrow today's audio scores. Missing audio is not evidence of quiet.
CREATE FUNCTION observe_sky_eye_cry(device uuid, audio jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE at_time timestamptz; active detection_events%ROWTYPE;
BEGIN
 at_time:=(audio->>'observedAt')::timestamptz;
 IF NOT coalesce((audio->>'available')::boolean,false) THEN
  UPDATE detection_events SET cry_end_reason='interrupted',cry_quiet_since=NULL WHERE source_id=device AND type='cry' AND ended_at IS NULL AND NOT cry_retired;
  RETURN;
 END IF;
 SELECT * INTO active FROM detection_events WHERE source_id=device AND type='cry' AND NOT cry_retired
  AND started_at<=at_time AND (ended_at IS NULL OR ended_at>=at_time) ORDER BY started_at DESC LIMIT 1;
 IF active.id IS NULL OR at_time<active.last_observed_at THEN RETURN; END IF;
 IF coalesce((audio->>'cryDetected')::boolean,false) THEN
  UPDATE detection_events SET cry_analysis=audio,cry_quiet_since=NULL,last_observed_at=at_time,
    cry_end_reason=CASE WHEN ended_at IS NULL THEN NULL ELSE cry_end_reason END WHERE id=active.id;
 ELSE
  UPDATE detection_events SET cry_quiet_since=coalesce(cry_quiet_since,at_time),last_observed_at=at_time,
   ended_at=CASE WHEN at_time>=cry_quiet_since+interval '3 seconds' THEN cry_quiet_since ELSE ended_at END,
   cry_end_reason=CASE WHEN at_time>=cry_quiet_since+interval '3 seconds' THEN 'quiet' ELSE cry_end_reason END WHERE id=active.id;
 END IF;
END $$;
