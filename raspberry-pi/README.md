# Sky Eye 통합 모델 연결

실제 통합 패키지의 `status.json`·`events.jsonl` 전송과 보호자 피드백은 **[SKY_EYE.md](SKY_EYE.md)**를 따르세요. 권장 실행은 `bash ~/backend/raspberry-pi/start-aicoo.sh` 하나입니다. 서버 주소 저장, 최초 등록/기존 연결 복구, 모델 실행, 분석·영상 전송을 이어서 처리합니다. 아래 `run`은 연결 확인용입니다.

항상 켜 둔 파이에서 재부팅·터미널 종료 후에도 자동 대기하려면 `bash ~/backend/raspberry-pi/start-aicoo.sh --install-service`로 등록합니다. 계정 연결 해제 후 기존 기록을 백업하고 새로 연결할 때는 `--fresh-profile --install-service`를 함께 사용합니다. 날짜 소수점 9자리 때문에 Python 3.9에서 발생하던 `Invalid isoformat string` 오류도 최신 코드에서 수정했습니다.

# 아이쿠 파이 연결

Raspberry Pi 4B에서 보호자 계정과 아이를 연결하는 프로그램입니다. 카메라·USB 헤드셋 드라이버 설정과 영상 송출, AI 추론은 기존 프로그램에서 담당합니다. 이 프로그램은 기기 등록·인증·연결 상태 전송을 담당하며, 분석 결과가 없으면 `unverifiable`를 전송합니다.

## 준비

Python 3.9 이상과 Ed25519를 지원하는 `cryptography`가 필요합니다. Raspberry Pi OS에서는 시스템 패키지를 사용할 수 있습니다.

```bash
sudo apt update
sudo apt install python3-cryptography python3-qrcode
git clone https://github.com/SkyBossam/backend.git
cd backend/raspberry-pi
```

`qrcode`는 터미널 QR 출력용이며 없어도 코드 입력과 링크로 연결됩니다. 가상환경을 사용한다면 `python3 -m pip install -r requirements.txt`로 설치하세요.

서버를 실행하고 다음 주소를 설정합니다.

- 백엔드 `FRONTEND_URL`: 보호자가 여는 프론트 주소. QR 링크는 `/devices#code=...`로 생성됩니다.
- 프론트 `VITE_API_BASE_URL`: 백엔드 origin. `/api/v1`은 붙이지 않습니다. 빈 값이면 같은 origin을 사용합니다.
- 백엔드 `CORS_ORIGINS`: 프론트의 정확한 origin.
- 파이 `AICOO_API_URL`: 파이에서 접근 가능한 백엔드 origin.

아래 HTTPS 예시 도메인은 실제 배포 주소로 바꿔 주세요. 로컬에서는 아래 `-Lan` 방식으로 연결할 수 있습니다. Windows 개발 서버는 기본적으로 `127.0.0.1`에만 바인딩하므로 기본 실행 상태에서는 파이에서 PC의 IP만 입력해 접근할 수 없습니다. 파이의 `localhost`는 파이 자신을 가리킵니다.

## PC와 파이를 같은 네트워크에서 연결하기

PC와 파이를 같은 공유기나 핫스팟에 연결합니다. Windows PC의 백엔드 폴더에서 기존 서버를 종료한 뒤 실행합니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Lan
```

PC에서는 프론트도 실행한 뒤 `http://127.0.0.1:5173/login`으로 접속합니다. 서버와 프론트를 실행한 상태로 유지하세요. `ipconfig`의 Wi-Fi/Ethernet IPv4 주소를 확인하고 아래 예시 `192.168.1.10`을 해당 주소로 바꿉니다. IP는 네트워크를 바꾸면 달라질 수 있습니다.

파이에서:

```bash
export AICOO_API_URL=http://192.168.1.10:8080
curl --connect-timeout 5 "$AICOO_API_URL/actuator/health"
# {"status":"UP"} 확인 후, backend/raspberry-pi 폴더에서:
python3 aicoo_device.py pair --allow-http
```

파이에 표시된 코드를 **PC의 앱 → 로그인 → 모니터링/더보기 → 아이쿠 연결하기**에서 입력·승인합니다. 이메일 인증을 요구하면 로컬 개발용 코드로 인증합니다. 파이에서 `Paired!`를 확인한 뒤:

```bash
python3 aicoo_device.py run --allow-http
```

이 모드의 QR 링크는 기본적으로 PC에서 여는 `localhost:5173` 주소입니다. 휴대폰으로 스캔하는 대신 PC 화면에서 코드를 입력하세요. 휴대폰 접속에는 프론트의 LAN 바인딩과 `FRONTEND_URL`, `CORS_ORIGINS`도 별도로 설정해야 합니다.

`curl`이 실패하면 PC 서버의 `-Lan` 실행 여부, Windows 방화벽의 TCP 8080 인바운드 허용, 같은 네트워크의 장치 간 통신 허용을 확인하세요. 방화벽 규칙이 필요하다면 파이 IP 또는 로컬 서브넷으로 범위를 한정합니다. 공유기 인터넷 포트포워딩은 필요하지 않습니다. `--allow-http`는 신뢰하는 개발망 테스트용입니다.

## 최초 연결

```bash
export AICOO_API_URL=https://api.your-aicoo-domain.example
python3 aicoo_device.py pair
```

1. 파이 터미널에 **8자리 연결 코드**, **기기 확인값**, **QR/링크**가 표시됩니다.
2. 보호자가 앱에 로그인한 뒤 **모니터링 → 아이쿠 연결하기** 또는 **더보기 → 아이쿠 연결하기**를 엽니다.
3. 아이쿠 계정 로그인·이메일 인증 후 아이를 선택합니다. 아이가 없으면 등록합니다.
4. 코드를 입력하고 파이에 표시된 기기 확인값을 대조한 뒤 **이 아이에게 연결**을 누릅니다.
5. 파이에서 `Paired!`가 표시되면 실행합니다.

```bash
python3 aicoo_device.py run
```

10초마다 서버에 연결 상태를 알립니다. 프론트는 5초마다 상태를 조회하며 30초 동안 응답이 없으면 오프라인으로 표시합니다. 온라인은 기기 통신 상태이고 아이의 안전 판단이나 영상 재생 가능 여부를 뜻하지 않습니다.

코드는 5분 후 만료됩니다. 만료되면 `pair`를 다시 실행합니다. 승인 후 응답을 놓쳤다면 `run` 또는 `status`가 저장된 기기 키로 복구합니다. 승인 대기 중 종료했다면 같은 `pair` 명령으로 만료 전까지 이어서 기다립니다.

```bash
python3 aicoo_device.py status      # 재인증 후 연결된 아이·기기 정보 확인
python3 aicoo_device.py run --once  # 재인증 및 heartbeat 1회 검증
```

신뢰할 수 있는 개발망의 HTTP 주소를 명시적으로 사용하는 경우에만 `--allow-http`를 추가합니다. TLS 인증서 검증을 끄는 옵션은 없습니다.

## 재부팅과 연결 해제

- 기본 상태 폴더는 `~/.local/share/aicoo`입니다. 같은 Linux 사용자와 같은 상태 폴더로 `run`을 실행하면 재등록이 필요 없습니다.
- `identity.pem`은 장기 기기 개인 키이고, `credential.json`은 회전되는 기기 토큰입니다. 소유자 전용 권한으로 저장하며 브라우저로 전달하지 않습니다. Git에 넣거나 다른 파이로 복제하지 마세요.
- 보호자의 앱 로그아웃과 파이 연결은 독립적입니다. **기기 연결 → 연결 해제**를 누르면 기기 토큰·영상 접근 권한이 폐기됩니다. 이전 기록은 원래 아이에게 남습니다.
- 기존 보호자가 앱에서 해제하거나, 등록 키가 남아 있는 파이에서 `bash ~/backend/raspberry-pi/start-aicoo.sh --unlink`로 해제할 수 있습니다. 서버 확인 후 새 코드를 받으려면 `bash ~/backend/raspberry-pi/start-aicoo.sh --fresh-profile --install-service`를 실행합니다. 기존 모델 기록은 백업하고 새 기기 등록으로 연결합니다.
- 소프트웨어 키를 이용하는 구조입니다. SD 카드 초기화로 키를 지우면 동일한 물리적 파이임을 판별할 수 없습니다. 키를 지우는 방식은 정상적인 연결 해제 방법이 아닙니다.
- `status`와 `run`은 토큰을 회전하므로 같은 파이에서 인증 에이전트를 여러 개 동시에 실행하지 마세요.

## 재부팅 자동 실행 (선택)

`aicoo-device.service`를 제공했습니다. 파일의 `User`를 실제 파이 로그인 사용자로 바꾸고, 코드를 `/opt/aicoo/raspberry-pi/`에 설치합니다. `AICOO_STATE_DIR`를 `/var/lib/aicoo`로 지정해 **최초 pair부터 같은 경로**를 사용하세요.

```bash
sudo install -d -m 700 -o "$USER" -g "$(id -gn)" /var/lib/aicoo
export AICOO_STATE_DIR=/var/lib/aicoo
python3 /opt/aicoo/raspberry-pi/aicoo_device.py pair
```

`/etc/aicoo-device.env` 내용(실제 주소로 교체):

```ini
AICOO_API_URL=https://api.your-aicoo-domain.example
AICOO_STATE_DIR=/var/lib/aicoo
PYTHONUNBUFFERED=1
```

```bash
sudo install -m 644 aicoo-device.service /etc/systemd/system/aicoo-device.service
sudo systemctl daemon-reload
sudo systemctl enable --now aicoo-device
journalctl -u aicoo-device -f
```

키를 복사하거나 이동할 때는 자동 실행을 멈추고 파일 소유권·권한을 유지하세요. 다른 서버를 사용하려면 별도의 상태 폴더가 필요합니다.

## 기존 카메라·AI 프로그램과 통합

`Agent`를 import해 한 프로세스에서 인증하고 `agent.request(path, body, device=True)`를 호출할 수 있습니다. 기존 AI 루프에 통합할 때는 별도 heartbeat 서비스 대신 그 프로세스가 상태를 보고하게 하세요.

| 경로 | 용도 |
|---|---|
| `GET /api/v1/device/me` | 연결된 기기·아이 확인 |
| `POST /api/v1/device/heartbeat` | `observedAt`, `status`, 실제 분석 결과가 있을 때만 `safety`, `confidence`, `analysisObservedAt` |
| `POST /api/v1/device/readings` | 실제 센서의 `type`, `value`, `observedAt` |
| `POST /api/v1/device/detections` | 감지 이벤트. 필드는 Swagger의 DeviceController 참고 |

인증 헤더는 `X-Device-Token`입니다. 파이가 보낸 workspace/child/source 식별자는 신뢰하지 않고 서버가 기기 등록 정보로 결정합니다. 전역 `WORKER_KEY`를 파이에 배포하지 않습니다.

## 검증

```bash
python3 -m unittest discover -s raspberry-pi -p test_agent.py  # 저장소 루트에서 실행
python3 raspberry-pi/smoke_local.py                           # 선택: localhost:8080 개발 서버
```

두 번째 명령은 로컬 개발 서버에 검증용 가상 계정·아이 기록을 생성합니다. 연결→승인→토큰 수령→재시작→heartbeat→해제→토큰 차단을 실제 HTTP로 검증하며, 생성한 기기는 마지막에 해제합니다. 운영 서버용 테스트가 아닙니다.
