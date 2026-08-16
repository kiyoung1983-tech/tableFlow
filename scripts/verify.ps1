$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path "$PSScriptRoot\..").Path
$env:GRADLE_USER_HOME = Join-Path $repositoryRoot '.verify-cache\gradle'
$env:npm_config_cache = Join-Path $repositoryRoot '.verify-cache\npm'

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory)] [string] $Command,
        [Parameter(ValueFromRemainingArguments)] [string[]] $CommandArguments
    )

    & $Command @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Command $($CommandArguments -join ' ')"
    }
}

Invoke-CheckedCommand -Command "$repositoryRoot\backend\gradlew.bat" -CommandArguments @(
    '-p', "$repositoryRoot\backend", 'test'
)
Invoke-CheckedCommand -Command 'npm.cmd' -CommandArguments @(
    'run', 'check', '--prefix', "$repositoryRoot\frontend"
)
Invoke-CheckedCommand -Command 'node' -CommandArguments @(
    '--test', "$repositoryRoot\scripts\staging-smoke.test.mjs"
)
Invoke-CheckedCommand -Command 'node' -CommandArguments @(
    '--test', "$repositoryRoot\scripts\deployment-preflight.test.mjs"
)
Invoke-CheckedCommand -Command 'node' -CommandArguments @(
    '--test', "$repositoryRoot\scripts\staging-uat.test.mjs"
)
