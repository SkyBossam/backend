# 08. 홈·루틴·하루 요약

## 루틴
- 종류: `sleep | wake | play | soothe | custom`.
- 상태: `todo | in_progress | done`.
- CRUD는 `routine:write` 권한 필요. 날짜 및 사용자 시간대를 별도로 처리.
- 반복 루틴이 필요하면 템플릿(`routine_templates`)을 확장; 첫 버전은 단일 일정 우선.

## 홈 데이터 조합
`GET /dashboard` 서버가 접근 가능한 아이만 가져와 위험 수준을 기준으로 정렬한다:
`immediate > caution > unknown > stable`, 동일 상태는 최근 감지 시각 기준.
기본 헤드라인은 해당 아이의 가장 중요한 미해결 이벤트를 사용.
- `immediate`: '지금 확인해 주세요' + 다음 행동 버튼.
- `unknown`: '현재 확인할 수 없어요' (안정 금지).
- `stable`: 미해결 위험이 없을 때만 중립 메시지.
- 우선순위와 CTA는 서버에서 결정하고 프론트는 표시.

## 울음 타임라인
`발생 → 추천 행동 → 시도 여부 → 반응` 순서를 이벤트/피드백으로 결합.
추천만 봤다고 '시도함'으로 표시하지 않는다.

## 요약
해당 날짜의 울음 횟수, 평균 지속시간, 위험 알림, 루틴 완료 수, 추천 행동 실행/효과 정도.
**데이터 부족을 0회 또는 정상으로 오인하지 않게** `hasData`와 수집 구간 제공.

## API 샘플
```json
{
  "workspaceId":"uuid",
  "children":[{
    "childId":"uuid","displayName":"지안","severity":"caution",
    "headline":"얼굴 주변 물체가 감지되었어요. 지금 확인해 주세요.",
    "lastDetectedAt":"2026-11-01T00:44:00Z",
    "unacknowledgedCount":1,"nextAction":{"label":"확인하러 가기","eventId":"uuid"}
  }]
}
```
