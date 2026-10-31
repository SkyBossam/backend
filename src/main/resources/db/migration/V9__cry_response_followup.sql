ALTER TABLE care_feedback ADD COLUMN version bigint NOT NULL DEFAULT 1;
ALTER TABLE care_feedback ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE care_feedback ADD COLUMN history jsonb NOT NULL DEFAULT '[]'::jsonb;
UPDATE care_feedback SET updated_at=created_at;

-- Keep each raw model segment, but attach it to one caregiver response episode.
CREATE TABLE cry_episode_links (
 device_id uuid NOT NULL REFERENCES devices(id), episode_key text NOT NULL,
 event_id uuid NOT NULL REFERENCES detection_events(id) ON DELETE CASCADE,
 started_at timestamptz NOT NULL, ended_at timestamptz,
 PRIMARY KEY(device_id,episode_key)
);
CREATE INDEX idx_cry_episode_event ON cry_episode_links(event_id);
INSERT INTO cry_episode_links(device_id,episode_key,event_id,started_at,ended_at)
SELECT s.device_id,s.body->>'episodeKey',d.id,min(d.started_at),max(d.ended_at)
FROM sky_eye_events s JOIN detection_events d
 ON d.id=md5('sky-eye-cry:' || s.device_id::text || ':' || (s.body->>'episodeKey'))::uuid
WHERE s.body->>'kind' IN ('cry_started','cry_ended')
GROUP BY s.device_id,s.body->>'episodeKey',d.id;

CREATE OR REPLACE FUNCTION project_sky_eye_cry(device uuid, body jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE event_uuid uuid; began timestamptz; finished timestamptz; observed timestamptz;
 latest detection_events%ROWTYPE; response text; confirmed timestamptz;
BEGIN
 IF body->>'kind' NOT IN ('cry_started','cry_ended') THEN RETURN; END IF;
 -- Serialize starts/ends from the same binding, including concurrent requests.
 PERFORM 1 FROM devices WHERE id=device FOR UPDATE;
 observed := (body->>'observedAt')::timestamptz;
 began := coalesce((body->>'startedAt')::timestamptz, observed);
 IF body->>'kind'='cry_ended' THEN
   finished := coalesce((body->>'endedAt')::timestamptz, observed);
   began := least(began,finished);
 END IF;
 SELECT event_id INTO event_uuid FROM cry_episode_links WHERE device_id=device AND episode_key=body->>'episodeKey';
 IF event_uuid IS NULL THEN
   SELECT * INTO latest FROM detection_events WHERE source_id=device AND type='cry' ORDER BY started_at DESC,id DESC LIMIT 1 FOR UPDATE;
   IF latest.id IS NOT NULL AND began>=latest.started_at THEN
     SELECT f.response,f.updated_at INTO response,confirmed FROM care_feedback f WHERE f.event_id=latest.id ORDER BY f.updated_at DESC,f.id DESC LIMIT 1;
     -- Acknowledging or recording 'continues' never rearms an alert. Even after a
     -- caregiver confirms quiet, the same/open model episode retains its ID.
     IF response IS DISTINCT FROM 'stopped' OR latest.ended_at IS NULL OR began<=greatest(latest.ended_at,confirmed) THEN
       event_uuid := latest.id;
     END IF;
   END IF;
   IF event_uuid IS NULL THEN event_uuid := md5('sky-eye-cry:' || device::text || ':' || (body->>'episodeKey'))::uuid; END IF;
   INSERT INTO detection_events(id,workspace_id,workspace_child_id,child_id,source_id,type,severity,status,
     started_at,ended_at,last_observed_at,verifiable,summary,recommendation_status)
   SELECT event_uuid,workspace_id,workspace_child_id,child_id,id,'cry','caution','new',
     began,finished,observed,true,'울음이 감지되었어요. 대응과 아이의 반응을 기록해 주세요.','unavailable'
   FROM devices WHERE id=device ON CONFLICT(id) DO NOTHING;
 END IF;
 INSERT INTO cry_episode_links(device_id,episode_key,event_id,started_at,ended_at)
 VALUES(device,body->>'episodeKey',event_uuid,began,finished)
 ON CONFLICT(device_id,episode_key) DO UPDATE SET
   started_at=least(cry_episode_links.started_at,excluded.started_at),
   ended_at=greatest(cry_episode_links.ended_at,excluded.ended_at);
 UPDATE detection_events SET
   started_at=least(started_at,began),last_observed_at=greatest(last_observed_at,observed),
   ended_at=(SELECT CASE WHEN bool_or(l.ended_at IS NULL) THEN NULL ELSE max(l.ended_at) END FROM cry_episode_links l WHERE l.event_id=event_uuid)
 WHERE id=event_uuid;
 -- Historical uploads must never dispatch fresh notifications.
END $$;
