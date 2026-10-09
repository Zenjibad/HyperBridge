# Gradle entry point for the cooldown fork.
# The host's CI builds with JDK 21 (.github/workflows/build-debug-apk.yml:29-33). This machine's
# ambient JAVA_HOME points at a JDK that does not exist, and gradlew.bat launches its own JVM
# before Gradle reads gradle.properties -- so pin JAVA_HOME here for the launcher.
# Usage: .\gw.ps1 :engine:test
$jdk = if (Test-Path 'C:\Users\Administrator\.jdks\jdk-21.0.5+11') {
    'C:\Users\Administrator\.jdks\jdk-21.0.5+11'
} else {
    'C:\Users\Administrator\.jdks\jdk-17.0.20.1+1'
}
Write-Host "gw.ps1: JAVA_HOME=$jdk"
$env:JAVA_HOME = $jdk
& "$PSScriptRoot\gradlew.bat" @args
exit $LASTEXITCODE
