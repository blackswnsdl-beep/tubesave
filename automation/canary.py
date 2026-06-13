#!/usr/bin/env python3
"""
TubeSave 카나리(canary) 스크립트
=================================

목적
----
유튜브가 추출 방식을 자주 바꿔서, 앱이 어느 날 갑자기 "다운로드 실패"가 날 수 있다.
이 스크립트는 NAS에서 주기적으로 실행되며, 휴대폰 앱과 똑같이 yt-dlp로 추출을
시도해 본다. 현재 설정(player_client)으로 실패하면, 작동하는 다른 클라이언트를
자동으로 찾아 tubesave_config.json 을 수정하고 GitHub 에 push 한다.

그러면 어르신 휴대폰의 앱이 GitHub 에서 그 설정을 자동으로 읽어 적용하므로,
어르신은 아무것도 하지 않아도 계속 정상 동작한다.

동작 요약
---------
1. tubesave_config.json 을 읽어 현재 player_client 를 파악
2. [현재 클라이언트] + [후보 클라이언트들] 순서로 테스트 영상 추출 시도
3. 추출 성공 = (a) 오디오 포맷이 존재하고 (b) 그 포맷 URL 이 실제로 응답함
4. 현재 클라이언트가 그대로 잘 되면 → 아무것도 안 함 (조용히 종료)
5. 현재는 깨졌고 다른 클라이언트가 되면 → config 수정 + git commit/push
6. 전부 실패하면 → 에러 로그 남기고 비정상 종료(=2) (설정은 건드리지 않음)

환경변수
--------
REPO_DIR          : 로컬에 clone 된 저장소 경로 (기본 /repo)
TEST_VIDEO_URL    : 테스트에 쓸 유튜브 영상 (기본 yt-dlp 공식 테스트 영상)
CANDIDATE_CLIENTS : 콤마구분 후보 클라이언트 (기본값은 아래 DEFAULT_CANDIDATES)
GIT_AUTHOR_NAME   : 커밋 작성자 이름 (기본 tubesave-canary)
GIT_AUTHOR_EMAIL  : 커밋 작성자 이메일 (기본 canary@tubesave.local)
DRY_RUN           : "1" 이면 config 를 고치되 git push 는 하지 않음 (테스트용)
"""

import json
import os
import subprocess
import sys
from datetime import datetime, timezone

try:
    import yt_dlp
except ImportError:
    print("[FATAL] yt-dlp 가 설치되어 있지 않습니다. 'pip install yt-dlp' 필요.", file=sys.stderr)
    sys.exit(3)


# ── 설정값 ───────────────────────────────────────────────────────────────────
REPO_DIR = os.environ.get("REPO_DIR", "/repo")
# CONFIG_PATH 를 직접 지정할 수도 있음 (GitHub Actions 등 마운트 경로 대응).
# 미지정 시 REPO_DIR 기준으로 계산 (NAS 도커 방식 호환).
CONFIG_PATH = os.environ.get(
    "CONFIG_PATH", os.path.join(REPO_DIR, "tubesave_config.json")
)

# 테스트 영상: 유튜브 최초 업로드 영상 "Me at the zoo" (삭제 가능성이 매우 낮아 안정적).
# 필요시 환경변수 TEST_VIDEO_URL 로 교체 가능.
TEST_VIDEO_URL = os.environ.get(
    "TEST_VIDEO_URL", "https://www.youtube.com/watch?v=jNQXAC9IVRw"
)

# 시도해 볼 player_client 후보. 위에서부터 우선 시도.
# 유튜브 차단 패턴이 바뀔 때 가장 흔히 통하는 순서로 배치.
DEFAULT_CANDIDATES = [
    "android_vr",
    "android",
    "ios",
    "tv",
    "mweb",
    "web_safari",
    "web",
]
CANDIDATE_CLIENTS = [
    c.strip()
    for c in os.environ.get("CANDIDATE_CLIENTS", ",".join(DEFAULT_CANDIDATES)).split(",")
    if c.strip()
]

GIT_AUTHOR_NAME = os.environ.get("GIT_AUTHOR_NAME", "tubesave-canary")
GIT_AUTHOR_EMAIL = os.environ.get("GIT_AUTHOR_EMAIL", "canary@tubesave.local")
DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"


def log(msg: str) -> None:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    print(f"[{ts}] {msg}", flush=True)


# ── config 입출력 ────────────────────────────────────────────────────────────
def load_config() -> dict:
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_config(cfg: dict) -> None:
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
        f.write("\n")


def client_from_extractor_args(extractor_args):
    """'youtube:player_client=android_vr' -> 'android_vr'"""
    if not extractor_args:
        return None
    for part in extractor_args.replace(" ", "").split(";"):
        if "player_client=" in part:
            value = part.split("player_client=", 1)[1]
            return value.split(",")[0].strip()
    return None


def build_extractor_args(client: str) -> str:
    return f"youtube:player_client={client}"


# ── 추출 테스트 ──────────────────────────────────────────────────────────────
def test_client(client: str) -> bool:
    """
    주어진 player_client 로 테스트 영상에서 '재생 가능한 오디오 포맷'을 실제로
    얻을 수 있는지 확인한다. 성공하면 True.
    """
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "extractor_args": {"youtube": {"player_client": [client]}},
        "socket_timeout": 30,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(TEST_VIDEO_URL, download=False)
    except Exception as e:  # noqa: BLE001 - 어떤 추출 실패든 '이 클라이언트 실패'로 본다
        log(f"  - {client}: 추출 예외 ({type(e).__name__}: {str(e)[:120]})")
        return False

    if not info:
        log(f"  - {client}: info 없음")
        return False

    formats = info.get("formats") or []
    # 오디오가 있는 포맷이 하나라도 있으면 MP3 변환 가능 (acodec != none)
    audio_formats = [
        f for f in formats
        if f.get("acodec") not in (None, "none") and f.get("url")
    ]
    if not audio_formats:
        log(f"  - {client}: 오디오 포맷 없음 (formats={len(formats)})")
        return False

    log(f"  - {client}: OK (오디오 포맷 {len(audio_formats)}개)")
    return True


# ── git ──────────────────────────────────────────────────────────────────────
def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", REPO_DIR, *args],
        check=True,
        capture_output=True,
        text=True,
    )


def commit_and_push(new_client: str, old_client) -> None:
    if DRY_RUN:
        log("DRY_RUN=1 → git push 생략 (config 파일만 수정됨)")
        return

    git("config", "user.name", GIT_AUTHOR_NAME)
    git("config", "user.email", GIT_AUTHOR_EMAIL)

    # 변경사항 최신화(다른 곳에서 push 됐을 수 있으니 먼저 pull --rebase)
    try:
        git("pull", "--rebase", "--autostash")
    except subprocess.CalledProcessError as e:
        log(f"[WARN] git pull 실패(무시하고 진행): {e.stderr.strip()[:200]}")

    git("add", "tubesave_config.json")

    # 실제 변경이 없으면 커밋하지 않음
    status = git("status", "--porcelain").stdout.strip()
    if not status:
        log("변경사항 없음 → 커밋 생략")
        return

    msg = f"canary: player_client {old_client or '(none)'} -> {new_client}"
    git("commit", "-m", msg)
    git("push")
    log(f"GitHub 에 push 완료: {msg}")


# ── 메인 ─────────────────────────────────────────────────────────────────────
def main() -> int:
    log("=== TubeSave 카나리 시작 ===")
    log(f"테스트 영상: {TEST_VIDEO_URL}")
    log(f"yt-dlp 버전: {yt_dlp.version.__version__}")

    if not os.path.exists(CONFIG_PATH):
        log(f"[FATAL] config 파일이 없습니다: {CONFIG_PATH}")
        return 3

    cfg = load_config()
    current_client = client_from_extractor_args(cfg.get("extractor_args", ""))
    log(f"현재 player_client: {current_client}")

    # 현재 클라이언트를 맨 앞에, 나머지는 뒤에 (중복 제거, 순서 유지)
    ordered = []
    for c in [current_client, *CANDIDATE_CLIENTS]:
        if c and c not in ordered:
            ordered.append(c)

    log(f"테스트 순서: {ordered}")

    working_client = None
    for client in ordered:
        if test_client(client):
            working_client = client
            break

    if working_client is None:
        log("[ERROR] 모든 player_client 가 실패했습니다. config 는 변경하지 않습니다.")
        log("        (yt-dlp 업데이트가 필요하거나 유튜브 측 큰 변경일 수 있음)")
        return 2

    if working_client == current_client:
        log(f"현재 설정({current_client})이 정상 작동합니다. 변경 없음.")
        return 0

    # 작동하는 새 클라이언트를 찾음 → config 갱신
    log(f"전환: {current_client} → {working_client}")
    cfg["extractor_args"] = build_extractor_args(working_client)
    cfg["update_message"] = "유튜브 호환성을 자동으로 개선했어요."
    save_config(cfg)

    commit_and_push(working_client, current_client)
    log("=== 완료 ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
 