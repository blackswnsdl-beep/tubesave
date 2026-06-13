# TubeSave 카나리용 Docker 이미지
# 가볍게 python + git 만 설치하고 yt-dlp 는 매 빌드 시 최신으로 설치한다.
FROM python:3.12-slim

# git: config push 용 / tzdata: 로그 시간대용
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

# yt-dlp 최신 설치 (유튜브 변경 대응의 핵심)
RUN pip install --no-cache-dir --upgrade yt-dlp

WORKDIR /app
COPY canary.py /app/canary.py
COPY run-canary.sh /app/run-canary.sh
RUN chmod +x /app/run-canary.sh

# 기본 환경값 (docker-compose / 컨테이너 설정에서 덮어쓸 수 있음)
ENV REPO_DIR=/repo \
    INTERVAL_HOURS=6 \
    TZ=Asia/Seoul

ENTRYPOINT ["/app/run-canary.sh"]
