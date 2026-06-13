# TubeSave 카나리 (자동 유지보수)

어르신 휴대폰의 TubeSave 앱이 **유튜브 변경에도 알아서 계속 작동**하도록,
ASUSTOR NAS에서 6시간마다 유튜브 추출을 점검하고, 깨졌으면 작동하는 설정을
GitHub에 자동으로 올려주는 시스템입니다.

```
[ASUSTOR NAS] --(6시간마다 점검·수정)--> [GitHub: tubesave_config.json] --(앱이 자동 읽음)--> [어르신 휴대폰]
```

어르신은 앱을 한 번 설치한 뒤로는 **아무것도 하지 않아도** 됩니다.

---

## ⚙️ 실행 방식 (둘 중 하나)

유튜브는 **데이터센터 IP**(GitHub Actions 등 클라우드)를 봇으로 차단합니다
(`Sign in to confirm you're not a bot`). 그래서 카나리는 **가정용 IP**에서 돌려야 합니다.

| 방식 | 장점 | 단점 | 비밀값 |
|---|---|---|---|
| **A. PC (현재 운영)** | 설정 5분, 토큰 불필요(저장된 git 인증 사용) | PC가 켜져 있을 때만 점검 | 없음 |
| **B. NAS(ASUSTOR)** | 24시간 항상 점검 | Docker 설치·토큰 발급 필요 | GitHub PAT |

> **현재는 A(PC) 방식으로 운영합니다.** 아래 "PC 방식" 섹션 참고.
> 항상 점검이 필요해지면 B(NAS) 방식(이 문서의 1~5단계)으로 이전하면 됩니다.

---

## PC 방식 (Windows 작업 스케줄러)

이 PC(가정용 IP)에서 `run-canary-local.ps1` 을 6시간마다 실행합니다.
토큰이 필요 없습니다 — 이 PC에 이미 저장된 GitHub 로그인으로 push 합니다.

**구성 파일:** `automation/run-canary-local.ps1`
- yt-dlp 최신 갱신 → `canary.py` 실행 → 로그를 `automation/logs/` 에 저장
- web 계열 클라이언트는 제외(`CANDIDATE_CLIENTS=android_vr,android,ios,tv,mweb`)하여
  PC의 Node.js 로 인한 '거짓 성공'을 막고 휴대폰과 동일 조건 유지

**예약 작업 등록** (PowerShell에서 한 번):
```powershell
$A = New-ScheduledTaskAction -Execute "powershell.exe" `
  -Argument '-NoProfile -ExecutionPolicy Bypass -File "D:\GitHub\App\01_youtube_Mp3\automation\run-canary-local.ps1"'
$T = New-ScheduledTaskTrigger -Once -At (Get-Date) `
  -RepetitionInterval (New-TimeSpan -Hours 6)
Register-ScheduledTask -TaskName "TubeSave Canary" -Action $A -Trigger $T `
  -Description "유튜브 호환성 자동 점검 (6시간마다)"
```

**즉시 1회 테스트:**
```powershell
Start-ScheduledTask -TaskName "TubeSave Canary"
# 또는 직접:
powershell -ExecutionPolicy Bypass -File "D:\GitHub\App\01_youtube_Mp3\automation\run-canary-local.ps1"
```

로그는 `automation/logs/canary_*.log` 에서 확인합니다.
- `현재 설정(android_vr)이 정상 작동합니다. 변경 없음.` → 정상
- `전환: android_vr → ios` + `GitHub 에 push 완료` → 자동 복구됨

---

## 동작 원리

1. NAS의 컨테이너가 yt-dlp로 테스트 영상을 추출 시도 (휴대폰 앱과 같은 방식)
2. 현재 `player_client` 로 **잘 되면** → 아무것도 안 함
3. **깨졌으면** → `android`, `ios`, `tv`, `web` 등 후보를 차례로 시도
4. 작동하는 걸 찾으면 → `tubesave_config.json` 을 고쳐서 GitHub에 자동 커밋/푸시
5. 휴대폰 앱이 그 설정을 자동으로 읽어 적용

> 참고: 이 시스템은 **설정(extractor_args) 자동 갱신**만 합니다. 앱(APK) 자체는
> 바꾸지 않습니다. 유튜브 호환성 문제 대부분이 설정 변경으로 해결되므로
> 어르신은 앱 재설치가 필요 없습니다.

---

## 1단계: GitHub 토큰(PAT) 발급

NAS가 GitHub에 글을 쓰려면 비밀번호 대신 쓰는 토큰이 필요합니다.

1. GitHub 로그인 → 우측 상단 프로필 → **Settings**
2. 맨 아래 **Developer settings** → **Personal access tokens** → **Fine-grained tokens**
3. **Generate new token**
   - Token name: `tubesave-canary`
   - Expiration: `No expiration` (또는 1년 — 만료 시 갱신 필요)
   - Repository access: **Only select repositories** → `tubesave` 선택
   - Permissions → Repository permissions → **Contents: Read and write**
4. **Generate token** → 나오는 토큰 문자열(`github_pat_...`)을 **복사** (다시 못 봅니다)

---

## 2단계: ASUSTOR에 Docker 설치

1. NAS의 **App Central** 열기
2. **Docker Engine** 검색 → 설치 (또는 `Portainer` 도 함께 설치하면 GUI로 관리 편함)

---

## 3단계: 파일 올리기

`tubesave-automation` 폴더 전체를 NAS의 공유폴더(예: `/volume1/docker/tubesave`)에
복사합니다. ASUSTOR **File Explorer** 나 윈도우 네트워크 드라이브로 끌어다 놓으면 됩니다.

폴더 구조:
```
tubesave/
├─ docker-compose.yml
├─ .env.example
└─ automation/
   ├─ Dockerfile
   ├─ canary.py
   ├─ run-canary.sh
   └─ README.md  (이 파일)
```

---

## 4단계: 토큰 입력

`.env.example` 을 복사해 `.env` 로 만들고, 1단계에서 복사한 토큰을 넣습니다.

SSH로 접속해서:
```bash
cd /volume1/docker/tubesave
cp .env.example .env
nano .env        # GITHUB_TOKEN=github_pat_... 형태로 붙여넣고 저장(Ctrl+O, Ctrl+X)
```

> `.env` 파일에는 토큰이 들어있으니 **GitHub에 올리지 마세요** (.gitignore에 이미 제외됨).

---

## 5단계: 실행

```bash
cd /volume1/docker/tubesave
docker compose up -d --build
```

처음엔 이미지를 빌드하느라 몇 분 걸립니다. 이후 백그라운드에서 계속 돌며
재부팅해도 자동으로 다시 시작합니다(`restart: unless-stopped`).

---

## 동작 확인 / 로그 보기

```bash
docker logs -f tubesave-canary
```

- `현재 설정(android_vr)이 정상 작동합니다. 변경 없음.` → 정상 (유튜브 문제 없음)
- `전환: android_vr → ios` + `GitHub 에 push 완료` → 자동 복구가 일어난 것

즉시 한 번만 테스트하고 싶으면:
```bash
docker run --rm --env-file .env -e RUN_ONCE=1 tubesave-canary:latest
```

`.env` 에 `DRY_RUN=1` 을 넣으면 GitHub에 push하지 않고 점검만 합니다(테스트용).

---

## 자주 묻는 것

**Q. 점검 주기를 바꾸려면?**
`docker-compose.yml` 의 `INTERVAL_HOURS` 값을 바꾸고 `docker compose up -d` 다시 실행.

**Q. 모든 클라이언트가 다 실패하면?**
설정을 건드리지 않고 로그에 에러만 남깁니다. 이 경우는 보통 yt-dlp 자체 업데이트가
필요한 큰 변경입니다. 컨테이너를 다시 빌드(`docker compose up -d --build`)하면
최신 yt-dlp로 다시 시도합니다.

**Q. 토큰이 만료되면?**
GitHub에서 토큰을 새로 발급해 `.env` 의 `GITHUB_TOKEN` 을 교체하고 재시작.
