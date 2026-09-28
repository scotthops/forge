param(
    [Parameter(Mandatory = $true)]
    [string] $GodotExecutable,

    [switch] $ThroughTurnTwo,

    [switch] $PlayLand
)

$ErrorActionPreference = 'Stop'
if ($ThroughTurnTwo -and $PlayLand) {
    throw 'Choose either -ThroughTurnTwo or -PlayLand.'
}
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
        -ArgumentList @('-cp', $classpath, 'forge.net.GodotTwoHumanSlighHostMain', '36743', '90') `
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
        $arguments = @('--headless', '--log-file', (Join-Path $logs "$name.godot.log"),
            '--path', (Join-Path $repository 'premodern-client'),
            '--script', 'res://tests/TwoClientOpeningProbe.gd', '--', '--username', $name)
        if ($ThroughTurnTwo) {
            $arguments += '--through-turn-two'
        }
        if ($PlayLand) {
            $arguments += '--play-land'
        }
        $client = Start-Process -FilePath $godotPath `
            -ArgumentList $arguments `
            -WorkingDirectory $repository -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logs "$name.out") `
            -RedirectStandardError (Join-Path $logs "$name.err") -PassThru
        $clients += $client
    }

    $deadline = [datetime]::UtcNow.AddSeconds(120)
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
        $diagnostics = Join-Path $logs "$name.err"
        [pscustomobject]@{
            Name = $name
            ExitCode = $clients[$index].ExitCode
            Play = Has-Line $output 'PROBE_CHOICE Play'
            OpeningHand = Has-Line $output 'PROBE_OPENING_HAND own_cards=7 opponent_count=7'
            Keep = Has-Line $output 'PROBE_CHOICE Keep'
            YourTurn = (Has-Line $output 'turn=TURN 1 — YOUR TURN')
            OpponentTurn = (Has-Line $output 'turn=TURN 1 — OPPONENT''S TURN')
            Pass = Has-Line $output 'PROBE_PASS count='
            YourTurnTwo = (Has-Line $output 'turn=TURN 2 — YOUR TURN')
            OpponentTurnTwo = (Has-Line $output 'turn=TURN 2 — OPPONENT''S TURN')
            LandClick = Has-Line $output 'PROBE_LAND_CLICK'
            LandOwn = Has-Line $output 'PROBE_LAND_OWN'
            LandOpponent = Has-Line $output 'PROBE_LAND_OPPONENT'
            Failure = Has-Line $output 'PROBE_FAIL'
            ProtocolError = (Has-Line $output 'STALE_INTERACTION') -or `
                (Has-Line $output 'Bridge error ') -or (Has-Line $diagnostics 'ERROR:')
        }
    }
    Write-Output "Logs: $logs"
    Write-Output "Forge host started: $hostStarted"
    if ($PlayLand) {
        $results | Select-Object Name, ExitCode, Play, OpeningHand, Keep,
            LandClick, LandOwn, LandOpponent, ProtocolError `
            | Format-Table -AutoSize | Out-String | Write-Output
    } elseif ($ThroughTurnTwo) {
        $results | Select-Object Name, ExitCode, Play, OpeningHand, Keep,
            Pass, YourTurnTwo, OpponentTurnTwo, ProtocolError `
            | Format-Table -AutoSize | Out-String | Write-Output
    } else {
        $results | Select-Object Name, ExitCode, Play, OpeningHand, Keep,
            YourTurn, OpponentTurn, ProtocolError `
            | Format-Table -AutoSize | Out-String | Write-Output
    }
    if (-not $hostStarted -or ($results | Where-Object {
                $_.ExitCode -ne 0 -or -not $_.OpeningHand -or -not $_.Keep `
                    -or $_.Failure -or $_.ProtocolError
            }) -or ($results | Where-Object Play).Count -ne 1 `
            -or ($results | Where-Object YourTurn).Count -ne 1 `
            -or ($results | Where-Object OpponentTurn).Count -ne 1) {
        throw "Two-client Godot opening probe failed; see $logs"
    }
    if ($ThroughTurnTwo -and (($results | Where-Object { -not $_.Pass }).Count -ne 0 `
            -or ($results | Where-Object YourTurnTwo).Count -ne 1 `
            -or ($results | Where-Object OpponentTurnTwo).Count -ne 1)) {
        throw "Two-client turn progression failed; see $logs"
    }
    if ($PlayLand -and (($results | Where-Object LandClick).Count -ne 1 `
            -or ($results | Where-Object LandOwn).Count -ne 1 `
            -or ($results | Where-Object LandOpponent).Count -ne 1)) {
        throw "Two-client land projection failed; see $logs"
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
