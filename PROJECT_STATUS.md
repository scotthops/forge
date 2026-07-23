# Premodern Client — Project Status

> **Current as of:** 2026-07-23  
> **Git branch:** `spike/godot-bridge`  
> **Commit inspected:** `61f4ad11dd9da65cb1c3632f385e976c331a716b` (`initial trample fix`)  
> **Working tree at inspection start:** no tracked modifications; the pre-existing `.codex/` directory was untracked, so the tree was not clean.

This is the primary high-level checkpoint for the custom Premodern client work in this Forge fork. It describes the checked-out code, not just the intended architecture. Re-check the code and Git history when this file is old.

## 1. Project Goal

This project is a small, unofficial 1v1 Premodern Magic client. It reuses Forge as the authoritative game and rules engine and uses a separate Godot C# application for a purpose-built presentation and input layer.

Forge is reused because it already models Magic rules, card scripts, game state, and legal player inputs. Godot exists so the product can have a focused UI without duplicating those rules or being constrained by Forge Desktop's presentation.

The scope is deliberately narrow:

- one 1v1 Premodern match;
- initially, one exact Sligh mirror;
- compatibility expanded deck-by-deck after that match works;
- no attempt yet to expose every card Forge knows.

**Current north star:** Two humans can play a complete Sligh mirror from opening hand and mulligan through lethal using the Godot client, with Forge authoritative and no manual console intervention.

## 2. Current Milestone

The immediate milestone is the first genuine end-to-end Sligh mirror.

What already works:

- Forge can host a normally shuffled match using the exact 60-card Sligh fixture for both decks.
- A Godot instance launches a separate bridge JVM, which joins the Forge TCP lobby as one remote human.
- The bridge receives an opening hand, Forge-provided mulligan controls, state/delta updates, and interaction callbacks.
- Godot renders the projected game state and can send generic card, player, button, priority, ability-choice, and combat-damage responses.
- Tests reach an ordinary turn with the shuffled Sligh fixture. Separate deterministic tests cover a land play and targeted Lightning Bolt flow.
- The current trample work provides a structured combat-damage query and client editor; its bridge unit tests pass.

What is still required:

- Complete repeated Sligh games through lethal and close every required generic interaction gap they reveal.
- Resolve the currently failing external JSONL targeted-spell integration test.
- Verify the new combat-damage/trample path in a real networked Sligh playthrough, not only bridge unit tests.
- Add an actual two-human arrangement. `GodotSlighMirrorHostMain` currently uses Forge AI in seat 1 and one Godot-controlled remote human in seat 2.
- Remove dependence on console input for normal match lifecycle and provide a usable two-client startup/join flow.

The code does **not** yet prove a complete Sligh game or a two-human game.

## 3. Architecture Overview

```text
Godot .NET client: premodern-client
        │
        │ UTF-8 JSONL over child-process stdin/stdout
        ▼
forge-bridge fat JAR: separate headless JVM
        │
        │ Forge network protocol over TCP (default localhost:36743)
        ▼
Forge host JVM: development host or Forge Desktop
        │
        │ in-process game/controller calls
        ▼
Forge rules and game engine
```

The important boundary is:

- **Forge is authoritative** for legality, rules, choices, and state transitions.
- **The bridge** turns Forge's networked `IGameController`/GUI callbacks into generic, visibility-safe state and interaction messages.
- **Godot** presents that state and only returns Forge-offered actions or choices. It does not move cards optimistically or independently decide what is legal.

The current development setup has three OS processes: the Forge host JVM, Godot, and the `forge-bridge` JVM that Godot starts. The host and authoritative engine share one JVM. Godot and the bridge communicate by JSONL; the bridge and host communicate over Forge's existing TCP network path.

## 4. Repository / Module Map

| Path | Current role |
|---|---|
| `forge-game/` | Authoritative rules and game model. Custom work includes `CombatDamageAssignment` and the London mulligan correction. |
| `forge-gui/` | Forge GUI/controller interfaces and network client/server infrastructure. It forwards selectable players and contains the server shutdown fix. |
| `forge-gui-desktop/` | Desktop artifact plus the test-only hosts and network harnesses used by this project. |
| `forge-bridge/` | Standalone headless remote-player adapter. It loads Forge resources, joins a host via `FGameClient`, and exposes schema-v1 JSONL. |
| `premodern-client/` | Godot 4.7 C# project: process management, protocol parser/state, UI, reusable card control, and local image catalog. It is not a Maven module. |

Important custom entry points and files:

- `forge.bridge.BridgeMain` — standalone bridge JAR entry point.
- `forge.bridge.BridgeSession` — lobby connection, ready state, and lifecycle.
- `forge.bridge.BridgeGuiGame` — translates Forge state/interactions and invokes the network controller.
- `forge.bridge.BridgeProtocol` / `BridgePrinter` — schema, validation, correlation, projections, and JSON messages.
- `forge-bridge/PROTOCOL.md` — current protocol reference.
- `forge.net.GodotSlighMirrorHostMain` — test-source Sligh-vs-AI host for manual Godot play.
- `forge.net.GodotG3HostMain` — deterministic Mountain/Lightning Bolt/Llanowar Elves regression host.
- `forge.net.SlighMirrorDeck` — exact first-deck recipe.
- `premodern-client/Main.cs` / `Main.tscn` — client startup, rendering, input, and main layout.
- `premodern-client/CardControl.cs` / `CardControl.tscn` — reusable image-backed hand/battlefield card.
- `premodern-client/CardImageCatalog.cs` — local WebP lookup/cache.

The development host classes are under `forge-gui-desktop/src/test`; they are compiled into `target/test-classes`, not the desktop production JAR.

## 5. Current End-to-End Flow

1. Start a Forge host from the `forge-gui` directory so Forge can find `res/`. The current Sligh host opens one remote seat and assigns the other seat to Forge AI.
2. Run the Godot project. `Main._Ready()` starts `BridgeProcessClient`.
3. Godot launches the packaged `forge-bridge` JAR with the configured host, port, username, and assets directory.
4. `BridgeMain` installs a headless `BridgeGuiBase`, initializes `FModel` and card resources, then `BridgeSession` connects through `FGameClient`.
5. The bridge claims the open lobby slot, marks itself ready, and the development host starts when that readiness is observed.
6. Forge sends full state and delta callbacks over its network protocol. `BridgeGuiGame` applies them and `BridgePrinter` emits a visibility-safe JSONL projection.
7. Godot parses messages into `BridgeClientState`, renders the latest authoritative state, and enables only currently offered controls.
8. A click sends a typed JSONL command to the bridge. The bridge validates correlation and offered IDs, then calls the Forge-provided `IGameController`.
9. Forge advances the game and publishes new authoritative state. Godot waits for that update; it never performs a local zone move.

Ordering and correlation are intentionally separate:

- `schemaVersion` is currently `1` and is required on every message.
- `stateSequence` identifies Forge full/delta state ordering. `-1` means the callback supplied no sequence.
- `interactionSequence` strictly increases for asynchronous interactions. Card/player/button/pass commands must echo the current value; stale commands are rejected with `STALE_INTERACTION`.
- `requestId` correlates a synchronous query and reply. Ability and combat-damage queries wait up to 30 seconds.

Bridge stdout is reserved for JSONL. Forge diagnostics are redirected to stderr so they cannot corrupt the protocol stream.

## 6. Implemented Features

### Forge / Host

- [x] Java 17 headless Forge initialization with the real card/resource database.
- [x] TCP network host using Forge's existing `FServerManager` and `ServerGameLobby`.
- [x] Deterministic G3 host for land, targeted spell, mana payment, stack, and resolution work.
- [x] Normally shuffled Sligh mirror fixture with two exact 60-card decks.
- [x] Forge AI opponent plus one open Godot/bridge remote-human seat.
- [x] Actionable-card highlighting enabled for the fixtures.
- [x] Selectable-player forwarding through the existing network GUI boundary.
- [x] Idempotent/restartable server shutdown and a JVM-shutdown-hook regression test.
- [x] London mulligan timing/selectable-card correction.

### Bridge / Protocol

- [x] Standalone fat JAR with a headless Forge GUI implementation.
- [x] UTF-8 newline-delimited JSON on stdin/stdout.
- [x] Required schema version and structured protocol errors.
- [x] Visibility-safe player, hand-count, visible-hand, battlefield, graveyard, turn, phase, priority, and stack projection.
- [x] Visible card name, zone, tapped state, and Forge-derived `isLand`.
- [x] Generic `selectCard`, `selectPlayer`, `button`, and `passPriority` commands.
- [x] Prompt, buttons, strong/weak card selectables, and player selectables.
- [x] Strict `interactionSequence` stale-action protection.
- [x] Correlated ability-choice queries using `requestId`.
- [x] Structured combat-damage assignment with ordered recipients, lethal minima, player/card defenders, validation, retry, and optional skip.
- [x] Action-accepted acknowledgements and lifecycle/controller messages.

### Godot Client

- [x] Launches and owns the bridge child process.
- [x] Typed schema-v1 parser, message records, command serializer, and client-state reducer.
- [x] Serialized async stdin writer; separate stdout protocol and stderr diagnostic readers.
- [x] No optimistic game-state mutation.
- [x] Card and player selection, Forge-labelled OK/Cancel controls, and pass priority.
- [x] Ability-choice panel and correlated replies.
- [x] Combat-damage editor with client-side guidance plus authoritative bridge validation.
- [x] Opening-hand Keep/Mulligan controls and London-mulligan card selection/Auto button path.
- [x] Graceful bridge disposal on scene exit, with forced child termination as a fallback.

### Gameplay / Interaction Coverage

- [x] Opening seven-card hand and mulligan prompt.
- [x] Land play through the remote controller.
- [x] Targeted spell selection, ability selection, card target, player target, mana-source selection, stack observation, pass priority, and authoritative resolution are represented in code/tests.
- [x] Single unambiguous triggered ability handling, including the Jackal Pup regression.
- [x] Attacker/blocker card selection primitives and structured combat-damage assignment.
- [ ] All required callbacks encountered by a complete Sligh game.
- [ ] Verified networked Ball Lightning trample playthrough.
- [ ] Complete game through lethal.
- [ ] Two Godot-controlled humans.

### UI

- [x] Opponent and local player areas with life and hand count.
- [x] Active-turn and priority indicators.
- [x] Shared turn/phase/stack strip.
- [x] Separate stable land and nonland battlefield rows for both players.
- [x] Local hand near the bottom and both graveyards.
- [x] Current-action prompt and Forge button controls.
- [x] Image-backed reusable `CardControl` for hand and battlefield.
- [x] Green actionable frame and 90-degree tapped rendering.
- [x] Right-click preview and missing-image/name fallback.

### Testing

- [x] Bridge ability-choice unit tests.
- [x] Bridge combat-damage/trample validation tests.
- [x] Standalone bridge-process integration test.
- [x] Sligh deck/opening/mulligan/ordinary-turn tests.
- [x] Network land and targeted-spell harnesses in source.
- [x] Blocking/Ball Lightning, Jackal Pup, London mulligan, and shutdown regressions.
- [ ] Godot UI/parser automated tests.
- [ ] Passing current external JSONL targeted-spell integration test; see sections 12 and 13.

## 7. Current Sligh Scope

`SlighMirrorDeck` builds this exact 60-card main deck for each player:

| Quantity | Card |
|---:|---|
| 21 | Mountain |
| 4 | Fireblast |
| 4 | Grim Lavamancer |
| 4 | Incinerate |
| 4 | Jackal Pup |
| 4 | Lightning Bolt |
| 4 | Mogg Fanatic |
| 4 | Seal of Fire |
| 3 | Cursed Scroll |
| 2 | Ball Lightning |
| 2 | Goblin Patrol |
| 2 | Price of Progress |
| 1 | Earthquake |
| 1 | Flame Rift |

There is no sideboard in this fixture. `SlighMirrorDeck.create()` resolves every name through Forge's common card database and fails if a card is missing or the total is not exactly 60.

This deck is the first compatibility target, not evidence that arbitrary Premodern decks work. It deliberately exercises targeted spells and abilities, player targets, sacrifice/alternative costs, graveyard costs, variable values, triggers, combat, and Ball Lightning's trample. Those interactions should be verified through play and generic callbacks rather than assumed from card presence in Forge.

Forge still loads and knows its much larger card database (the current test initialization reports 33,331 card files). Product/UI compatibility remains intentionally deck-scoped.

## 8. UI State

`Main.tscn` is a functional playtest table:

- connection status at the top;
- opponent header, indicators, land/nonland battlefield rows, and graveyard;
- shared turn, phase, and text stack strip;
- local header, indicators, nonland/land rows, and graveyard;
- local image-backed hand dock near the bottom;
- persistent right-side card preview;
- current-action prompt, pass/OK/cancel controls, action status, and query panel.

`Main.cs` rebuilds the zone controls from each authoritative state message. Empty battlefield rows remain present, avoiding large layout jumps. Forge's dynamic `isLand` value selects the battlefield row.

The same `CardControl` is used for visible hand and battlefield cards. Left-click requests the current Forge action only when the card ID is offered. Right-click opens the local preview. A green frame marks actionable cards; tapped battlefield cards rotate 90 degrees. Graveyards currently use compact text buttons with right-click preview rather than `CardControl`.

Player selection uses the local/opponent headers. The stack is currently a single text line showing source and card targets; it is not an interactive card stack and does not display player targets. Combat has no dedicated attacker/blocker visualization even though Forge can offer the underlying card selections.

The UI is a practical vertical slice, not final visual polish.

## 9. Card Image Strategy

Current behavior is intentionally simple:

- Developer-supplied WebP files live under `premodern-client/card-images/sligh/`.
- Both `.webp` files and Godot's `.webp.import` metadata are ignored by `premodern-client/.gitignore`.
- This workspace currently has one local image for each of the 14 unique Sligh card names, but those assets are not part of a fresh checkout.
- `CardImageCatalog` loads the directory once and caches `Texture2D` objects by normalized filename.
- Lookup uses normalized card name, for example `Lightning Bolt` → `lightning-bolt.webp`.
- Godot owns image presentation. The bridge treats card identity/state independently of artwork.
- A missing image falls back to the visible card name; gameplay does not depend on artwork.

There is no downloader, remote cache, set/printing selection, protocol `imageKey`, or oracle-name field yet. Those are possible future refinements, not current behavior. Image support should continue deck-by-deck until broader support has current product value.

## 10. Build and Run

### Requirements

- Java 17 (enforced by the Maven build).
- Maven 3.8.1 or newer.
- Godot .NET 4.7.x; the project references `Godot.NET.Sdk/4.7.1`.
- .NET 8 SDK for the desktop Godot project.
- Forge assets at `forge-gui/res`.
- Local WebP art is optional for function but required for image presentation.

### Build

From the repository root in Windows PowerShell:

```powershell
mvn --% -pl forge-gui-desktop,forge-bridge -am -DskipTests package
dotnet build "premodern-client/Premodern Client.csproj" -nologo
```

Both commands passed on 2026-07-23 at the commit listed above. The Maven command creates:

- `forge-bridge/target/forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar`;
- `forge-gui-desktop/target/forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar`;
- `forge-gui-desktop/target/test-classes`, containing the development hosts.

If the Forge version changes, update the JAR paths below and the `Main` node's `Bridge Jar Path`.

### Start the Sligh host

Run from the repository root. The process itself must use `forge-gui` as its working directory so Forge finds its assets:

```powershell
Push-Location forge-gui
java -cp "..\forge-gui-desktop\target\test-classes;..\forge-gui-desktop\target\forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar" forge.net.GodotSlighMirrorHostMain 36743
Pop-Location
```

This is currently **Godot human versus Forge AI**, despite both decks being the Sligh list. It waits up to 60 seconds for the bridge to join and become ready, then starts automatically.

### Start the deterministic G3 regression host

Use this when debugging the known land/Bolt/target/mana/stack path:

```powershell
Push-Location forge-gui
java -cp "..\forge-gui-desktop\target\test-classes;..\forge-gui-desktop\target\forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar" forge.net.GodotG3HostMain 36743
Pop-Location
```

### Run Godot

Open `premodern-client/project.godot` in the Godot .NET editor and run the project. The default `Main` properties are:

```text
Bridge JAR: ../forge-bridge/target/forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar
Assets:     ../forge-gui
Host:       localhost
Port:       36743
Username:   Godot Bridge Client
```

Relative paths are resolved from `premodern-client`. Starting the scene launches the bridge automatically. Change these exported properties in the Godot inspector when using a different host, port, username, Java executable, or artifact version.

### Shutdown

- Stop the Godot scene/window to close bridge stdin and dispose the child process.
- Press Enter in either development host after a playtest for its normal `finally` cleanup.
- Ctrl+C/JVM shutdown should be safe: `FServerManager` marks shutdown in progress before the channel close callback, tolerates shutdown-hook removal after JVM shutdown begins, and has a currently passing subprocess regression test.

## 11. Important Design Decisions

**Decision:** Finish one Sligh mirror before broad format/card support.  
**Why:** It provides a concrete compatibility target and exposes real interaction gaps without building speculative infrastructure for thousands of cards.

**Decision:** Forge owns game legality and authoritative state.  
**Why:** There should be one rules implementation; client-side legality would diverge and be difficult to validate.

**Decision:** Godot is presentation and input, not a second rules engine.  
**Why:** It can remain focused on player-visible state, usable controls, and rendering.

**Decision:** Prefer generic bridge primitives over card-specific messages or hacks.  
**Why:** Card, player, button, query, and amount-selection concepts can support the deck while preserving a stable boundary.

**Decision:** Keep the protocol visibility-safe, versioned, and correlated.  
**Why:** `schemaVersion`, `stateSequence`, `interactionSequence`, and `requestId` make stale actions and asynchronous behavior explicit without exposing mutable Forge internals.

**Decision:** Use one reusable image-backed `CardControl` for hand/battlefield cards.  
**Why:** Click, preview, actionable, tapped, image, and fallback behavior should not be reimplemented per zone. Compact graveyard and text stack views are current UI exceptions.

**Decision:** Keep artwork local and uncommitted.  
**Why:** Art is presentation data, not core game identity, and the current target only needs a small developer-supplied set.

**Decision:** Optimize for a single 1v1 match, not match/lobby infrastructure.  
**Why:** Matchmaking, simultaneous games, and public hosting do not advance the current proof.

**Decision:** Avoid unnecessary Forge-specific assumptions in Godot.  
**Why:** Generic snapshots and commands reduce coupling today and leave room for protocol evolution later.

## LONG-TERM STRETCH GOAL: FUTURE CUSTOM RULES ENGINE

A custom high-performance Magic rules engine—possibly in Rust or another systems language—is exploratory and **not** a current implementation goal. Do not begin a rewrite before the Forge-backed Sligh milestone works reliably.

Low-cost choices that are already useful should leave that door open: stable engine-agnostic boundaries, deterministic fixtures, structured command/state logs, replayable traces where practical, and golden scenarios for tricky behavior. Forge can serve as a behavioral oracle.

A valuable gradual asset would be verified scenarios of:

```text
initial state/deck setup
+ player commands and decisions
→ expected interactions, state transitions, and final outcome
```

Such artifacts could later support regression tests, replay/debug tools, and differential testing. Do not add infrastructure solely for this hypothetical engine. Build the Forge-backed client correctly today; when two current solutions are otherwise equal, prefer the one that leaves better boundaries, reproducibility, logs, and test artifacts.

## 12. Known Issues / Incomplete Areas

### Known Bugs

- **`JsonBridgeIntegrationTest` currently fails all three target variants.** On 2026-07-23, both a focused combined run and an isolated run reproduced timeouts for `card`, `opponent`, and `self`. The external driver reaches Lightning Bolt selection but does not complete target selection/resolution within 100 seconds. The logs also contain delta-checksum mismatch/full-state-resync warnings. This class had an older passing report, but the current repeated failure is the source of truth; root cause is not yet diagnosed.

### Not Yet Implemented / Deferred Within the Milestone

- A complete Sligh playthrough through lethal has not been demonstrated or automated.
- The bridge still reports `unsupportedQuery` and stops for required callbacks including generic amount assignment, confirm/option/input dialogs, general `getChoices`, ordering, sideboarding, entity-choice helpers, and card-list manipulation. A real Sligh playthrough must determine which need generic implementations now.
- `BridgeGuiGame` leaves many purely visual Forge GUI callbacks as no-ops. Authoritative full/delta state covers the implemented zones, but combat presentation is especially thin.
- State projection currently includes visible hand, battlefield, graveyard, and stack. It does not provide a complete combat graph, exile/library views, mana pool, game result screen, or every temporary/revealed zone.
- The stack UI is one clipped text line, shows card targets only, and is not interactive.
- There is no dedicated attacker/blocker layout or combat-state visualization.
- The Sligh host is one Godot human versus AI. Two remote humans, coordinated startup, match completion, and rematch/lobby UX are not built.
- Remote-friend connectivity, firewall/NAT guidance, packaging, and distribution are not finished. Defaults assume localhost development.
- Artwork requires external developer setup and card-name-based filenames.
- The Godot parser/state/UI has compile verification but no automated unit or scene tests.

The deliberately limited deck/card pool, lack of a deckbuilder, and lack of broad art download support are scope decisions, not bugs.

## 13. Testing / Verification

Important custom coverage:

- `AbilitySelectionBridgeTest` — single unambiguous trigger behavior and correlated multi-choice ability replies.
- `CombatDamageBridgeTest` — Ball Lightning-style trample assignment, one/multiple blockers, ordered lethal constraints, invalid-reply retry, unblocked/single-recipient fast paths, and zero damage.
- `StandaloneBridgeIntegrationTest` — a real bridge process connects, receives a normal network controller, emits versioned controller/state/interaction messages, and hides the opponent hand.
- `SlighMirrorH1Test` — exact deck recipe for both players, standalone bridge opening hand/mulligan controls, and a shuffled remote-human game reaching an ordinary turn.
- `NetworkPlayIntegrationTest` / `NetworkInteractionProbe` — network state/delta observation plus scripted land and targeted-spell controller paths.
- `BlockingCombatRegressionTest` — no-blocker, declined-block, legal-block, and blocked Ball Lightning engine behavior.
- `JackalPupTriggerRegressionTest` — the mandatory damage trigger resolves through the single-ability behavior.
- `LondonMulliganTest` — tuck timing and post-mulligan selection behavior.
- `FServerManagerShutdownTest` — repeated stop/restart and clean JVM shutdown-hook behavior.

Verification performed for this status snapshot:

- Maven package command: **passed**.
- Godot C# `dotnet build`: **passed**, 0 warnings and 0 errors.
- Bridge unit tests: **8/8 passed** (2 ability + 6 combat-damage).
- Selected desktop tests other than JSON: **11/11 invocations passed** (shutdown 2, Sligh 3, standalone bridge 1, blocking 4, Jackal Pup 1).
- `JsonBridgeIntegrationTest`: **0/3 passed** in both combined and isolated reruns; see section 12.

Major coverage gaps are a real Godot-driven full match, two humans, end-to-end networked trample, unsupported callback discovery/coverage, and automated C# protocol/UI tests.

## 14. Immediate Next Priorities

1. Diagnose and fix the reproducible `JsonBridgeIntegrationTest` target-flow timeout without weakening protocol validation.
2. Play complete Sligh-vs-AI games from mulligan through lethal and implement each encountered **generic** interaction callback.
3. Verify Ball Lightning combat and trample end-to-end over Forge TCP → bridge JSONL → Godot; add a regression scenario for the proven behavior.
4. Add the smallest workable two-human host/client flow and verify both humans can make every decision.
5. Improve only UI that materially blocks those playtests—especially combat state and stack/target clarity.
6. Preserve structured logs or golden traces from successful Sligh scenarios where that also helps present debugging and regression work.

## 15. Later / Explicitly Deferred

- Broad Premodern card-pool compatibility.
- Deckbuilder, sideboarding UX, and deck import.
- Matchmaking, accounts, lobbies, rematches, and multiple simultaneous matches.
- Public hosting, NAT traversal, and production remote deployment.
- Web frontend.
- Full card-image downloader, printing selection, or global cache.
- Polished animations, theme, audio, and final visual design.
- Mobile/export packaging.
- Replacing Forge with a custom rules engine.

## 16. Longer-Term Architectural Ideas

**NON-CURRENT / exploratory.**

A possible future authoritative engine could use a deterministic model such as:

```text
state + player/input command + RNG state
→ new state + events + pending decisions
```

Desirable properties would include deterministic execution, explicit commands/events/pending decisions, serializable or hashable state where practical, replayable matches, strong golden-scenario regression coverage, and no UI-specific legality. A stable engine-neutral protocol could allow Godot to remain largely independent of whether Forge or another engine supplies truth.

Forge is useful as a behavioral oracle. A gradually accumulated corpus should record:

- starting decks, seed/setup, and relevant initial state;
- player commands and human decisions;
- authoritative state changes and generated interactions;
- final outcomes.

That corpus could eventually drive replay tools, rule regressions, and differential tests between Forge and an experimental engine. It should be built only when the artifacts also improve today's Forge-backed debugging or verification.

This is not the current roadmap. Do not propose a Rust rewrite, Forge replacement, or new rules engine as an immediate task.

## 17. Context for Future AI/Codex Sessions

When continuing this project:

1. Read this file first, then inspect the current code and Git state before assuming every detail is still current.
2. Keep the immediate success criterion explicit: a complete **two-human Sligh mirror**, not general Magic compatibility.
3. Reproduce the current JSON integration failure before building on its previously passing history.
4. Keep Forge authoritative and prefer generic interaction primitives over card-specific hacks.
5. Make incremental changes and verify them with a focused fixture or regression test.
6. Do not casually modify unrelated upstream Forge code; investigate the existing controller/network path first.
7. Distinguish observed code/test behavior from architectural recommendations.
8. Preserve useful deterministic scenarios, logs, and expected state transitions when doing so helps the current milestone.

