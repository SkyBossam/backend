# 07. 실시간 알림·권한 필터링

## 전달
- 실시간: SSE 또는 WebSocket `/realtime`에 연결 후 서버가 **허용된 아이**만 구독.
- 알림: 앱 내 목록 + 브라우저/모바일 푸시(사용자 동의 필요). 재연결 시 REST 이력 조회.
- 알림 수신자는 생성 당시 유효한 `notification:receive` + `detection:read` 권한 보유자만.
- 권한 회수 시 기존 실시간 구독 종료, 토큰 무효화 및 수신자 대상 재평가.

## 메시지 구조
```json
{
  "messageId":"uuid", "sequence":107,
  "type":"detection.created", "workspaceId":"uuid", "childId":"uuid",
  "eventId":"uuid", "severity":"immediate",
  "occurredAt":"2026-11-01T01:03:00Z"
}
```
내용은 최소화; 상세 설명은 보호된 REST에서 별도 조회. `messageId` 중복 처리.

## 알림별 상태
- `delivery_status = queued | sent | failed`
- `read_at`는 알림 클릭/읽음.
- `acknowledged_at`는 해당 사용자 확인.
- 이벤트 `resolved_at`는 **현장 확인 결과 제출 이후**. 세 가지는 별개.

## 신뢰성
- DB outbox → 큐 → 재시도/지수 백오프 → DLQ.
- 긴급 사건은 푸시 실패·기기 오프라인 때 추가 채널/에스컬레이션 정책 검토.
- 심각도에 따른 순서 보장 및 중복 제거.
- 긴급 알림 완전 음소거 허용 여부는 정책 미확정. 안전 검토 전까지 서버의 중요한 경고를 완전 차단하는 기능을 제공하지 않음.
