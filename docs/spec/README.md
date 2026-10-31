# BabySense 백엔드 설계 명세 v1.0
기준일: 2026-11-01

## 확정 범위
- **개인 보호자와 기관을 모두 지원**하고, 시터/직원 초대 및 **아이별 세부 정보 공유 권한**을 지원한다.
- 기존 프론트 4개 탭(홈, 우리 아이, 모니터링, 더보기) 및 울음 대응 흐름과 호환된다.
- 카메라 **연결 온보딩 화면은 제외**하되 실시간 모니터링과 연결 상태 확인은 유지한다.
- 별도 수유 기록 기능은 두지 않는다. `hunger`는 울음의 **원인 후보**일 뿐이다.
- 얼굴 가림·낙상 위험 감지는 보조 알림이며 질식 여부나 의료 상태를 확정하지 않는다.
- 알림 `acknowledged`(읽고 확인)와 `resolved`(실제 점검 결과 기록)는 서로 다르다.

## 추천 기술 스택(변경 가능)
- API: Spring Boot 3 + Java 21, REST/JSON, Spring Security
- DB: PostgreSQL 16+, 마이그레이션 Flyway
- 실시간: WebSocket 또는 SSE + Redis Pub/Sub, 재연결 시 REST 재동기화
- 백그라운드: 메시지 큐(RabbitMQ 등) + Python 추론 워커
- 영상: 별도 WebRTC/미디어 서버, API는 짧은 수명의 접근 토큰만 발급
- 객체 저장: 민감 영상/이미지는 기본 미보관, 보관 시 암호화 객체 저장소
- 로깅/운영: OpenTelemetry, 구조화 로그(개인정보 마스킹), 메트릭/알림

## 문서
| 파일 | 내용 |
|---|---|
| 01-architecture.md | 서비스 경계, 멀티테넌시, 데이터 흐름 |
| 02-erd-schema.md | DB 테이블, 관계, 제약조건, 인덱스 |
| 03-auth-permissions.md | 인증, 초대, RBAC+리소스 범위 권한 |
| 04-api-contract.md | REST API, 응답, 오류, 멱등성 |
| 05-detection-and-monitor.md | 감지 모델, 모니터링 상태, 이벤트 라이프사이클 |
| 06-cry-and-recommendation.md | 울음 분석·추천·행동·반응 기록 |
| 07-notifications-realtime.md | 실시간 전파, 알림, 누락 대응 |
| 08-routines-dashboard.md | 루틴, 요약, 대시보드 우선순위 |
| 09-security-privacy.md | 아동 영상/데이터 보호, 감사, 보관 |
| 10-deployment-operations.md | 배포·확장·장애 복구 |
| 11-test-and-migration.md | 인수 테스트, 단계별 개발 |
| 12-example-sql.md | 핵심 테이블 PostgreSQL DDL 예제 |

## 핵심 도메인
User → Membership → Workspace(personal / institution) → ChildAssignment → Child.
아이 접근은 **워크스페이스의 멤버라는 이유만으로 자동 허용되지 않는다.**
ChildAssignment와 세부 권한(capabilities)이 모두 충족되어야 한다.

### MVP 권장 범위
1. 개인 워크스페이스 + 아이/멤버/초대 + 세부 권한
2. 루틴, 이벤트/위험 확인, 울음 추천/피드백, 홈 대시보드
3. 기관 워크스페이스 + 직원/그룹 정책 + 실시간 알림 라우팅
4. WebRTC/센서·추론 워커 고도화

### 확정이 필요한 제품 정책
- 개인 공간 내 소유자 외 공동 보호자 초대 허용 여부
- 시터별 허용 기간과 어떤 정보를 기본 공유할지
- 기관에서 부모의 아이 프로필을 연결할 때 동의·철회 절차
- 영상/이벤트 증거의 보관 기간 및 촬영에 대한 법적 동의
- 기관이 아동 상태를 공유할 때 역할별 정보 최소화
