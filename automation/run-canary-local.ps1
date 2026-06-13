# =====================================================================
# TubeSave canary - Windows local runner
# ---------------------------------------------------------------------
# Why run on this PC:
#   GitHub Actions (cloud) uses datacenter IPs that YouTube blocks with
#   "Sign in to confirm you're not a bot". This PC has a residential IP,
#   the same situation as the phone app, so it is not blocked.
#
# What it does:
#   1) Upgrade yt-dlp to latest (key to coping with YouTube changes)
#   2) Run canary.py -> if the current player_client is broken, switch
#      tubesave_config.json to a working one and git push (using this
#      PC's stored GitHub credentials, no token needed)
#   3) Write a log into automation\logs
#
# Manual run:
#   powershell -ExecutionPolicy Bypass -File "D:\GitHub\App\01_youtube_Mp3\automation\run-canary-local.ps1"
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 misreads
# UTF-8-without-BOM scripts that contain non-ASCII characters.
# =====================================================================

$ErrorActionPreference = "Continue"

# Force Python into UTF-8 mode so Korean log text is not mangled
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

# Decode child-process (python) output as UTF-8 so Korean shows correctly
# in the console and the log file instead of garbled cp949 text.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [System.Text.Encoding]::UTF8

$RepoDir = "D:\GitHub\App\01_youtube_Mp3"
$env:REPO_DIR = $RepoDir

# Only test clients that do NOT need a JS (nsig) runtime, to match the
# Android phone app. web/web_safari are excluded so the PC's Node.js
# cannot produce a false "OK".
$env:CANDIDATE_CLIENTS = "android_vr,android,ios,tv,mweb"

# Prepare log folder
$LogDir = Join-Path $RepoDir "automation\logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$Log = Join-Path $LogDir ("canary_" + $Stamp + ".log")

# Pick Python launcher (prefer 'py', fall back to 'python')
$Py = "py"
if (-not (Get-Command py -ErrorAction SilentlyContinue)) { $Py = "python" }

("[" + (Get-Date -Format o) + "] === run-canary-local start (py=" + $Py + ") ===") | Tee-Object -FilePath $Log -Append

# 1) Upgrade yt-dlp (continue even if it fails, e.g. offline)
#    --no-warn-script-location: silence the harmless "not on PATH" notice
& $Py -m pip install --quiet --no-warn-script-location --upgrade yt-dlp 2>&1 | Tee-Object -FilePath $Log -Append

# 2) Run the canary
& $Py (Join-Path $RepoDir "automation\canary.py") *>&1 | Tee-Object -FilePath $Log -Append
$rc = $LASTEXITCODE

("[" + (Get-Date -Format o) + "] === canary.py exit code: " + $rc + " ===") | Tee-Object -FilePath $Log -Append

# Clean up logs older than 30 days
Get-ChildItem $LogDir -Filter "canary_*.log" -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } |
    Remove-Item -Force -ErrorAction SilentlyContinue

exit $rc
