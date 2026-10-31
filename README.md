# 아이쿠 aicoo Backend

첨부된 `BabySense_Backend_Spec.zip`을 기준으로 만든 Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 API 서버입니다. 제품명은 **아이쿠(aicoo)**로 적용했습니다. 원본 명세는 [docs/spec](docs/spec)에 보관했습니다.

## 빠른 실행 — Windows, Docker 없이

```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
```

- API: `http://127.0.0.1:8080/api/v1`
- Swagger: `http://127.0.0.1:8080/docs`
- OpenAPI JSON: `http://127.0.0.1:8080/v3/api-docs`
- 상태 확인: `http://127.0.0.1:8080/actuator/health`
- 종료: 실행 터미널에서 `Ctrl+C`

이 작업 폴더에 준비된 `.tools` Java/Maven을 자동 사용합니다. 소스 ZIP을 다른 PC에 풀었다면 Java 21을 설치하고 `JAVA_HOME`을 지정하세요. Maven은 Wrapper가 내려받습니다. 최초 실행에는 인터넷이 필요합니다.

로컬 실행기는 **실제 PostgreSQL 16**을 자동 시작합니다. 데이터는 `data/postgres`, 로컬 비밀 키는 `data/local-secrets.json`에 보관되고 Git/소스 ZIP에서 제외됩니다. Windows의 한글 설치 경로 문제를 피하려고 PostgreSQL 실행 파일만 `%TEMP%/aicoo-postgres-binaries`에 풉니다. API와 DB 모두 로컬 인터페이스에 바인딩합니다. 이 개발용 DB는 trust 인증을 사용하므로 공용 서버에 배포하지 마세요.

포트 변경: `scripts/start-local.ps1 -Port 8081`. 초기에는 사용자가 없는 빈 데이터베이스입니다. 가입 API로 계정과 개인 공간을 생성합니다. 로컬 모드의 이메일 확인 코드는 가입 응답 `verificationToken`에만 반환됩니다.

## 구현 범위

| 영역 | 동작 |
|---|---|
| 인증 | BCrypt 비밀번호, 15분 JWT, 14일 회전형 refresh token, 재사용 탐지·세션 폐기, 이메일 확인, 인증 요청 횟수 제한 |
| 공간·권한 | 개인/기관 공간, 역할 + 아이별 명시적 권한, 유효 기간, 매 요청 DB 재검증 |
| 초대·공유 | 이메일 소유 확인, 48시간 일회용 초대, 철회, 기관 간 동의·범위·만료, 재공유 차단 |
| 아이·루틴 | 프로필, 루틴 생성·수정·완료·삭제, 한국 날짜 기준 하루 요약 |
| 감지 | 장치·아이·기관 바인딩, 중복 감지 키, 15초 그룹화, 낮은 신뢰도는 확인 불가 |
| 위험 대응 | 읽음 / 사용자별 확인 / 현장 해결 결과를 분리, `needs_help`는 `checking` 유지 |
| 울음 케어 | 분석 콜백, 원인 후보·행동 스냅샷, 실패·대기 시 기본 안내, 시도/미시도·반응 별도 기록 |
| 모니터링 | 연결·추론 신선도 검사, 실제 센서 측정치, 60초 영상 토큰, 권한·세션 재확인 |
| 파이 연결 | 5분 코드·QR 링크, 보호자 승인, Ed25519 기기 인증, 아이별 바인딩, 재부팅 재인증·연결 해제 |
| 알림 | 트랜잭션 outbox, 내구성 있는 DB 큐, 재시도·지수 백오프·DLQ, 앱 내 알림, SSE 재연결 이력 |
| 보안·운영 | 워크스페이스 복합 FK, 감사 로그, 명시적 CORS, 공통 오류·request ID, Flyway, Docker, CI |
| 개인정보 | 현재 허용 범위의 메타데이터 내보내기, 전체 내보내기·삭제 요청 접수 |

모든 일반 API는 현재 `X-Workspace-Id`의 데이터만 반환합니다. 기관 구성원이라고 아이를 자동 열람할 수 없습니다. 공유받은 기관 관리자도 `/workspace-children/{id}/grants`로 아이별 권한을 부여해야 합니다. 원래 기관의 공유 이력은 별도 `/data-share-grants/{id}/events`로만 조회합니다.

## API 사용

[API 사용 안내](docs/API.md)에 가입 → 아이 등록 → 내부 감지 → 케어 기록 흐름과 예제가 있습니다. 전체 필드는 실행 중인 Swagger에서 확인할 수 있습니다. 프론트엔드용 TypeScript 어댑터는 [clients/aicoo-client.ts](clients/aicoo-client.ts)입니다.

```http
Authorization: Bearer <accessToken>
X-Workspace-Id: <workspaceId>
Content-Type: application/json
Idempotency-Key: <동일한 사용자 동작에 고정한 UUID>
```

초대 수락·생성, 데이터 공유, 케어 피드백, 해결 기록에는 `Idempotency-Key`가 필수입니다. 동일 키·본문 재전송은 원래 응답을 돌려주고, 동일 키에 다른 본문을 보내면 409를 반환합니다. 권한이 회수된 후에는 과거 멱등 응답도 조회할 수 없습니다.

프론트의 `/devices`는 실제 계정·아이·기기 API와 연결됩니다. 나머지 기존 돌봄 화면은 로컬 모의 데이터 저장소를 사용하므로 어댑터 연결이 별도로 필요합니다. 공개 프론트 페이지를 볼 때 ChatGPT 로그인은 필요하지 않으며, 아이의 실제 API 데이터에는 아이쿠 계정 인증이 필요합니다.

## 사용자와 Raspberry Pi 연결

파이의 `pair` 명령 → 보호자의 `/devices` 로그인·아이 선택·코드 승인 → 파이의 `run` 명령 순서로 사용합니다. 초기 등록 이후에는 같은 기기 키로 자동 재인증합니다. 기존 보호자가 연결을 해제해야 다른 아이에게 다시 연결할 수 있고, 이전 기록은 원래 아이에게 남습니다.

- [파이 설치·실행·systemd 안내](raspberry-pi/README.md)
- [페어링 API·인증 구조](docs/DEVICE_PAIRING.md)
- `FRONTEND_URL` 환경 변수는 보호자가 QR 링크를 열 프론트 주소로 설정하세요.
- 카메라 영상 송출·AI 추론은 기존 장치 프로그램을 연결해야 합니다. 제공 에이전트의 기본 heartbeat는 분석 상태 `unverifiable`입니다.

## 테스트·빌드

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test.ps1
```

다른 환경에서는 Java 21 설정 후 `./mvnw -B -ntp verify`를 실행합니다. Linux에서는 PostgreSQL 초기화를 위해 일반 사용자로 실행하세요. 테스트는 H2 모형 대신 격리된 PostgreSQL 16에 모든 Flyway 마이그레이션을 적용하고 HTTP API, 권한 회수, 공유 철회, 스트림·SSE, 동시 제출, 복합 FK와 outbox 복구를 확인합니다. 결과는 `target/surefire-reports`, 실행 JAR는 `target/aicoo-backend-1.0.0.jar`에 생성됩니다.

## Docker 개발 실행

`.env.example`을 `.env`로 복사하고 서로 다른 무작위 `DATABASE_PASSWORD`, `JWT_SECRET`, `WORKER_KEY`를 입력하세요. JWT/worker 키는 각각 32자 이상이어야 합니다.

```sh
docker compose up --build
```

Compose는 로컬 개발용입니다. PostgreSQL 포트는 외부에 공개하지 않습니다. 운영 환경은 [운영·연동 안내](docs/OPERATIONS.md)를 참고하세요.

## 외부 서비스 연결 상태

- **영상:** 미디어 서버를 포함하지 않습니다. 기본 `MEDIA_GATEWAY_URL`이 비어 있어 토큰 발급은 503을 반환합니다. 연결 후에는 미디어 게이트웨이가 토큰을 검증하고 5초마다 다시 인증해야 합니다.
- **AI:** 실제 추론 모델을 실행하지 않습니다. 내부 분석 콜백을 제공하며, 결과가 없으면 `pending`과 기본 확인 안내를 반환합니다. 원인 확률이나 생체 수치를 만들어 넣지 않습니다.
- **푸시:** 앱 내 알림과 SSE는 구현되어 있습니다. 모바일/브라우저 푸시 공급자는 아직 연결하지 않았고, 푸시 요청은 `failed`로 기록합니다. 성공 전송으로 표시하지 않습니다.
- **메일:** 로컬 모드는 실제 메일을 보내지 않습니다. 운영은 SMTP 설정이 필요합니다. 전송 실패 시 코드 재요청 또는 초대 재생성을 사용합니다.
- **삭제:** 요청을 접수하며 즉시 삭제하지 않습니다. 백업·외부 저장소까지 포함하는 실제 삭제 처리는 운영 담당 절차가 필요합니다.

원본 영상·음성은 저장하지 않습니다. 외부 AI·영상·푸시 장비를 붙인 종단 간 검증과 운영 부하 테스트는 이 구현의 로컬 검증 범위에 포함되지 않습니다.
