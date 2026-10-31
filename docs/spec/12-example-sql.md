# 12. PostgreSQL DDL 핵심 예제

아래는 **시작용 예시**로, 전체 ERD의 모든 테이블을 포함하는 완성형 마이그레이션은 아니다.
실제 Flyway 마이그레이션은 삭제·동의·RLS·인덱스 정책 확정 후 작성한다.

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE workspace_type AS ENUM ('PERSONAL','INSTITUTION');
CREATE TYPE membership_role AS ENUM ('owner','admin','guardian','sitter','staff','viewer');
CREATE TYPE event_severity AS ENUM ('immediate','caution','stable','unknown');
CREATE TYPE event_status AS ENUM ('new','acknowledged','checking','resolved','unverifiable');

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  status text NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE workspaces (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  type workspace_type NOT NULL,
  name text NOT NULL,
  status text NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE memberships (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  user_id uuid NOT NULL REFERENCES users(id),
  role membership_role NOT NULL,
  status text NOT NULL DEFAULT 'active',
  UNIQUE(workspace_id,user_id),
  UNIQUE(id,workspace_id)
);
CREATE TABLE children (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  display_name text NOT NULL,
  birth_date date,
  photo_object_key text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE workspace_children (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  child_id uuid NOT NULL REFERENCES children(id),
  link_status text NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now(),
  ended_at timestamptz,
  UNIQUE(id,workspace_id),
  UNIQUE(id,child_id)
);
CREATE UNIQUE INDEX ux_workspace_child_active
ON workspace_children(workspace_id,child_id) WHERE link_status='active';

CREATE TABLE child_access_grants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_child_id uuid NOT NULL,
  membership_id uuid NOT NULL,
  workspace_id uuid NOT NULL,
  capabilities jsonb NOT NULL DEFAULT '[]'::jsonb,
  valid_from timestamptz NOT NULL DEFAULT now(),
  valid_until timestamptz,
  revoked_at timestamptz,
  FOREIGN KEY (workspace_child_id,workspace_id)
    REFERENCES workspace_children(id,workspace_id),
  FOREIGN KEY (membership_id,workspace_id)
    REFERENCES memberships(id,workspace_id),
  CHECK (jsonb_typeof(capabilities)='array'),
  CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX idx_grants_membership ON child_access_grants(membership_id,workspace_child_id);

CREATE TABLE detection_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id),
  workspace_child_id uuid NOT NULL,
  child_id uuid NOT NULL,
  type text NOT NULL CHECK (type IN
    ('cry','face_covered','edge_proximity','movement','camera_obstructed','connection_lost')),
  severity event_severity NOT NULL,
  status event_status NOT NULL DEFAULT 'new',
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  verifiable boolean NOT NULL DEFAULT true,
  model_version text,
  confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
  summary text NOT NULL,
  FOREIGN KEY (workspace_child_id,workspace_id)
    REFERENCES workspace_children(id,workspace_id),
  FOREIGN KEY (workspace_child_id,child_id)
    REFERENCES workspace_children(id,child_id)
);
CREATE INDEX idx_events_feed ON detection_events(workspace_id,child_id,started_at DESC,id DESC);

CREATE TABLE event_acknowledgements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id uuid NOT NULL REFERENCES detection_events(id),
  membership_id uuid NOT NULL REFERENCES memberships(id),
  acknowledged_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(event_id,membership_id)
);
CREATE TABLE event_resolutions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id uuid NOT NULL UNIQUE REFERENCES detection_events(id),
  membership_id uuid NOT NULL REFERENCES memberships(id),
  result text NOT NULL CHECK (result IN
    ('checked_safe','intervention_done','false_alarm','needs_help')),
  note text,
  resolved_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE care_feedback (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id uuid NOT NULL REFERENCES detection_events(id),
  membership_id uuid NOT NULL REFERENCES memberships(id),
  response text NOT NULL CHECK (response IN ('stopped','reduced','continues','unknown')),
  note text,
  idempotency_key text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(membership_id,idempotency_key)
);
```

**주의:** 이 DDL만으로는 `event_acknowledgements`/`event_resolutions`의 멤버가 이벤트와 동일 공간에 속하는지 보장하지 않는다. 서비스 계층 및 RLS/트리거/복합 FK로 교차 공간 참조를 반드시 차단한다. 또한 `needs_help`는 resolution 기록과 별도로 이벤트 상태를 `resolved`로 변경하지 않는다.
