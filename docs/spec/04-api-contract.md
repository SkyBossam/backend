# 04. REST API 계약 (v1 제안)

Base: `/api/v1` · `Authorization: Bearer ...` · `X-Workspace-Id: <uuid>`
성공은 JSON, 오류는 `{ "error": { "code": "...", "message": "...", "requestId": "..." } }`.
페이지네이션: `limit` + opaque `cursor`; 정렬은 UTC timestamp와 ID를 함께 사용.
쓰기 요청에 `Idempotency-Key` 지원(특히 초대 수락·피드백·해결 처리).

## 인증 / 공간 / 초대
| Method | Path | 권한/목적 |
|---|---|---|
| POST | `/auth/signup` | 회원가입, 개인 공간 초기 생성 |
| POST | `/auth/login` | 로그인 |
| POST | `/auth/refresh` | 토큰 회전 |
| POST | `/auth/logout` | 로그아웃 |
| GET | `/me/workspaces` | 내 공간과 역할 |
| POST | `/workspaces` | 기관 공간 생성(정책에 따라 제한) |
| GET | `/workspaces/{workspaceId}/members` | 멤버 목록 |
| POST | `/workspaces/{workspaceId}/invitations` | 시터·직원 초대 |
| POST | `/invitations/accept` | 초대 수락 |
| DELETE | `/invitations/{id}` | 초대 취소 |
| PATCH | `/workspace-children/{id}/grants/{grantId}` | 권한·만료 변경 |
| DELETE | `/workspace-children/{id}/grants/{grantId}` | 권한 회수 |
| POST | `/data-share-grants` | 기관 공유 동의/범위 생성 |
| DELETE | `/data-share-grants/{id}` | 공유 철회 |

## 아이 / 루틴 / 홈
| Method | Path | 권한/목적 |
|---|---|---|
| GET,POST | `/children` | 접근 가능한 아이 조회/등록 |
| GET,PATCH | `/children/{childId}` | 아이 정보 조회/수정 |
| GET | `/dashboard?childId={id}` | 위험도 우선 정렬, 헤드라인, 미확인 개수 |
| GET | `/children/{childId}/summary?date=YYYY-MM-DD` | 하루 요약 |
| GET,POST | `/children/{childId}/routines` | 루틴 목록·추가 |
| PATCH,DELETE | `/children/{childId}/routines/{routineId}` | 수정·완료·삭제 |

## 감지 / 케어 / 모니터링
| Method | Path | 권한/목적 |
|---|---|---|
| GET | `/children/{childId}/events?type=cry&cursor=...` | 감지 목록 |
| GET | `/events/{eventId}` | 감지 상세 |
| GET | `/events/{eventId}/recommendations` | 울음 원인 후보·행동 추천 |
| POST | `/events/{eventId}/feedback` | 시도/미시도 + 반응 저장 |
| POST | `/events/{eventId}/acknowledgements` | **사용자별** 알림 확인 |
| POST | `/events/{eventId}/resolution` | 현장 점검·결과 저장 |
| GET | `/children/{childId}/monitoring` | 연결/판정 상태·센서 |
| POST | `/children/{childId}/stream-token` | 짧은 수명 영상 접근 토큰 |
| GET,PATCH | `/preferences/notifications` | 알림 채널·소리 설정 |
| GET | `/notifications?unread=true` | 내 알림 |
| PATCH | `/notifications/{id}/read` | 나에게 온 알림 읽음 처리 |

## JSON 샘플: 울음 피드백
```json
{
  "attempts": [
    {"actionId":"5f92...", "state":"attempted", "note":"5분간 안아줌"},
    {"actionId":"6a37...", "state":"not_attempted"}
  ],
  "response":"reduced"
}
```
`response = stopped | reduced | continues | unknown`.
서버는 `actionId`가 해당 이벤트 추천에 포함되는지 검사. 권한·만료·중복 제출 검사.

## JSON 샘플: 위험 해결
```json
{
  "result": "checked_safe",
  "note": "침구 정리 후 얼굴 노출 직접 확인"
}
```
`result = checked_safe | intervention_done | false_alarm | needs_help`.
`needs_help`는 자동 해결 처리하지 않고 `checking` 또는 에스컬레이션 유지.

## 공통 상태 / 오류
- `severity = immediate | caution | stable | unknown`
- `status = new | acknowledged | checking | resolved | unverifiable`
- `404 CHILD_NOT_ACCESSIBLE`, `403 CAPABILITY_REQUIRED`, `409 EVENT_ALREADY_RESOLVED`,
  `409 INVITE_EXPIRED`, `422 INVALID_STATUS_TRANSITION`, `503 RECOMMENDATION_PENDING`.
- 읽기 액션, 모달 열기, ‘확인하러 가기’는 해결 API를 호출하지 않는다.
