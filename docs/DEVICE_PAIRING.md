# 사용자–기기 페어링 API

## 흐름

1. 파이는 Ed25519 키를 생성·보관합니다. `POST /api/v1/pairing/challenges`에 `{publicKey, purpose:"pair"}`를 보냅니다. `publicKey`는 DER SubjectPublicKeyInfo의 표준 Base64입니다.
2. 60초 내 `aicoo-device-v1\npair\n{challengeId}\n{nonce}`의 UTF-8 바이트를 서명합니다. `POST /api/v1/pairing/start`에 `{publicKey, challengeId, nonce, signature}`(서명은 표준 Base64)를 보냅니다.
3. 응답의 `userCode`·`verificationUriComplete`·`fingerprint`는 보호자에게 표시합니다. `deviceCode`는 파이만 보관합니다. 두 코드는 서로 다른 값이고 5분 동안 유효합니다.
4. 보호자는 JWT와 `X-Workspace-Id`로 `POST /api/v1/device-pairings/inspect`에 `{code}`를 보내 확인값·만료 시각을 조회합니다.
5. 로그인한 계정으로 해당 아이의 `child:manage`, `child:read` 권한을 가진 보호자가 `POST /api/v1/device-pairings/approve`에 `{code, childId, name}`을 보냅니다. `Idempotency-Key`는 같은 승인 재시도에서 유지합니다.
6. 파이는 3초 이상 간격으로 `POST /api/v1/pairing/token`에 `{deviceCode}`를 보내 `pending` 또는 기기 토큰을 받습니다. 보호자 응답에는 토큰을 반환하지 않습니다. 승인된 응답의 네트워크 유실은 만료 전 동일 private code로 복구할 수 있습니다.
7. 이후 파이는 기기별 30일 토큰을 `X-Device-Token`으로 사용합니다. 재시작·만료 시 목적 `authenticate`로 새 challenge를 서명하여 `POST /api/v1/device/authenticate`에 제출합니다. 새 토큰 발급 시 이전 토큰이 폐기됩니다.

## 조회·해제

- `GET /api/v1/children/{childId}/devices`: 현재 공간·아이의 기기 목록. `connected`는 최근 30초 내 heartbeat 여부입니다. `state`는 영상/분석 상태와 구분해 표시합니다.
- `DELETE /api/v1/devices/{deviceId}`: 로그인 + 아이 관리 권한 필요. 기기 토큰, 영상 토큰, 미완료 모니터링 세션을 종료합니다. 이전 기록은 삭제·이동하지 않습니다.
- 같은 키로 다시 연결하려면 기존 보호자가 앱에서 해제하거나 파이의 키 서명으로 직접 해제해야 합니다. 해제 후 새 등록은 새 device ID를 가집니다.

### 파이에서 직접 해제

`POST /api/v1/pairing/challenges`에 `{publicKey, purpose:"unlink", deviceId}`를 보냅니다. 해제할 **현재 등록 번호**까지 포함한 `aicoo-device-v1\nunlink\n{challengeId}\n{nonce}\n{deviceId}`를 Ed25519로 서명한 뒤 `POST /api/v1/device/unlink`에 `{publicKey, challengeId, nonce, signature}`를 보냅니다. 계정 JWT나 기기 토큰만으로는 이 요청을 승인하지 않습니다. challenge는 60초·1회용이며 서명에 등록 번호가 포함됩니다.

서버는 해당 키의 기기인지 확인하고 identity → device 순서로 잠근 뒤 기존 앱 해제와 같은 권한 폐기를 수행합니다. 다른 아이로 새 승인된 연결에 과거 요청이 도착하면 `DEVICE_BINDING_CHANGED`로 거부합니다. 확인 응답을 놓친 경우 새 challenge로 **같은 deviceId**를 다시 해제하는 것은 멱등 처리합니다. 과거 기록은 보존하며 `device.unlink.local` 감사 기록을 남깁니다. V6 migration이 해제 목적과 대상 등록 번호를 추가합니다.

파이 실행기는 `--unlink`로 전송 서비스를 멈추고 해제 요청을 원자적으로 저장한 뒤 서버 확인을 기다립니다. 미확인 상태에서는 영상·관측 전송 및 새 코드 발급을 차단합니다. 서비스는 중단된 해제 요청을 먼저 재시도하고, 해제 완료 후 새 승인을 기다립니다. 장기 키·모델·개인화 기록은 이 명령에서 삭제하지 않습니다.

## 보안·운영 기준

- challenge는 목적·공개키·nonce·만료와 결합되고 성공 시 한 번만 사용됩니다.
- 공개키 해시별 트랜잭션 잠금과 DB 고유 제약으로 동시 승인 시 하나만 성공합니다.
- 사용자 코드·파이 private code·nonce·기기 토큰은 DB에 SHA-256 해시로 저장합니다. private code는 256비트 랜덤 값입니다. 기기 서명 키는 서버에 저장하지 않습니다.
- 기기 쓰기 API는 서버의 binding에서 공간·아이·source를 주입합니다. 토큰을 일반 사용자 JWT나 전역 worker 인증에 사용할 수 없습니다.
- 코드 조회·승인은 사용자별 15분당 10회, IP별 30회로 제한합니다. challenge는 IP별 40회, start는 20회, private poll은 600회입니다. 프록시 사용 시 신뢰하는 프록시에서만 클라이언트 IP를 복원하도록 별도 구성해야 합니다. 기본은 요청의 remote address를 사용합니다.
- 소프트웨어 키는 물리적 하드웨어 식별·공장 인증서가 아닙니다. 키 파일을 지우면 새 소프트웨어 identity가 만들어집니다.
- `FRONTEND_URL`은 보호자에게 접근 가능한 주소로 설정합니다. 운영 환경은 HTTPS가 필요합니다. 기기 연결에는 이메일·SMTP 설정이 필요하지 않습니다.
- V4 Flyway migration이 추가 테이블·컬럼을 생성합니다. 기존 worker 장치 등록 API는 호환성을 위해 남아 있지만 일반 파이에는 페어링 API를 사용합니다.

설치·실행은 [파이 안내](../raspberry-pi/README.md)를 참고하세요.
