# Sky Eye → 아이쿠 로컬 연결

2026-11-01 통합 패키지의 `sky-eye.status.v1` / `sky-eye.event.v1` 파일 인터페이스용 전송기입니다. Pi 4 / ARMv7 / Python 3.9에서 사용할 수 있는 표준 라이브러리와 기존 Ed25519 인증만 사용합니다. 모델, NCNN, TFLite, 개인 프로필을 재설치하거나 업로드하지 않습니다.

## 권장: 파이에서 명령 하나로 실행

이미 저장소를 받았다면 한 번 업데이트합니다.

```bash
cd ~/backend
git pull --ff-only
bash raspberry-pi/start-aicoo.sh
```

다음부터는 `bash ~/backend/raspberry-pi/start-aicoo.sh`만 실행합니다.

1. 기존 `~/.local/share/aicoo/server.json`의 서버 주소와 장기 기기 키를 읽습니다. 이전에 코드 승인을 완료했다면 재입력 없이 인증을 복구합니다.
2. 주소가 없는 최초 실행만 PC 주소를 한 번 물어봅니다. 자동 실행용 설정은 `~/.config/aicoo/launcher.json`에 저장합니다. 매번 export할 필요가 없습니다.
3. PC 백엔드에 연결될 때까지 기다립니다. 처음 등록하는 기기는 코드를 표시하고 PC 앱에서 승인을 기다린 뒤 **같은 실행 안에서** 다음 단계로 진행합니다.
4. 설치된 카메라·울음·통합 출력 서비스를 시작합니다. 이미 실행 중인 모델은 재시작하지 않으며, 선택된 objects 모델도 임의로 바꾸지 않습니다.
5. 분석 데이터·이벤트·피드백과 **최대 2fps의 카메라 미리보기**를 전송합니다. Ctrl+C로 전송을 멈춥니다. 모델 서비스 자체는 계속 실행됩니다.

Python 인증 패키지가 없으면 실행 스크립트가 apt 설치를 진행합니다. `sudo bash`로 실행하지 말고 모델을 설치한 Pi 사용자로 실행하세요. PC에서는 백엔드 `start-local.ps1 -Lan`과 프론트가 실행 중이어야 합니다. 이 명령이 꺼진 PC를 켜거나 방화벽을 변경하지는 않습니다.

연결 코드 승인은 완료했는데 기기 목록이 대기 상태라면, 아직 파이 전송 프로그램이 실행되지 않은 것입니다. 기기를 삭제하거나 다시 등록하지 않고 위 실행 명령으로 이어갑니다. 기존 `aicoo_device.py run`, `sky_eye_bridge.py`, `aicoo-sky-eye` 서비스가 있다면 먼저 중지하고 하나만 실행하세요.

```bash
# 기본 설치 위치가 다른 경우 (설정은 다음 실행에 재사용)
bash ~/backend/raspberry-pi/start-aicoo.sh --sky-eye-dir /실제/모델/폴더
# 별도 방식으로 이미 모델을 실행 중인 경우
bash ~/backend/raspberry-pi/start-aicoo.sh --no-start-model
# 영상 없이 데이터만 보낼 경우
bash ~/backend/raspberry-pi/start-aicoo.sh --no-camera
```

## 카메라 미리보기

기존 Sky Eye가 제공하는 파이 루프백 `127.0.0.1:8765/api/status`와 `/frame.jpg`를 읽습니다. 카메라 장치를 새로 열지 않으므로 추론 프로그램과 충돌하지 않습니다. 프레임은 기기 인증으로 PC에 보내며, 앱은 사용자 로그인과 해당 아이의 `monitor:live` 권한으로만 가져옵니다. 공개 영상 URL이나 별도 SSH 터널은 필요하지 않습니다.

프레임은 서버 메모리에 최신 한 장만 보관하고 디스크에 녹화하지 않습니다. 원본 시각과 미리보기 나이를 검사하며 오래된 영상은 다시 현재 시각으로 찍어 보내지 않습니다. 서버는 3초 지난 영상을 반환하지 않고, 앱도 새 응답이 끊기면 마지막 화면을 지웁니다. 영상이 보여도 AI가 아이의 자세·얼굴을 판단하지 못하면 해당 분석 상태는 확인 불가일 수 있습니다. 음성 재생은 포함하지 않습니다.

## 연결 해제 후 백업하고 새로 시작

연결을 해제하고 다시 승인하면 기기 번호가 달라집니다. `Child/device binding changed`는 이전 아이의 학습·감지 기록을 새 연결로 보내지 않기 위한 차단입니다. 기존 기록을 파이에 백업하고 빈 개인화 프로필로 시작하려면 다음 두 명령을 **각각 한 줄씩** 실행합니다.

```bash
git -C ~/backend pull --ff-only
bash ~/backend/raspberry-pi/start-aicoo.sh --fresh-profile
```

이미 승인했다면 코드를 다시 입력하지 않습니다. 아직 승인하지 않았다면 표시된 코드를 PC의 아이쿠 연결하기에서 승인합니다. 설치된 사용자 모델 서비스를 모두 중지한 뒤 감지 로그, 통합 출력, 개인화 프로필, 피드백 대기열·영수증과 이전 전송 상태를 `~/sky-eye/aicoo-backups/날짜-고유번호/`에 옮겨 보관합니다. 출력기도 중지하여 이전 로그가 다시 전송되지 않도록 합니다. 모델 가중치·카메라 설정·장기 기기 키는 유지하고 모델과 전송을 다시 시작합니다. 새 개인화 학습은 0건부터 시작하며 서버의 이전 계정 기록은 변경하지 않습니다.

이 절차는 제공된 통합 모델의 기본 서비스·데이터 경로용입니다. 다른 경로로 수정한 설치나 연결된 데이터 디렉터리는 자동 변경하지 않습니다. 중간에 중단되면 같은 `--fresh-profile` 명령으로 이어서 진행하며, 미완료 상태에서는 전송하지 않습니다. 정상 완료 후 같은 명령을 다시 입력해도 현재 연결의 새 기록은 초기화하지 않습니다. 백업은 비공개로 보관하고 Git에 올리지 마세요.

`Invalid isoformat string`에 소수점 9자리 시각이 표시되면 위 `git pull`로 Python 3.9 날짜 호환성 수정도 적용됩니다. 승인 후 종료됐어도 장기 키로 연결을 복구하므로 다시 해제할 필요가 없습니다.

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

아이쿠의 **모니터링**에서 자세·얼굴 관측, 울음 후보, 실험적 돌봄 힌트, 최근 30개 관측 이벤트를 확인합니다. 홈의 안전 상태도 실제 기기 분석으로 갱신합니다. 기존 체험 모드 및 앱의 수동 기록과 기기 기록은 구분됩니다. 현재 연결은 **분석 데이터와 카메라 미리보기**입니다. 기존 파이 자체 미리보기 화면을 따로 열고 싶을 때만 문서의 SSH 터널을 사용합니다.

## 보호자 피드백

모니터링의 정상 종료된 울음 아래에서 실제로 한 행동을 고르고 **실제 행동 기록하기**를 누릅니다. 앱은 `전달 중`으로 표시합니다. 전송기는 기기 인증으로 명령을 받아 `input/cry_commands/<id>.json`에 원자적으로 기록하고, 모델의 `input/cry_receipts/<id>.json`에서 `ok`를 확인한 뒤에만 `파이 반영 완료`로 바꿉니다. 원본 소리나 임베딩을 서버로 보내지 않습니다.

- `feed/diaper/hold/unknown/self`만 전송합니다. `unknown/self`는 학습 10건에 포함하지 않습니다.
- 실제 피드백 10건 **및 현재 울음 창 10개**와 모델의 ready 조건을 모두 충족할 때 추천이 표시됩니다. 점수는 상대 가중치입니다.
- 관찰 중단 구간에는 피드백을 제공하지 않습니다. 다른 답으로 기존 피드백을 덮어쓰지 않습니다.
- 명령은 5분 유효합니다. 만료/실패가 나오면 파이 연결과 울음 서비스를 확인하고 파이 미리보기에서 해당 기록을 확인하세요. 큐 접수만으로 학습 완료를 표시하지 않습니다.
- `cry-cause-demo.sh`의 시뮬레이션은 실제 추천·기록에 섞이지 않습니다.

## 재부팅 자동 실행 (선택)

제공된 `aicoo-sky-eye.service`는 **사용자 서비스**입니다. ExecStart의 저장소 위치를 실제 경로에 맞추세요. 기본 예시는 `~/backend`이며 위 실행 스크립트가 저장한 설정을 사용합니다. 기존 `bridge.env` 설정도 있으면 읽습니다.

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
- 원본 video monotonic 시각은 대응 UNIX 시각으로 변환합니다. risk_ended와 risk_interrupted를 보호자의 위험 해소로 처리하지 않습니다. 과거 로그는 기록으로 저장하고 새 푸시 알림을 발송하지 않습니다. 앱 알림 목록에는 30초 이내의 신규 시작 관측만 잠시 표시하며 모바일 백그라운드 푸시는 이 연결에 포함되지 않습니다. 카메라 미리보기는 앱을 열었을 때만 조회합니다.
- 다른 아이로 재등록하면 같은 출력/개인화 폴더를 그대로 재사용하지 못하도록 차단합니다. `--fresh-profile`로 이전 기록을 백업하고 새로 시작하거나, 별도의 새 모델 배포 폴더를 `--sky-eye-dir`로 지정합니다. 이전 아이의 파일을 새 폴더에 복사하거나 기기 키·커서를 지워서 이 검사를 우회하지 않습니다.
- 상태/커서/피드백 영수증은 소유자 전용 상태 폴더에 저장합니다. Git에 넣지 않습니다. PC 주소가 바뀌면 자동으로 다른 서버를 신뢰하지 않으며 기존 `server.json` 바인딩을 확인해야 합니다.

```bash
python3 -m unittest discover -s raspberry-pi -p 'test_*.py'
# PC의 localhost:8080 개발 서버에만 가상 계정과 아이를 생성하는 선택 테스트
python3 raspberry-pi/smoke_bridge_local.py
python3 raspberry-pi/smoke_camera_local.py  # 검정 테스트 JPEG, 실제 카메라 영상 사용 안 함
```

API: `POST /api/v1/device/sky-eye/status`, `POST /events`, `GET /commands`, `POST /commands/{id}/receipt` (모두 같은 `/api/v1/device/sky-eye` 접두사). 기기 토큰으로 소유 아이를 결정합니다. 앱은 사용자 JWT + workspace 헤더로 `GET /api/v1/children/{id}/sky-eye`, `POST /api/v1/children/{id}/sky-eye/feedback`을 호출합니다. 전체 상태 조회는 monitor:live, cry:read, detection:read 권한이 모두 필요하며 피드백은 cry:feedback 및 이메일 인증이 필요합니다.

영상 API: `POST /api/v1/device/camera/frame` (X-Device-Token, X-Frame-Captured-At, image/jpeg, 최대 512 KiB/1920×1080), `GET /api/v1/children/{child}/devices/{device}/camera/frame` (사용자 인증). 새 영상이 없으면 204입니다. 서버는 임의의 파이 URL로 접속하지 않습니다.
