# Local diagnosis never publishes by default; publication requires -Publish.
[CmdletBinding()]
param([switch]$Publish, [switch]$SkipUpgrade)
$ErrorActionPreference = "Stop"
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$OutputEncoding = [System.Text.Encoding]::UTF8

# Resolve relative to this script, so moving the GitHub Desktop checkout works.
$RepoDir = Split-Path -Parent $PSScriptRoot
$env:REPO_DIR = $RepoDir
$env:DRY_RUN = if ($Publish) { "0" } else { "1" }
$env:CANDIDATE_CLIENTS = "default,android_vr,android,ios,tv,mweb"
$LogDir = Join-Path $PSScriptRoot "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Log = Join-Path $LogDir ("canary_" + (Get-Date -Format "yyyy-MM-dd_HHmmss") + ".log")

# Keep dependencies local to this project; do not modify the system Python.
$Deps = Join-Path $PSScriptRoot ".python-deps"
$Py = if (Get-Command py -ErrorAction SilentlyContinue) { "py" } else { "python" }
if (-not $SkipUpgrade) {
    & $Py -m pip install --disable-pip-version-check --quiet --no-warn-script-location --upgrade --target $Deps 'yt-dlp[default]' 2>&1 | Tee-Object -FilePath $Log -Append
    if ($LASTEXITCODE -ne 0) { throw "yt-dlp dependency update failed. See $Log" }
}
$PreviousPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = $Deps + [IO.Path]::PathSeparator + $PreviousPythonPath
    & $Py (Join-Path $PSScriptRoot "canary.py") *>&1 | Tee-Object -FilePath $Log -Append
    $rc = $LASTEXITCODE
} finally { $env:PYTHONPATH = $PreviousPythonPath }
exit $rc
