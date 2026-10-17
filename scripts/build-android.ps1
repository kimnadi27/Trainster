param(
    [string]$JavaHome,
    [string[]]$Tasks = @('assembleDebug', 'lintDebug')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) {
    $bundledJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk21') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundledJdk) { $JavaHome = $bundledJdk.FullName }
    elseif ($env:JAVA_HOME) { $JavaHome = $env:JAVA_HOME }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 17 or 21, or pass -JavaHome. Android Studio: Settings > Build Tools > Gradle > Gradle JDK.'
}
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user'
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)." }
} finally { Pop-Location }
