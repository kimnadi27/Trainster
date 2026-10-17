param(
    [string]$Python,
    [int]$Port = 8000,
    [switch]$Install,
    [switch]$NoReload
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serverRoot = Join-Path $projectRoot 'server'
$venvPython = Join-Path $serverRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    if (-not $Python) { $Python = 'python' }
    & $Python -m venv (Join-Path $serverRoot '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Could not create Python venv. Pass -Python C:/path/to/python.exe (Python 3.12+).' }
    $Install = $true
}
if ($Install) {
    & $venvPython -m pip install -r (Join-Path $serverRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
}
if (-not (Test-Path -LiteralPath (Join-Path $serverRoot '.env'))) {
    Copy-Item -LiteralPath (Join-Path $serverRoot '.env.example') -Destination (Join-Path $serverRoot '.env')
    Write-Host 'Created server/.env. Set YANDEX_RASP_API_KEY there for real schedules.'
}
Push-Location $serverRoot
try {
    $uvicornArgs = @('-m', 'uvicorn', 'app.main:app', '--host', '0.0.0.0', '--port', "$Port")
    if (-not $NoReload) { $uvicornArgs += '--reload' }
    & $venvPython @uvicornArgs
    if ($LASTEXITCODE -ne 0) { throw "Server stopped with error ($LASTEXITCODE)." }
} finally { Pop-Location }
