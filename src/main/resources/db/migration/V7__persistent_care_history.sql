ALTER TABLE routines ADD COLUMN version bigint NOT NULL DEFAULT 1;
ALTER TABLE care_feedback ADD COLUMN actions jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE sleep_records (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL, workspace_child_id uuid NOT NULL, child_id uuid NOT NULL,
 started_at timestamptz NOT NULL, ended_at timestamptz NOT NULL,
 created_by uuid NOT NULL, version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 FOREIGN KEY(workspace_child_id,workspace_id,child_id) REFERENCES workspace_children(id,workspace_id,child_id),
 FOREIGN KEY(created_by,workspace_id) REFERENCES memberships(id,workspace_id),
 CHECK(ended_at>started_at AND ended_at<=started_at+interval '7 days')
);
CREATE INDEX idx_sleep_records_child ON sleep_records(workspace_id,child_id,started_at DESC,id DESC) WHERE deleted_at IS NULL;

-- The model exports both cry candidates and personalization episodes. Count only
-- candidates: episode segments must not count a second time as another cry.
-- IDs include the device binding so reconnecting to another child cannot move history.
CREATE FUNCTION project_sky_eye_cry(device uuid, body jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE event_uuid uuid; began timestamptz; finished timestamptz; observed timestamptz;
BEGIN
 IF body->>'kind' NOT IN ('cry_started','cry_ended') THEN RETURN; END IF;
 event_uuid := md5('sky-eye-cry:' || device::text || ':' || (body->>'episodeKey'))::uuid;
 observed := (body->>'observedAt')::timestamptz;
 began := coalesce((body->>'startedAt')::timestamptz, observed);
 IF body->>'kind'='cry_ended' THEN
   finished := coalesce((body->>'endedAt')::timestamptz, observed);
   began := least(began, finished);
 END IF;
 INSERT INTO detection_events(id,workspace_id,workspace_child_id,child_id,source_id,type,severity,status,
   started_at,ended_at,last_observed_at,verifiable,summary,recommendation_status)
 SELECT event_uuid,workspace_id,workspace_child_id,child_id,id,'cry','caution','new',
   began,finished,observed,true,'울음 의심 신호가 기록되었어요. 아이의 상태를 직접 확인해 주세요.','unavailable'
 FROM devices WHERE id=device
 ON CONFLICT(id) DO UPDATE SET
   started_at=least(detection_events.started_at,excluded.started_at),
   ended_at=greatest(detection_events.ended_at,excluded.ended_at),
   last_observed_at=greatest(detection_events.last_observed_at,excluded.last_observed_at);
 -- Deliberately do not enqueue notifications during projection or backfill.
END $$;

CREATE FUNCTION sky_eye_cry_history_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM project_sky_eye_cry(NEW.device_id,NEW.body); RETURN NEW; END $$;
CREATE TRIGGER trg_sky_eye_cry_history AFTER INSERT ON sky_eye_events
 FOR EACH ROW EXECUTE FUNCTION sky_eye_cry_history_trigger();

DO $$
DECLARE event record;
BEGIN
 FOR event IN SELECT device_id,body FROM sky_eye_events
   WHERE body->>'kind' IN ('cry_started','cry_ended') ORDER BY observed_at,event_id
 LOOP PERFORM project_sky_eye_cry(event.device_id,event.body); END LOOP;
END $$;
