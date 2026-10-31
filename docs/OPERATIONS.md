# 운영과 연동

## 배포 단위

이 버전은 모듈을 나눈 단일 Spring Boot 애플리케이션과 PostgreSQL로 실행됩니다. 명세의 Redis/RabbitMQ 권고 대신 PostgreSQL outbox·작업 테이블·`FOR UPDATE SKIP LOCKED`를 선택했습니다. 별도 브로커 없이 이벤트와 발송 작업을 같은 트랜잭션에 기록하고, 여러 API 인스턴스가 안전하게 분담합니다. 실제 대규모 배포에서는 이 경계를 별도 큐 소비자로 분리할 수 있습니다.

SSE는 PostgreSQL에 저장된 사용자별 메시지를 조회합니다. 인스턴스 메모리에만 의존하지 않아 재시작 후 이력을 복구할 수 있습니다. 인스턴스당 500개, 구성원당 3개 연결 제한이 있습니다. 부하 시험에 따른 수치는 아직 아닙니다.

## 운영 환경 변수

`APP_ENV=production` 또는 `staging`에서는 32자 이상의 JWT/worker 키와 `MAIL_MODE=smtp`가 없으면 시작하지 않습니다. 외부 요청에는 HTTPS가 필요합니다. `.env.example`의 Spring SSL 설정으로 애플리케이션 TLS를 활성화하거나, 신뢰할 수 있는 프록시만 연결되는 사설 네트워크와 올바른 forwarded-header 설정을 구성하세요. 인터넷 클라이언트가 전달하는 `X-Forwarded-*`를 무조건 신뢰하도록 켜지 마세요.

- `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`: 암호화된 운영 PostgreSQL. 로컬 임베디드 DB를 운영에 사용하지 않습니다.
- `JWT_SECRET`, `WORKER_KEY`: 독립적인 무작위 키. 로그·Git·이미지에 포함하지 않습니다. JWT 키를 바꾸면 기존 JWT·커서·이메일 기반 초대 식별이 무효화됩니다.
- `CORS_ORIGINS`: 허용할 프론트의 정확한 origin을 쉼표로 지정합니다. 공개 aicoo 프론트를 붙일 때 해당 origin을 추가합니다.
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`: STARTTLS와 서버 인증서 검증을 사용하는 SMTP.
- `MEDIA_GATEWAY_URL`: 실제 미디어 게이트웨이. TLS와 주기적 토큰 검증을 반드시 구현해야 합니다.
- `API_DOCS_ENABLED=false`: 운영 Swagger 비활성화.
- `ALLOW_INSTITUTIONS=false`: 셀프서비스 기관 생성 차단이 필요한 경우.

API 이미지에는 테스트용 PostgreSQL·개발 실행기·로컬 비밀 키가 포함되지 않습니다. 내부 `/api/v1/internal/**`는 키 검사 외에도 ingress/방화벽에서 비공개로 제한하세요. 사용자 JWT만으로 내부 장치·분석 API를 호출할 수 없습니다.

## outbox·장애 복구

감지 저장과 수신자 스냅샷·outbox 삽입은 원자적입니다. 생성 당시 `notification:receive`와 `detection:read`를 가진 구성원만 후보이며, 실제 발송 시 다시 검사합니다. 나중에 가입한 사람에게 과거 알림을 자동 발송하지 않습니다. 권한 저장소 오류 시 해당 트랜잭션은 실패하고 전송하지 않습니다.

실패 시 2, 4, 8, 16초 방식으로 재시도하고 5번째 실패에서 DLQ로 보냅니다. 내부 인증으로 `GET /internal/outbox`에서 대기·실패 건수와 가장 오래된 대기 시각을 확인합니다. 원인 해결 후 `POST /internal/outbox/{id}/retry`로 재등록할 수 있습니다. 중복 전달은 DB 유일 제약으로 제거됩니다. 앱 내 알림 저장은 영상·AI·푸시 성공을 의미하지 않습니다.

Micrometer 메트릭: `aicoo.outbox.published`, `aicoo.outbox.failures`, `aicoo.push.unconfigured`, `aicoo.notification.latency`. 기본 HTTP 노출은 health/info로 제한되어 있으며 관측 백엔드 연결은 운영 설정에서 추가해야 합니다. 메일 장애가 전체 API를 비정상으로 만들지 않도록 SMTP health probe는 기본 제외했습니다.

외부 푸시 공급자·기기 등록 토큰·추가 채널 에스컬레이션은 구현하지 않았습니다. `pushEnabled=true`인 사용자에 대해 현재는 `failed` 기록을 남깁니다. 운영 연결 시 provider ID 기반 멱등 발송·인증·재시도·DLQ·토큰 폐기 로직을 추가하고 `pushDeliveryAvailable` 응답을 실제 상태와 맞추세요.

## 데이터·보존

원본 영상·음성 저장소가 없습니다. 감사 로그는 사용자/공간/대상 ID와 액션만 기록합니다. 애플리케이션 HTTP 요청·응답, SQL 바인딩, 인증 토큰을 DEBUG로 기록하지 마세요. 공유 철회는 접근만 종료하며 개인 기록은 유지합니다.

`GET /privacy/export`는 현재 읽을 수 있는 프로필·최대 10,000개 이벤트·500개 루틴의 메타데이터 내보내기입니다. 전체 이력과 삭제는 `/privacy/requests`로 접수하고 수동 운영 절차에서 처리합니다. 보존 기간은 명세에서 미확정이므로 파괴적인 자동 정리 작업은 넣지 않았습니다. 출시 전에 사용자 동의에 맞는 TTL, 내보내기 범위, 기관 보존 의무, DB/큐/백업 삭제 절차를 확정하세요.

백업은 암호화하여 보관하고 별도 환경에서 Flyway 적용·복원 훈련을 수행해야 합니다. 마이그레이션은 기존 파일 수정 대신 새 버전을 추가합니다. 초기 스키마에는 workspace/child/source/member 복합 외래 키와 이력 조회 인덱스가 포함되어 있습니다.

## 검증 범위

통합 테스트는 실제 PostgreSQL 16에서 실행되며 가입/권한/초대/철회/공유/이벤트/피드백/알림/SSE/스트림 검증 경계/루틴/동시 멱등 요청/복합 FK/DLQ 복구를 다룹니다. 외부 카메라, 추론 모델, SMTP 실제 전송, 푸시 실제 수신, Docker 데몬 배포, 장애 주입 네트워크 시험, 대규모 부하 시험은 별도 환경에서 수행해야 합니다.
