# Premodern Client G3

This Godot vertical slice launches the standalone `forge-bridge` JAR, displays authoritative
schema-version-1 state, and sends the bridge's existing typed interaction commands over JSONL.
Forge remains authoritative; the client does not calculate Magic legality or move cards locally.

## Requirements

- Godot .NET 4.7.x (the project currently references `Godot.NET.Sdk/4.7.1`).
- .NET 8 SDK.
- Java 17.
- A packaged `forge-bridge` JAR and the Forge assets under `forge-gui/res`.

From the repository root, build the bridge:

```powershell
mvn --% -pl forge-bridge -am -DskipTests package
```

The default Godot export properties point to:

```text
../forge-bridge/target/forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar
../forge-gui
localhost:36743
```

If the Forge version or location changes, select the `Main` node in Godot and update **Bridge Jar
Path**, **Assets Directory**, **Host**, **Port**, **Username**, or **Java Executable** in the
inspector. Relative paths are resolved from `premodern-client`.

## Run G3 With The Deterministic Host

Build the bridge, desktop test classes, and desktop dependency JAR:

```powershell
mvn --% -pl forge-gui-desktop,forge-bridge -am -DskipTests package
```

Start the development-only Spike C-style host from the Forge assets directory:

```powershell
Push-Location forge-gui
java -cp "..\forge-gui-desktop\target\test-classes;..\forge-gui-desktop\target\forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar" forge.net.GodotG3HostMain 36743
Pop-Location
```

The fixture enables `UI_SHOW_ACTIONABLE_HIGHLIGHTS`, gives Alice four Forests and four Llanowar
Elves, gives Bob seven Mountains and one Lightning Bolt, opens Bob's slot for the bridge, and starts
when the bridge is ready. It configures setup only; it never submits a player action.

In a second terminal or the Godot editor, run `premodern-client/project.godot`. Then:

1. Use Forge-provided **OK** or **Pass Priority** controls until Bob reaches a main phase.
2. Click a green `READY` Mountain in Bob's hand and choose the offered `Play land` ability.
3. Continue until Alice controls Llanowar Elves and Bob again has main-phase priority.
4. Click the green `READY` Lightning Bolt and choose its offered damage ability. Forge may repeat
   this callback with a new `requestId`; answer each query panel independently within 30 seconds.
5. Click Llanowar Elves when its opponent-battlefield button turns green and reads `READY`.
6. Click Bob's battlefield Mountain when Forge exposes it during mana payment.
7. Confirm the stack lists Lightning Bolt and Llanowar Elves, then click **Pass Priority**.
8. Confirm the authoritative view removes Lightning Bolt from hand/stack and moves Llanowar Elves
   from Alice's battlefield to Alice's graveyard.

Press Enter in the host terminal after verification to stop it cleanly.

## Run Against Forge Desktop

1. Start Forge Desktop and host an online/network game on the configured port (default `36743`).
2. Enable Forge's actionable-card highlighting (`UI_SHOW_ACTIONABLE_HIGHLIGHTS`) so the network
   controller emits legal hand cards as `weaklySelectableCardIds`.
3. Configure the host player/deck, leave one human player slot open, and wait in the lobby.
4. Open `premodern-client/project.godot` with Godot .NET and run the main scene/project.
5. The Godot process launches `forge-bridge`; the bridge joins the open slot and marks itself ready.
6. Start the game from Forge. Godot will show lifecycle status, controller creation, state, zones,
   stack, and the latest interaction callback.

## Controls And Authoritative Updates

- A card button is enabled only while its ID appears in the latest Forge `selectableCardIds` or
  `weaklySelectableCardIds` interaction data.
- **OK** and **Cancel** use Forge's current labels and enabled states.
- **Pass Priority** is enabled when the local player has priority and there is a current interaction.
- Offered player IDs appear as simple player-selection buttons.
- A synchronous `abilityChoice` query opens a panel of Forge's offered descriptions. Clicking one
  sends a `reply` correlated by `requestId`; it does not use `interactionSequence`.

For the G2 regression check, use a deck that gives the Godot-controlled player a basic land in hand:

1. Advance normally until that player is in a main phase with priority.
2. Confirm the land button is enabled by the latest Forge interaction.
3. Click the land once.
4. The action status will report the versioned `selectCard` send and later `actionAccepted`.
5. Wait for authoritative state to remove that ID from **Your visible hand** and add it to
   **Your battlefield**. The client performs no optimistic zone update.

For G3, the same generic card control handles the spell, target, and mana source. Its meaning comes
only from Forge's current interaction. The Godot output logs `G3 CLICK`, `G3 QUERY`, `G3 STACK`,
`G3 ZONE`, `G3 PASS_PRIORITY`, and `G3 RESOLUTION` observations for manual verification.

If Forge returns `STALE_INTERACTION`, the command is not retried. The controls refresh from the
latest interaction and require another click.

The Forge desktop application can be packaged from the repository root with:

```powershell
mvn --% -pl forge-gui-desktop -am -DskipTests package
java -jar forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar
```

For a compile-only check of the client:

```powershell
dotnet build "premodern-client/Premodern Client.csproj" -nologo
```

Bridge stdout is reserved for JSONL and is parsed as protocol data. Bridge stderr and outbound
command diagnostics are logged to the Godot output. All stdin writes pass through the typed command
serializer and one serialized writer task.
