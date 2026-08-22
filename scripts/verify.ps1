# Full verification gate: unit tests + both release-like native pipelines.
# Debug-only builds never compile the RelWithDebInfo native config, R8 rules,
# or the vulkan-shaders-gen host tool — this gate does, so native/R8 breakage
# surfaces here instead of on device day. CI runs the same set natively.
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath (Join-Path $PSScriptRoot "..")

& .\gradlew.bat --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& .\gradlew.bat --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
exit $LASTEXITCODE
