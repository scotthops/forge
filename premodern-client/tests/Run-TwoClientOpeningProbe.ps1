param(
    [Parameter(Mandatory = $true)]
    [string] $GodotExecutable
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$godotPath = (Resolve-Path -LiteralPath $GodotExecutable).Path
$hostJar = Join-Path $repository 'forge-gui-desktop\target\forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar'
$hostClasses = Join-Path $repository 'forge-gui-desktop\target\test-classes'
$bridgeJar = Join-Path $repository 'forge-bridge\target\forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar'
foreach ($required in @($hostJar, $bridgeJar, (Join-Path $hostClasses 'forge\net\GodotTwoHumanSlighHostMain.class'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Build the Java packages first; missing $required"
    }
}

$logs = Join-Path $env:TEMP ('mtg-two-godot-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logs | Out-Null
$serverProcess = $null
$clients = @()

function Has-Line([string] $path, [string] $pattern) {
    return [bool](Select-String -Path $path -Pattern $pattern -SimpleMatch -Quiet -ErrorAction SilentlyContinue)
}

try {
    $classpath = "$hostClasses;$hostJar"
    $serverProcess = Start-Process -FilePath 'java' `
        -ArgumentList @('-cp', $classpath, 'forge.net.GodotTwoHumanSlighHostMain', '36743', '60') `
        -WorkingDirectory (Join-Path $repository 'forge-gui') -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'host.out') `
        -RedirectStandardError (Join-Path $logs 'host.err') -PassThru

    $deadline = [datetime]::UtcNow.AddSeconds(50)
    while (-not (Has-Line (Join-Path $logs 'host.out') 'TWO_HUMAN_SLIGH_WAITING')) {
        if ($serverProcess.HasExited -or [datetime]::UtcNow -ge $deadline) {
            throw "Forge host did not reach its waiting state; see $logs"
        }
        Start-Sleep -Milliseconds 250
    }

    foreach ($name in @('SlighA', 'SlighB')) {
        $client = Start-Process -FilePath $godotPath `
            -ArgumentList @('--headless', '--log-file', (Join-Path $logs "$name.godot.log"),
                '--path', (Join-Path $repository 'premodern-client'),
                '--script', 'res://tests/TwoClientOpeningProbe.gd', '--', '--username', $name) `
            -WorkingDirectory $repository -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logs "$name.out") `
            -RedirectStandardError (Join-Path $logs "$name.err") -PassThru
        $clients += $client
    }

    $deadline = [datetime]::UtcNow.AddSeconds(90)
    while (($clients | Where-Object { -not $_.HasExited }).Count -gt 0) {
        if ($serverProcess.HasExited -or [datetime]::UtcNow -ge $deadline) {
            throw "Godot clients did not complete the opening probe; see $logs"
        }
        Start-Sleep -Milliseconds 250
    }

    $hostStarted = Has-Line (Join-Path $logs 'host.out') 'TWO_HUMAN_SLIGH_STARTED'
    $results = foreach ($index in 0..1) {
        $name = @('SlighA', 'SlighB')[$index]
        $output = Join-Path $logs "$name.out"
        [pscustomobject]@{
            Name = $name
            ExitCode = $clients[$index].ExitCode
            Play = Has-Line $output 'PROBE_CHOICE Play'
            OpeningHand = Has-Line $output 'PROBE_OPENING_HAND own_cards=7 opponent_count=7'
            Keep = Has-Line $output 'PROBE_CHOICE Keep'
            YourTurn = (Has-Line $output 'turn=TURN 1 — YOUR TURN')
            OpponentTurn = (Has-Line $output 'turn=TURN 1 — OPPONENT''S TURN')
            Failure = Has-Line $output 'PROBE_FAIL'
        }
    }
    Write-Output "Logs: $logs"
    Write-Output "Forge host started: $hostStarted"
    $results | Format-Table -AutoSize | Out-String | Write-Output
    if (-not $hostStarted -or ($results | Where-Object {
                $_.ExitCode -ne 0 -or -not $_.OpeningHand -or -not $_.Keep -or $_.Failure
            }) -or ($results | Where-Object Play).Count -ne 1 `
            -or ($results | Where-Object YourTurn).Count -ne 1 `
            -or ($results | Where-Object OpponentTurn).Count -ne 1) {
        throw "Two-client Godot opening probe failed; see $logs"
    }
} finally {
    foreach ($client in $clients) {
        if ($client -and -not $client.HasExited) {
            $client.Kill($true)
            $client.WaitForExit(5000) | Out-Null
        }
    }
    if ($serverProcess -and -not $serverProcess.HasExited) {
        $serverProcess.Kill($true)
        $serverProcess.WaitForExit(5000) | Out-Null
    }
}
