# Two-human Sligh development playtest

This starts one Forge host and two Godot clients. Each Godot process owns a separate
`forge-bridge` JVM. The two bridge usernames must differ; connection order determines
which open Forge seat each client receives. Forge remains authoritative for choices,
rules, and state.

From the repository root, build the current Java artifacts and C# client:

```powershell
mvn --% -pl forge-gui-desktop,forge-bridge -am -DskipTests package
dotnet build "premodern-client/Premodern Client.csproj" -nologo
```

Start the development host in **terminal 1**. Keep this terminal open. The working
directory matters because Forge loads resources from `forge-gui`:

```powershell
Push-Location forge-gui
java -cp "..\forge-gui-desktop\target\test-classes;..\forge-gui-desktop\target\forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar" forge.net.GodotTwoHumanSlighHostMain 36743
Pop-Location
```

Set `$godot` to your installed Godot **.NET** executable. Run the following in
**terminal 2** from the repository root:

```powershell
$godot = "C:\Users\scott\Desktop\Godot_v4.7.1-stable_mono_win64\Godot_v4.7.1-stable_mono_win64_console.exe"
& $godot --path (Resolve-Path .\premodern-client).Path -- --username "Sligh Player A"
```

Run the same command in **terminal 3**, changing the username:

```powershell
$godot = "C:\Users\scott\Desktop\Godot_v4.7.1-stable_mono_win64\Godot_v4.7.1-stable_mono_win64_console.exe"
& $godot --path (Resolve-Path .\premodern-client).Path -- --username "Sligh Player B"
```

Godot passes arguments after `--` to `OS.GetCmdlineUserArgs()`. `Main.ResolveUsername`
uses the supplied name for that process's bridge and window title. With no argument,
the scene's exported `Username` remains the default. Empty or repeated `--username`
arguments fail visibly instead of starting a bridge with an ambiguous identity.

The host waits up to two minutes for two distinct ready remote bridges, then starts
the match. Verify that the window titles differ, each window shows its own opening
hand and only the opponent's hand count, the coin-toss winner can answer Forge's
Play/Draw choice, and each player can answer mulligan choices. Seat 0/1 is assigned
by arrival order, so the player names identify the windows.

Close both Godot windows to stop their child bridges. Press Enter in the host
terminal to stop the development host. The existing headless two-bridge integration
test proves separate seats and opening-hand privacy; a two-window Godot playthrough
through lethal still needs manual verification.

## Repeatable Godot runtime check

The scene-level probe runs the real `Main.tscn` and bridge in two headless Godot
processes. It presses Forge's offered Play and Keep buttons through the rendered
controls. From the repository root, after building both Java and C# artifacts:

```powershell
& .\premodern-client\tests\Run-TwoClientOpeningProbe.ps1 `
  -GodotExecutable "C:\Users\scott\Desktop\Godot_v4.7.1-stable_mono_win64\Godot_v4.7.1-stable_mono_win64_console.exe"
```

The runner starts and stops its own host. It checks both process exit codes, both
seven-card hand displays, both Keep actions, and turn-one displays. Its logs are
retained under the printed temporary directory.

To continue through the first turn, add `-ThroughTurnTwo`:

```powershell
& .\premodern-client\tests\Run-TwoClientOpeningProbe.ps1 `
  -GodotExecutable "C:\Users\scott\Desktop\Godot_v4.7.1-stable_mono_win64\Godot_v4.7.1-stable_mono_win64_console.exe" `
  -ThroughTurnTwo
```

In this mode, each headless client presses Pass Priority only when its rendered
control is enabled. The runner requires each client to send passes, receive turn
two, and show opposite active-player labels without protocol errors. On
2026-09-28, both clients passed through turn one and each sent seven priority
passes. The next mode covers land play; combat, targeting, and lethal still
require a longer two-client playtest.

To verify a shared authoritative land update, use `-PlayLand` instead. The
probe advances priority until one client has a Forge-selectable Mountain,
then emits that card control's primary action. It waits for the acting
client's hand count to fall by one and a card to appear in its land row. The
other client must see the same change in its opponent hand count and land row.

```powershell
& .\premodern-client\tests\Run-TwoClientOpeningProbe.ps1 `
  -GodotExecutable "C:\Users\scott\Desktop\Godot_v4.7.1-stable_mono_win64\Godot_v4.7.1-stable_mono_win64_console.exe" `
  -PlayLand
```

On 2026-09-28, the selected Mountain moved from hand to battlefield in
the acting Godot client, and both clients displayed the Forge-published
hand-count and land-row changes. This test uses the exact Sligh fixture and
its normal shuffle; it does not make land play deterministic when both
players draw landless hands. Combat, targeting, and lethal remain outside
this probe.
