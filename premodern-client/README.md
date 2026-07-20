# Premodern Client G1

This is the observation-only Godot vertical slice for the Forge JSONL bridge. It launches the
standalone `forge-bridge` JAR, parses schema version 1 messages, and displays authoritative state
and interaction snapshots. It sends no gameplay commands and does not answer synchronous queries.

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

## Run G1

1. Start Forge Desktop and host an online/network game on the configured port (default `36743`).
2. Configure the host player/deck, leave one human player slot open, and wait in the lobby.
3. Open `premodern-client/project.godot` with Godot .NET and run the main scene/project.
4. The Godot process launches `forge-bridge`; the bridge joins the open slot and marks itself ready.
5. Start the game from Forge. Godot will show lifecycle status, controller creation, state, zones,
   stack, and the latest interaction callback.

The Forge desktop application can be packaged from the repository root with:

```powershell
mvn --% -pl forge-gui-desktop -am -DskipTests package
java -jar forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar
```

For a compile-only check of the client:

```powershell
dotnet build "premodern-client/Premodern Client.csproj" -nologo
```

Bridge stdout is reserved for JSONL and is parsed as protocol data. Bridge stderr is logged to the
Godot output as diagnostics. A `query` is visibly marked pending but intentionally receives no
reply in G1.
