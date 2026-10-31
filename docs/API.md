# API 연결 안내

Base URL: `http://127.0.0.1:8080/api/v1`. JSON의 시각은 UTC ISO 8601이고, 하루 요약의 날짜 경계는 `Asia/Seoul`입니다. 금액·수유 기록 API는 없습니다.

## 가입과 공간 선택

```http
POST /api/v1/auth/signup
Content-Type: application/json

{"username":"aicoo_parent","password":"your-unique-password"}
```

201 응답의 `accessToken`, `refreshToken`, `username`, `workspaceId`, `membershipId`를 사용합니다. 회원가입에는 아이디와 비밀번호만 필요하며 이메일 확인 코드를 발급하거나 발송하지 않습니다. 이메일이 없는 계정의 `email`은 `null`, `emailVerified`는 `false`입니다. 기기 등록·해제·모델 피드백에는 이메일 인증이 필요하지 않습니다.

`POST /auth/login`은 같은 `username`/`password` 형식입니다. 아이디는 대소문자를 구분하지 않는 1~32자의 영문·숫자·점·밑줄·하이픈이며 영문·숫자로 시작합니다. 중복은 `409 USERNAME_UNAVAILABLE`입니다. V8은 기존 이메일 앞부분을 아이디로 배정하고 중복에 숫자 접미사를 붙입니다. 유효하지 않은 앞부분은 `user_`와 UUID 일부로 배정합니다. 기존 UUID·비밀번호·아이·기기 연결·기록은 유지됩니다. 기존 이메일 로그인 및 이메일만 보내는 이전 가입 API는 호환되지만 가입 시 인증 메일은 보내지 않습니다.

로그인 후 `GET /me/workspaces`로 공간을 선택합니다. 이후 요청은 `Authorization: Bearer ...`, `X-Workspace-Id: ...`를 지정합니다. `POST /auth/refresh`에 `{"refreshToken":"..."}`를 보내면 이전 refresh/access 토큰이 폐기되고 새 쌍을 받습니다. 회전은 클라이언트에서 한 번에 하나씩 진행하세요. 폐기된 refresh token 재사용 시 해당 세션 계열 전체가 종료됩니다.

기존 이메일 기반 초대·공유 API의 이메일 소유 확인 정책은 유지합니다. 이메일이 있는 기존 계정은 `/auth/request-verification`을 명시적으로 호출할 수 있고 로컬 모드에서는 이 응답에만 `verificationToken`이 반환됩니다. 일반 가입·기기 연결 화면은 이 API를 사용하지 않습니다.

웹 어댑터는 토큰을 메모리에만 보관합니다. 새로고침 후 영구 로그인이 필요한 배포에서는 별도 BFF의 Secure·HttpOnly 쿠키와 CSRF 방어를 설계하세요. refresh token을 localStorage나 URL에 넣지 마세요.

## 아이와 루틴

```http
POST /api/v1/children
Authorization: Bearer <accessToken>
X-Workspace-Id: <workspaceId>
Idempotency-Key: <uuid>
Content-Type: application/json

{"displayName":"지안","birthDate":"2026-01-15"}
```

등록 응답의 `id`가 childId, `workspaceChildId`가 공간 연결 ID입니다. 아이 생성자는 별도 명시적 아이 권한을 받습니다. 다른 구성원에게는 자동 부여되지 않습니다. 프로필의 `photoObjectKey`는 저장소 키만 받으며, 업로드/공개 이미지 호스팅 기능은 포함하지 않습니다.

루틴 `POST /children/{id}/routines` 예제:

```json
{"type":"play","title":"함께 놀기","scheduledAt":"2026-11-01T01:00:00Z","endedAt":"2026-11-01T01:30:00Z","timezone":"Asia/Seoul","status":"todo"}
```

`PATCH`로 `{"status":"done"}`를 보내면 완료 시각이 기록됩니다. `todo`로 되돌리면 완료 시각은 사라집니다. `DELETE`는 소프트 삭제입니다. `sleepMinutes`는 계획된 루틴 시간을 실제 수면 시간으로 꾸며 계산하지 않아 `null`입니다.

## 감지·AI 연결

아래 내부 API는 사용자의 JWT와 다른 `X-Worker-Key`가 필요합니다. 운영에서는 인터넷 라우팅에서도 차단해야 합니다.

1. `POST /internal/devices`: `{"workspaceId":"...","childId":"...","type":"camera"}`로 장치를 등록합니다.
2. `POST /internal/devices/{id}/heartbeat`: 카메라 상태와 분석의 실제 관측 시각을 보고합니다.
3. `POST /internal/detections`: 장치 바인딩과 신뢰도를 검사한 뒤 감지 이벤트를 저장합니다.
4. 울음 분석이 완료되면 `POST /internal/events/{id}/analysis`를 호출합니다.

Heartbeat:

```json
{"observedAt":"2026-11-01T01:00:00Z","status":"live","confidence":0.92,"analysisObservedAt":"2026-11-01T01:00:00Z","safety":{"posture":"stable","face":"stable","edge":"stable","objects":"unknown"}}
```

연결은 살아 있어도 분석 결과가 30초 넘게 오래되었거나 신뢰도가 0.6 미만이면 `unverifiable`입니다. 30초 동안 heartbeat가 없으면 조회 시 즉시 `offline`, 감시 작업이 `connection_lost` 이벤트를 생성합니다. 미연결 센서 값은 `null`입니다. 실제 측정은 `/internal/devices/{id}/readings`에 `type`, `value`, `observedAt`으로 보고합니다.

Detection:

```json
{"workspaceId":"...","childId":"...","sourceId":"...","sourceEventId":"device-unique-sequence-107","type":"cry","severity":"caution","startedAt":"2026-11-01T01:00:00Z","endedAt":"2026-11-01T01:02:00Z","verifiable":true,"confidence":0.87,"modelVersion":"your-model-version"}
```

`sourceEventId`는 장치 내에서 유일해야 합니다. 같은 키·본문은 같은 이벤트를 반환하고 다른 본문은 409입니다. 동일 장치·유형의 15초 이내 열린 이벤트를 그룹화하고, 심각도 상승은 새 실시간 메시지로 전달합니다. 의료 진단이 아니라 관찰 신호로 표시합니다.

Analysis:

```json
{"status":"ready","modelVersion":"your-model-version","causes":[{"key":"sleepiness","label":"졸림 가능성","reason":"추가 관찰이 필요해요"}],"actions":[{"key":"check_environment","title":"주변 환경 확인","explanation":"아이와 주변 환경을 직접 확인해 주세요","instructions":["얼굴이 가려져 있는지 확인해 주세요","주변 자극을 줄이고 부드럽게 달래 주세요"]}]}
```

실패 또는 데이터 부족은 `status=failed|insufficient_data`, 빈 `causes`·`actions`로 보냅니다. 공개 API는 200과 상태·기본 확인 안내를 반환하므로 프론트가 대기 화면에서 안전 안내를 계속 보여줄 수 있습니다. 원본 제안의 `503 RECOMMENDATION_PENDING` 대신 명시적 상태를 선택했습니다. 분석된 행동 스냅샷은 발행 후 수정하지 않습니다. 모델이 반환한 문구를 운영에 사용하기 전 의료 진단·안전하지 않은 수면 권고가 없는지 모델 어댑터에서 검증해야 합니다.

## 피드백·해결

`POST /events/{id}/feedback`, 필수 `Idempotency-Key`:

```json
{"attempts":[{"actionId":"추천 응답의 실제 id","state":"not_attempted","note":"아직 시도하지 못함"}],"response":"continues"}
```

다른 이벤트의 actionId는 거부합니다. 사용자당 이벤트 피드백은 한 번 저장합니다. 버튼 재클릭·네트워크 재시도에는 같은 키를 유지하세요.

- 알림 읽음: `PATCH /notifications/{id}/read`
- 내 확인 기록: `POST /events/{id}/acknowledgements`
- 현장 결과 제출: `POST /events/{id}/resolution` + `Idempotency-Key`

해결 본문은 `{"result":"checked_safe|intervention_done|false_alarm|needs_help","note":"현장 확인 결과"}`입니다. `needs_help`는 `checking`이며 해결 시각은 없습니다. 이미 해결된 이벤트에 다른 키로 해결을 다시 제출하면 409입니다. 단순 화면 열기는 위 세 API를 자동 호출하지 마세요.

## 시터 초대·공유

초대는 `POST /workspaces/{workspaceId}/invitations`에 다음 형태로 보냅니다.

```json
{"email":"sitter@example.test","role":"sitter","children":[{"workspaceChildId":"...","capabilities":["cry:read","cry:feedback"]}],"validUntil":"2026-11-02T00:00:00Z"}
```

초대 메일의 코드를, 이메일 확인을 마친 해당 사용자가 `POST /invitations/accept`의 `{"token":"..."}`로 제출합니다. 로컬 모드에서는 생성자가 `POST /invitations/{id}/local-token`을 호출해 개발용 코드를 얻습니다. 이 호출은 코드를 회전하므로 이전 코드는 무효입니다. 운영에서는 이 경로가 404입니다. 초대 취소는 수락 후에도 초대에서 생긴 권한을 회수합니다.

기관 간 공유는 `/data-share-grants`에 `childId`, `toWorkspaceId`, `scopes`, `consentRef`, `expiresAt`을 보냅니다. 자동으로 개인 기록을 복사하지 않습니다. 공유받은 기관의 관리자가 `/workspace-children/{id}/grants`에 `membershipId`, `capabilities`, 선택적 `validFrom`, `validUntil`을 보내 담당자를 지정합니다. 공유 범위를 넘는 권한은 거부합니다. 공유 철회는 연결·권한·스트림을 닫고 원래 개인 기록은 삭제하지 않습니다.

## 실시간·영상

`GET /realtime`은 SSE입니다. Bearer와 Workspace 헤더가 필요하므로 브라우저 기본 EventSource 대신 `fetch` 스트림을 사용합니다. [TypeScript 클라이언트](../clients/aicoo-client.ts)의 `realtime`이 이를 처리합니다. 연결은 최대 25초 후 정상 종료되며 새 연결을 만들 때 마지막 `sequence`를 `Last-Event-ID`에 전달합니다. `messageId` 또는 sequence로 중복 처리하세요. 재연결 시 REST 감지 목록도 재조회하세요.

권한·세션·구독 범위를 1초마다 재검증하고 접근이 사라지면 연결을 종료합니다. 최소 메시지에는 ID, 유형, 심각도, 시각만 포함합니다. 아이 이름이나 상세 분석은 보호된 REST에서 조회합니다.

`POST /children/{id}/stream-token`은 영상 바이트를 반환하지 않습니다. 게이트웨이에 토큰을 Authorization 헤더로 전달하세요. 게이트웨이는 연결 시 및 5초마다 `/internal/stream-tokens/introspect`를 호출하고, 만료·권한 회수·세션 폐기·카메라 단절 시 재생을 종료해야 합니다. 토큰을 쿼리 문자열이나 로그에 남기지 마세요. 기본 구성에는 미디어 게이트웨이가 없습니다.

## 울음·수면·하루 기록

`GET /children/{childId}/care-history?kind=cries|routines|sleepRecords|feedback&limit=100&cursor=...`

응답은 `{items,nextCursor}`입니다. 모든 페이지를 조회해야 전체 이력이 됩니다. `cries`는 종료 시간이 없으면 `durationSec: null`이며, `feedback`은 현재 보호자가 저장한 대응 결과입니다. 기기 연결이 끊기거나 해제되어도 아이에 대한 기록 조회 권한이 있으면 과거 이력을 볼 수 있습니다.

`PUT /children/{childId}/care-history`는 `Idempotency-Key`와 다음 본문을 받습니다. 각 배열은 최대 50개입니다.

```json
{
  "routines": [{"id":"클라이언트 UUID","type":"play","title":"놀이 시간","note":"","scheduledAt":"2026-11-01T01:00:00Z","endedAt":"2026-11-01T01:30:00Z","status":"done"}],
  "deletedRoutines": [],
  "sleepRecords": [{"id":"클라이언트 UUID","startedAt":"2026-10-31T14:00:00Z","endedAt":"2026-10-31T22:00:00Z"}],
  "deletedSleepRecords": [],
  "feedback": [{"eventId":"울음 UUID","actions":["hug"],"note":"안고 난 뒤 진정했어요","response":"stopped"}]
}
```

생성에는 버전을 생략하고 수정에는 조회한 `version`을 함께 보냅니다. 삭제 항목은 `{id,version}`입니다. 동시 변경은 `409 CARE_RECORD_CHANGED`이며 한 요청에서 일부만 저장되지 않습니다. 같은 동작 재시도는 키와 본문을 유지합니다. 응답의 `routines`, `sleepRecords`, `feedback`에는 갱신 버전과 서버 저장 시각이 포함됩니다.

일상 기록은 `routine:read/write`, 울음·대응 기록은 `cry:read/feedback`, 공통으로 `child:read`를 검사합니다. 행동은 `sleep`, `hug`, `noise`, `posture`, `diaper`, `none`, `other:자유 입력`이고 `none`은 단독 선택입니다. 모델 학습 명령인 `/sky-eye/feedback`과 일반 돌봄 일지는 별개입니다.

Flyway V7이 기존 Sky Eye 울음 후보를 `detection_events`로 반영하며 새 전송은 트리거로 반영합니다. `(device_id,episodeKey)`별로 시작·종료를 합치고 학습용 에피소드는 중복 집계하지 않습니다. 과거 데이터를 이관할 때 알림을 새로 발송하지 않습니다. 사용자 연결이 바뀌어도 기존 기기 바인딩의 기록을 다른 아이에게 이전하지 않습니다.

수면 기록은 실제 시작·종료가 필요합니다. 하루 요약은 Asia/Seoul 날짜로 자정을 나누고 겹치는 수면 시간을 합쳐 계산합니다. 수면 계획 루틴을 실제 수면으로 간주하지 않습니다. 원시 영상·오디오·학습 임베딩은 이 API에 저장하지 않습니다.

## 오류와 null

```json
{"error":{"code":"CAPABILITY_REQUIRED","message":"접근 권한이 없어요.","requestId":"..."}}
```

401은 인증, 403은 기능 권한, 404는 접근할 수 없는 자원, 409는 중복/상태 충돌, 422는 입력 오류입니다. `unknown`, `null`, 빈 수집 구간은 실제 정보 부족을 뜻합니다. 프론트에서 `0`이나 ‘안정’으로 바꾸지 마세요. `limit`은 1–100이며, cursor는 특정 아이·워크스페이스·필터에 묶여 있습니다.
