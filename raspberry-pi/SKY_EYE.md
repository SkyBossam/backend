# Sky Eye → 아이쿠 로컬 연결

2026-11-01 통합 패키지의 `sky-eye.status.v1` / `sky-eye.event.v1` 파일 인터페이스용 전송기입니다. Pi 4 / ARMv7 / Python 3.9에서 사용할 수 있는 표준 라이브러리와 기존 Ed25519 인증만 사용합니다. 모델, NCNN, TFLite, 개인 프로필을 재설치하거나 업로드하지 않습니다.

## 최초 연결

PC에서 백엔드와 프론트엔드를 실행합니다.

```powershell
# backend 폴더, 창 유지. 기존 8080 서버가 있으면 그 서버부터 종료합니다.
.\scripts\start-local.ps1 -Lan
# frontend 폴더, 별도 창
npm.cmd run dev -- --port 5173 --strictPort
```

PC와 파이가 서로 통신 가능한 네트워크에 있어야 합니다. PC의 `ipconfig`에서 파이와 같은 네트워크의 IPv4를 확인하세요. 첨부 문서의 `172.20.10.2`는 **파이 주소**이며 전송 대상 PC 주소가 아닙니다. Wi-Fi 변경 시 PC 주소도 달라집니다. 먼저 아래 health 요청이 성공해야 연결할 수 있습니다.

파이에서 (이미 clone했다면 clone 대신 해당 폴더에서 `git pull --ff-only`):

```bash
sudo apt install -y git curl python3-cryptography
git clone https://github.com/SkyBossam/backend.git ~/backend
cd ~/backend
git pull --ff-only
export AICOO_API_URL=http://PC의_IP주소:8080
curl --connect-timeout 5 "$AICOO_API_URL/actuator/health"
python3 raspberry-pi/aicoo_device.py pair --allow-http
```

PC의 `http://localhost:5173`에서 로그인 → **아이쿠 연결하기** → 코드 입력 → 아이 선택 → 승인합니다. 이미 같은 계정의 아이에 연결된 상태라면 `pair`는 생략합니다. 기본 상태 폴더 `~/.local/share/aicoo`를 그대로 사용해야 기존 키를 인식합니다.

## 모델 전송 실행

기존 `aicoo_device.py run` 또는 `aicoo-device` 서비스가 실행 중이면 먼저 중지하세요. 동일 상태 폴더에 두 에이전트를 실행하면 파일 잠금이 차단합니다. `sky-eye`, `sky-eye-cry`, `sky-eye-export`는 계속 실행합니다.

```bash
bash ~/sky-eye/start-all.sh
cd ~/backend
python3 raspberry-pi/sky_eye_bridge.py --allow-http --once  # 한 번 점검
python3 raspberry-pi/sky_eye_bridge.py --allow-http         # 창 유지, 계속 전송
```

기본 입력은 `~/sky-eye/output/status.json`, `~/sky-eye/output/events.jsonl`입니다. 다르면 `--sky-eye-dir /실제/sky-eye/폴더`를 지정합니다. Windows PC에서 이 전송기를 실행하는 것이 아니라 **파일이 있는 파이에서** 실행합니다. HTTPS 운영 서버는 `--allow-http`를 생략합니다.

아이쿠의 **모니터링**에서 자세·얼굴 관측, 울음 후보, 실험적 돌봄 힌트, 최근 30개 관측 이벤트를 확인합니다. 홈의 안전 상태도 실제 기기 분석으로 갱신합니다. 기존 체험 모드 및 앱의 수동 기록과 기기 기록은 구분됩니다. 현재 연결은 **분석 데이터**입니다. 영상 재생은 구현하지 않았으며 기존 파이 미리보기는 문서의 SSH 터널 `ssh -L 8765:127.0.0.1:8765 pi@파이_IP`로 따로 볼 수 있습니다.

## 보호자 피드백

모니터링의 정상 종료된 울음 아래에서 실제로 한 행동을 고르고 **실제 행동 기록하기**를 누릅니다. 앱은 `전달 중`으로 표시합니다. 전송기는 기기 인증으로 명령을 받아 `input/cry_commands/<id>.json`에 원자적으로 기록하고, 모델의 `input/cry_receipts/<id>.json`에서 `ok`를 확인한 뒤에만 `파이 반영 완료`로 바꿉니다. 원본 소리나 임베딩을 서버로 보내지 않습니다.

- `feed/diaper/hold/unknown/self`만 전송합니다. `unknown/self`는 학습 10건에 포함하지 않습니다.
- 실제 피드백 10건 **및 현재 울음 창 10개**와 모델의 ready 조건을 모두 충족할 때 추천이 표시됩니다. 점수는 상대 가중치입니다.
- 관찰 중단 구간에는 피드백을 제공하지 않습니다. 다른 답으로 기존 피드백을 덮어쓰지 않습니다.
- 명령은 5분 유효합니다. 만료/실패가 나오면 파이 연결과 울음 서비스를 확인하고 파이 미리보기에서 해당 기록을 확인하세요. 큐 접수만으로 학습 완료를 표시하지 않습니다.
- `cry-cause-demo.sh`의 시뮬레이션은 실제 추천·기록에 섞이지 않습니다.

## 재부팅 자동 실행 (선택)

제공된 `aicoo-sky-eye.service`는 **사용자 서비스**입니다. ExecStart의 저장소 위치를 실제 경로에 맞추세요. 기본 예시는 `~/backend`와 로컬 HTTP입니다.

```bash
mkdir -p ~/.config/aicoo ~/.config/systemd/user
chmod 700 ~/.config/aicoo
printf 'AICOO_API_URL=%s\nSKY_EYE_DIR=%s\n' "$AICOO_API_URL" "$HOME/sky-eye" > ~/.config/aicoo/bridge.env
chmod 600 ~/.config/aicoo/bridge.env
cp ~/backend/raspberry-pi/aicoo-sky-eye.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now aicoo-sky-eye
journalctl --user -u aicoo-sky-eye -f
```

터미널의 전송기를 Ctrl+C로 중지한 뒤 서비스를 시작합니다. 부팅 후 로그인 전부터 사용자 서비스를 실행하려면 Pi의 사용자 linger 설정이 필요합니다. 기존 설치의 실행 설정에 맞춰 적용하세요. 카메라·마이크 서비스의 부팅 설정은 이 서비스가 변경하지 않습니다.

## 처리 규칙과 점검

- 상태 약 2초 전송, 앱 약 2.5초 조회. 파일 시각뿐 아니라 각 입력의 UNIX 시각을 검사합니다. 중단·오래된 파일·낮은 신뢰도는 확인 불가입니다. 입력별로 만료하며 앱도 마지막 응답을 계속 정상으로 표시하지 않습니다.
- 자세/얼굴의 confidence 0.6 이상만 정상/위험 상태에 반영합니다. 주변 물체 정책이 없으면 unknown, 침대 영역 미보정은 가장자리 unknown입니다. Pi CPU 온도를 실내 온도로 사용하지 않습니다.
- 줄 끝의 개행이 도착하기 전에는 이벤트를 읽지 않습니다. 서버 확인 후 커서를 저장합니다. `(deviceId, id)`로 멱등 처리하며, 파일 회전/잘림도 감지합니다. 영구적으로 잘못된 이벤트는 최근 100개까지 `bridge-errors.json`에 내용 없는 진단만 남깁니다.
- 원본 video monotonic 시각은 대응 UNIX 시각으로 변환합니다. risk_ended와 risk_interrupted를 보호자의 위험 해소로 처리하지 않습니다. 과거 로그는 기록으로 저장하고 새 푸시 알림을 발송하지 않습니다. 앱 알림 목록에는 30초 이내의 신규 시작 관측만 잠시 표시하며 모바일 백그라운드 푸시는 이 연결에 포함되지 않습니다.
- 다른 아이로 재등록하면 같은 출력/개인화 폴더를 재사용하지 못하도록 차단합니다. 새 모델 배포 폴더와 빈 개인 프로필을 준비하고 `--sky-eye-dir`로 명시해야 합니다. 이전 아이의 파일을 새 폴더에 복사하지 마세요. 등록 키나 커서를 지우는 방식으로 이 검사를 우회하지 않습니다.
- 상태/커서/피드백 영수증은 소유자 전용 상태 폴더에 저장합니다. Git에 넣지 않습니다. PC 주소가 바뀌면 자동으로 다른 서버를 신뢰하지 않으며 기존 `server.json` 바인딩을 확인해야 합니다.

```bash
python3 -m unittest discover -s raspberry-pi -p 'test_*.py'
# PC의 localhost:8080 개발 서버에만 가상 계정과 아이를 생성하는 선택 테스트
python3 raspberry-pi/smoke_bridge_local.py
```

API: `POST /api/v1/device/sky-eye/status`, `POST /events`, `GET /commands`, `POST /commands/{id}/receipt` (모두 같은 `/api/v1/device/sky-eye` 접두사). 기기 토큰으로 소유 아이를 결정합니다. 앱은 사용자 JWT + workspace 헤더로 `GET /api/v1/children/{id}/sky-eye`, `POST /api/v1/children/{id}/sky-eye/feedback`을 호출합니다. 전체 상태 조회는 monitor:live, cry:read, detection:read 권한이 모두 필요하며 피드백은 cry:feedback 및 이메일 인증이 필요합니다.
