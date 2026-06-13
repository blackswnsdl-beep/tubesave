#!/usr/bin/env bash
#
# TubeSave 카나리 실행 루프
# - 저장소를 clone(처음) 또는 pull(이후) 한 뒤
# - canary.py 를 실행하고
# - INTERVAL_HOURS 시간만큼 잤다가 반복한다.
#
# 필요한 환경변수
#   GITHUB_TOKEN  : repo 쓰기 권한이 있는 Personal Access Token (필수)
#   GITHUB_REPO   : "owner/name" 형식 (기본 blackswnsdl-beep/tubesave)
#   REPO_DIR      : clone 위치 (기본 /repo)
#   INTERVAL_HOURS: 실행 주기 시간 (기본 6)
#   RUN_ONCE      : "1" 이면 한 번만 실행하고 종료 (테스트/스케줄러용)
#
set -u

GITHUB_REPO="${GITHUB_REPO:-blackswnsdl-beep/tubesave}"
REPO_DIR="${REPO_DIR:-/repo}"
INTERVAL_HOURS="${INTERVAL_HOURS:-6}"
RUN_ONCE="${RUN_ONCE:-0}"

log() { echo "[$(date -u '+%Y-%m-%d %H:%M:%S UTC')] $*"; }

if [ -z "${GITHUB_TOKEN:-}" ]; then
  log "[FATAL] GITHUB_TOKEN 환경변수가 없습니다. PAT 를 설정하세요."
  exit 1
fi

AUTH_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPO}.git"

# 인증/네트워크 문제 시 git 이 입력을 기다리며 멈추지 않고 즉시 에러로 종료하게 함
export GIT_TERMINAL_PROMPT=0

prepare_repo() {
  if [ -d "${REPO_DIR}/.git" ]; then
    log "기존 저장소 갱신(pull)"
    git -C "${REPO_DIR}" remote set-url origin "${AUTH_URL}"
    git -C "${REPO_DIR}" fetch --quiet origin || { log "[ERROR] git fetch 실패"; return 1; }
    git -C "${REPO_DIR}" reset --hard origin/HEAD --quiet || true
  else
    log "저장소 clone: ${GITHUB_REPO}"
    # ${REPO_DIR}(/repo)는 도커 볼륨 마운트 지점이라 통째로 삭제할 수 없다.
    # (rm -rf /repo → "Device or resource busy") 그래서 '내용물'만 비운 뒤 clone 한다.
    find "${REPO_DIR}" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
    git clone --quiet "${AUTH_URL}" "${REPO_DIR}" || { log "[ERROR] git clone 실패"; return 1; }
  fi
}

run_once() {
  prepare_repo || { log "저장소 준비 실패 — 이번 회차 건너뜀"; return 1; }
  # yt-dlp 를 매 실행마다 최신으로 갱신한다.
  # 클라이언트 교체로 안 풀리는 '큰 변경'은 보통 yt-dlp 업데이트로 해결되므로,
  # 이 한 줄로 그 경우까지 자동 대응한다(인터넷 필요, 실패해도 기존 버전으로 계속 진행).
  log "yt-dlp 최신화"
  pip install --no-cache-dir --upgrade --quiet yt-dlp || log "[WARN] yt-dlp 업그레이드 실패(기존 버전으로 진행)"
  log "canary.py 실행"
  python /app/canary.py
  local rc=$?
  log "canary.py 종료 코드: ${rc}"
  return $rc
}

# 토큰이 로그에 남지 않도록 주의 (URL 에 토큰이 들어가므로 set -x 사용 금지)
while true; do
  run_once || true
  if [ "${RUN_ONCE}" = "1" ]; then
    log "RUN_ONCE=1 → 종료"
    break
  fi
  log "${INTERVAL_HOURS}시간 대기..."
  sleep "$(( INTERVAL_HOURS * 3600 ))"
done
