# 05. 안전 감지 및 실시간 모니터링

## 감지 유형
`cry | face_covered | edge_proximity | movement | camera_obstructed | connection_lost`.
AI 결과는 `observation`으로 취급; 실제 질식/낙상/의학적 문제 확정 진단 금지.

## 이벤트 상태
- `new`: 새 감지
- `acknowledged`: 적어도 한 사용자가 알림을 확인; **해결 아님**
- `checking`: 보호자가 현장 확인 중
- `resolved`: 적격 권한자가 현장 점검 결과 제출
- `unverifiable`: 영상 가림·연결 끊김·판정 불가

이벤트 자동 종료 시 `ended_at` 설정할 수 있으나 `resolved`와 동일하지 않다.
알림별 `acknowledged`는 개별 사용자 테이블에 저장하며 이벤트 전체 상태와 분리한다.

## 안전 판단
- 감지기의 신뢰도가 낮으면 `unknown/unverifiable`로 노출.
- 영상 프레임 손실, 실시간 스트림 단절, 센서 미연결은 정상으로 간주하지 않음.
- 위험이 높은 경우 알림 푸시와 앱 내 경고를 전송; 소리 설정은 영상 오디오와 분리.
- 빈번한 감지는 윈도우 기반 그룹화 및 디바운싱, 그러나 실제 지속 위험을 무조건 억제하지 않음.
- 생체징후·의료 진단을 암시하는 텍스트 금지.
- 카메라 연결 설정 페이지는 없음. 내부 장치 관리 및 스트림 메타데이터는 서버에 존재.

## 모니터링 응답 예시
```json
{
  "childId":"uuid", "streamStatus":"unverifiable", "streamUrl":null,
  "safety":{
    "posture":"unknown","face":"unknown","edge":"unknown","objects":"unknown"
  },
  "sensors":[{"type":"temperature","connected":false,"unit":"°C","value":null}]
}
```
스트림 URL은 정적 공개 URL을 반환하지 않고 별도의 접근 토큰을 사용한다.

## 권고
안전한 수면 환경 점검은 근거 기반 체크리스트. 베개나 느슨한 이불·자세 고정 장치를 추천하지 않는다.
