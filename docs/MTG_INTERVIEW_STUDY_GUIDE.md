# MTG game client: interview study guide

**Audience and use:** A third-year Applied Computer Science student preparing to explain this project in a Software Developer Co-op interview. Allow about 150 minutes. This file is self-contained project evidence for a tutor that cannot inspect the repository. Paths are repository-relative. Terms such as *observed* mean visible in inspected source, not verified by a test run today.

**Evidence snapshot:** Inspected branch `spike/godot-bridge`, commit `47c6eb86c4d4002b7b24a0958c26f2cf6347de56` on 2026-09-28. At inspection start, `PROJECT_STATUS.md` was modified; `.codex/`, `docs/TWO_HUMAN_SLIGH.md`, and this guide were untracked. This guide was already present as an older untracked draft and has been updated in place. `PROJECT_STATUS.md` still labels its main checkpoint 2026-09-04 and describes a pre-two-human milestone; current source and tests take precedence. No build, test, game, or network session was run for this document. Existing Surefire reports and playthrough notes are identified as prior results, not current execution.

## 1. Project overview and verified scope — **must understand**

This is a small, unofficial client for a one-on-one Magic: The Gathering game using an exact Premodern-era Sligh deck as its first compatibility target. *Premodern* describes the intended card-format context; the inspected code establishes an exact 60-card Sligh fixture (`SlighMirrorDeck`), not general Premodern deck legality or support for arbitrary decks. Forge already supplies the rules engine, card definitions, game state, legal inputs, shuffling, and AI. The custom work supplies a focused Godot interface, a Java adapter, protocol, development hosts, and tests.

| Process / layer | Job | Connection |
|---|---|---|
| Forge host JVM | Owns the match and authoritative rules; `GodotTwoHumanSlighHostMain` opens two remote-human seats | Forge's existing network protocol over TCP, default `localhost:36743` |
| `forge-bridge` JVM | Joins that remote seat through `FGameClient`; translates Forge GUI/controller callbacks to visibility-safe JSON and validates returned input | Forge TCP on one side; UTF-8 JSONL on child-process stdin/stdout on the other |
| Godot/C# process | Starts the bridge, renders state, enables offered controls, sends player actions | Child-process streams, **not a direct Godot-to-host TCP socket** |

*Online* here means the game uses Forge's real TCP client/server path: separate bridge JVMs can join the same host. `GodotTwoHumanSlighHostMain.configureLobby` opens two seats, then requires two distinct ready remote clients. `TwoHumanSlighBridgeIntegrationTest` verifies independent controller identities and each player's private opening hand. A headless two-Godot-client probe verifies opening choices, first-turn progression, and shared land projection in separate modes, according to `docs/TWO_HUMAN_SLIGH.md`. These are localhost development arrangements; Internet deployment and a complete two-human game through lethal are not established.

*Authoritative* means Forge decides legal actions, targets, costs, random outcomes, resolution, life totals, and zones. Godot presents Forge-offered choices and waits for Forge state after input. An `actionAccepted` message means the bridge validated and passed an action to the network controller; it does **not** mean a spell resolved. *Versioned* means every JSONL message has `schemaVersion:1`; both sides reject missing or unsupported versions. There is one implemented schema, not a multi-version migration system.

What works in source: opening hand and Keep/Mulligan, visible state and turn/phase/priority, card and player targets, buttons and priority passing, ability and generic choices, trigger ordering, reveal events, combat selection and damage assignment UI. The external-process `JsonBridgeIntegrationTest` has card-, opponent-, self-, and Cursed Scroll scenarios; its latest inspected Surefire report shows only the **scroll** case passing in that run. The separate two-bridge test report shows one passing opening-state test. `docs/TWO_HUMAN_SLIGH.md` records prior two-Godot-client probes through turn two and a land play visible to both clients. It also says combat, targeting, and lethal still require a longer two-client playtest. No full two-human match through lethal or in-client winner/result display is established. Some Forge callbacks remain unsupported, including dialogs, generic amount assignment, sideboarding, and partial selection-plus-order. The app has no separate application database; Forge loads its existing card database.

**Small file map:** `premodern-client/Main.cs` (`_Ready`, `_Process`, `OnCardPressed`, `OnPlayerPressed`, `RenderUi`) is the UI entry point; `Bridge/BridgeProcessClient.cs` owns the Java child and stream tasks; `Protocol/BridgeCommands.cs`, `BridgeProtocolParser.cs`, `BridgeMessages.cs`, and `BridgeClientState.cs` define the C# protocol and reducer. `forge-bridge/src/main/java/forge/bridge/BridgeMain.java` starts the adapter; `BridgeSession.java` joins the lobby; `JsonLineTransport.java` frames JSONL; `BridgeProtocol.java` validates and correlates; `BridgeGuiGame.java` adapts Forge callbacks; `BridgePrinter.java` projects state. `forge-bridge/PROTOCOL.md` documents schema v1. Forge's `FGameClient` and `NetGameController` provide the TCP boundary. `forge-gui-desktop/src/test/java/forge/net/JsonBridgeIntegrationTest.java`, `TwoHumanSlighBridgeIntegrationTest.java`, and their drivers test the host/bridge path; `premodern-client/tests/TwoClientOpeningProbe.gd` tests the Godot scene path.

## 2. Resume claim audit — **must understand**

**Claim 1: “Built a C# application in Godot using the existing Forge Java rules engine and a versioned TCP/JSONL protocol for online one-on-one card-game matches.”** Supporting source: `premodern-client/project.godot`, `Main.cs`, `BridgeProcessClient.cs`; `BridgeMain`, `BridgeSession`, `FGameClient`, `GodotTwoHumanSlighHostMain`, and `forge-bridge/PROTOCOL.md`. `TwoHumanSlighBridgeIntegrationTest` starts two independent bridge processes for two human seats; `TwoClientOpeningProbe.gd` exercises two Godot scenes. This establishes the implemented two-client prototype, Forge TCP, and schema-v1 JSONL. Qualification: TCP and JSONL are **different links**, and the protocol version belongs to JSONL. The two-Godot probe stops well before a complete match; Internet-ready matchmaking is unproven. The host is a development class in test sources, not a packaged online service. Interview follow-ups: (1) Which process owns the TCP connection, and which link carries JSONL? (2) What has actually been demonstrated with two Godot clients?

**Claim 2: “Implemented player interactions and authoritative game-state updates, including target selection, spell and ability resolution and turn phases.”** Supporting source: `BridgeGuiGame.handleCommand`, `BridgePrinter.snapshotState`, `Main.RenderUi`, `BridgeClientState.Apply`; external Bolt/Cursed Scroll scenarios inspect target offers, stack, life or graveyard after resolution. `state` includes `turn`, `phase`, `activePlayerId`, `priorityPlayerId`, players, and stack. This establishes an input/projection path for those interactions. Qualification: Forge implements resolution; custom code *exposes and drives* its choices and applies resulting projections. The stack snapshot lists card targets, while player targets are offered as player IDs and resolution is visible through life changes. There is no general game-result projection. Follow-ups: (1) Why is `actionAccepted` insufficient to show a resolved spell? (2) How are player targets different from card targets in the UI and bridge?

**Claim 3: “Added Java integration tests and headless scenarios for targeting, resolution and protocol behavior without fixed sleeps or prompt matching.”** Supporting source: `JsonBridgeIntegrationTest`, `ExternalJsonDriverMain`, `ExternalCursedScrollDriverMain`, `TwoHumanSlighBridgeIntegrationTest`, and `premodern-client/tests/TwoClientOpeningProbe.gd`. The Bolt and Scroll drivers choose by structured phase, priority, IDs, queries, stack, and final state rather than natural-language prompt text. The two-bridge Java test uses a `CountDownLatch` and message queues. Qualification: the blanket **“without fixed sleeps” is false**: `JsonBridgeIntegrationTest.waitFor` polls readiness with `Thread.sleep(100)`, and the Godot probe runner polls process/log progress with `Start-Sleep -Milliseconds 250`. Those are bounded condition polls, not blind waits for resolution. Tests do match some structured `reason` values and visible **button labels** such as Play/Draw or Keep, though the Bolt driver does not parse natural-language prompt wording. The latest inspected report shows only one passing Scroll invocation, not all four data-provider scenarios. Better wording: “Added Java integration and headless scenarios driven by structured state and choices, with bounded waits; setup uses short polling intervals.” Follow-ups: (1) How does the Bolt driver know when to select a target? (2) Which tests exercise the real Godot UI versus a substitute driver?

**Attribution boundary:** Source and commit history show implementation and an account attached to some commits; they cannot establish what you personally designed or wrote without AI assistance. Before speaking in first person, separate what you personally did, what you reviewed, and what AI generated. Explain the code you can defend rather than claiming independent authorship from repository presence.

## 3. Trace one real interaction: Bolt targets Llanowar Elves — **must understand**

The following is the path implemented for the `JsonBridgeIntegrationTest` card-target scenario. Its C# leg is source-traced; the automated external test swaps in `ExternalJsonDriverMain` at the JSONL boundary. Numbers in messages below are **illustrative IDs**, not captured test output.

1. Forge's host runs a small test deck: AI has Forests and Llanowar Elves; the remote seat has Mountains and Lightning Bolt. The bridge joins the open seat. Forge sends its `GameView`, deltas, prompt and selectables through `RemoteClientGuiGame`/`ProtocolMethod` over Forge TCP. `BridgeGuiGame.setGameView` or `applyDelta` calls `observeState`; `BridgePrinter.snapshotState` publishes a visibility-safe JSONL `state` with phase, priority, hands, battlefield, graveyards, stack.
2. Godot's `BridgeProcessClient.ReadStdoutAsync` reads lines and `BridgeProtocolParser.Parse` creates typed messages. `Main._Process` dequeues them, calls `clientState.Apply`, then `RenderUi`. Bolt in the visible hand becomes clickable only when its card ID is Forge-offered as selectable/weakly selectable and the client can send an async action.
3. A left click calls `Main.OnCardPressed`, then `SendAsyncCommand(new SelectCardCommand(cardId, interactionSequence))`. `BridgeCommandSerializer.Serialize` adds schema v1 and fields. `BridgeProcessClient.TrySend` queues it; a single stdin-writer task writes and flushes one line.

**Shortened source excerpt 1 from `premodern-client/Protocol/BridgeCommands.cs`** (the orientation comment marks an omitted switch header):

```csharp
public sealed record SelectCardCommand(int CardId, long InteractionSequence)
    : AsyncInteractionCommand("selectCard", InteractionSequence);
// Inside BridgeCommandSerializer.Serialize:
SelectCardCommand selectCard => new {
    schemaVersion = BridgeSchema.SupportedVersion,
    type = selectCard.Type,
    cardId = selectCard.CardId,
    interactionSequence = selectCard.InteractionSequence
},
```

`record` provides a typed immutable data carrier; the switch arm converts it into a small JSON transfer object. `interactionSequence` refers to the current **interaction**, not a state snapshot.

4. `JsonLineTransport` reads the complete line as a JSON object. `BridgeProtocol.handleInput` checks schema, type, required ID and interaction sequence, then schedules the action on the bridge GUI event thread via `BridgeGuiBase.invokeInEdtLater`. `BridgeGuiGame.handleCommand` rejects an old sequence or an ID outside currently offered cards. If valid, it calls the Forge-provided `IGameController.selectCard(card, null, null)`. `NetGameController.selectCard` sends a Forge `ProtocolMethod.selectCard` event to the host over TCP. Forge processes casting, choices, target legality, mana payment, priority, stack, and resolution.

**Shortened source excerpt 2 from `forge-bridge/src/main/java/forge/bridge/BridgeGuiGame.java`** (the surrounding switch branches are omitted):

```java
if (command.interactionSequence() != protocol.currentInteractionSequence()
        || protocol.currentInteractionSequence() == 0) {
    protocol.staleInteraction(command.interactionSequence());
    return;
}
CardView card = selectableCards.get(command.selectedId());
if (card == null) { card = weaklySelectableCards.get(command.selectedId()); }
if (card == null) { card = combatSelectableCards.get(command.selectedId()); }
if (card == null) {
    protocol.error("cardNotOffered", "cardId is not currently selectable", null);
    return;
}
gameController.selectCard(card, null, null);
protocol.actionAccepted("selectCard", card.getId(), command.interactionSequence());
```

The actual switch has parallel `selectPlayer`, button, and pass-priority branches. The controller call submits input to Forge; `actionAccepted` only acknowledges that boundary.

5. If Forge offers several abilities, `BridgeGuiGame.getAbilityToPlay` emits an `abilityChoice` query; Godot returns the offered `selectedId` with its `requestId`. A single unambiguous ability may be chosen by the adapter without a query. Forge then offers target cards and players through distinct selectable lists. The card-target scenario clicks Llanowar Elves with a **new** current interaction sequence. Later, Forge may offer a Mountain as a mana source. The player passes priority with another correlated async command. These are separate inputs, not one “cast Bolt” RPC.
6. New Forge full/delta callbacks cause a projected state with Bolt on the stack and its card target; after passes and resolution, a later state shows Llanowar Elves in the AI graveyard. Godot `BridgeClientState.Apply` stores that state; `Main.RenderUi` displays it. The test asserts the stack target and later graveyard state rather than assuming success from click or acknowledgement.

**Illustrative, abbreviated before/after messages** (IDs and sequence values chosen only to teach the flow):

```json
{"schemaVersion":1,"type":"state","stateSequence":51,"phase":"MAIN1","priorityPlayerId":2,"players":[{"id":2,"handVisible":[{"id":101,"name":"Lightning Bolt"}]},{"id":1,"battlefield":[{"id":202,"name":"Llanowar Elves"}]}],"stack":[]}
{"schemaVersion":1,"type":"interaction","interactionSequence":42,"weaklySelectableCardIds":[101],"selectableCardIds":[],"selectablePlayerIds":[]}
{"schemaVersion":1,"type":"selectCard","interactionSequence":42,"cardId":101}
{"schemaVersion":1,"type":"actionAccepted","action":"selectCard","selectedId":101,"interactionSequence":42}
... Forge offers ability, target 202, mana source, and priority in later interactions ...
{"schemaVersion":1,"type":"state","stateSequence":58,"stack":[{"source":{"id":101,"name":"Lightning Bolt"},"targets":[{"id":202,"name":"Llanowar Elves"}]}]}
{"schemaVersion":1,"type":"state","stateSequence":63,"players":[{"id":1,"battlefield":[],"graveyard":[{"id":202,"name":"Llanowar Elves"}]}],"stack":[]}
```

These shortened lines omit required fields from real `state`/`interaction` records; they are **illustrations, not valid full fixtures**. An invalid path: sending `selectCard` with sequence 41 after current sequence 42 yields `STALE_INTERACTION` and Forge is not called. Sending current sequence but an unoffered card gives `cardNotOffered`. The client also checks its rendered sequence, but Java revalidates because the UI can be stale or another sender can write JSONL.


## 4. Networking and protocol essentials — **must understand**

TCP carries bytes in order, but a `read` has no promise to return one logical message. One JSON object might arrive in pieces, or several may arrive together. The Forge host↔bridge link uses Forge's existing Netty object encoding/decoding (`FGameClient`, `ProtocolMethod`), **not** JSONL. The Godot↔bridge link uses two redirected standard streams and newline-delimited JSON: one complete UTF-8 JSON object per nonempty line, with stdout reserved for protocol and stderr for diagnostics. `BridgeMain` redirects `System.out` to stderr before Forge initializes, while retaining the original stdout for JSONL.

**Shortened source excerpt 3 from `forge-bridge/src/main/java/forge/bridge/JsonLineTransport.java`** (the try/catch and thread setup are omitted):

```java
this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
while (!closed.get() && (line = input.readLine()) != null) {
    if (!JsonParser.parseString(line).isJsonObject()) {
        errorHandler.accept("Each input line must be a JSON object");
        continue;
    }
    messageHandler.accept(JsonParser.parseString(line).getAsJsonObject());
}
```

`BufferedReader` handles partial byte deliveries and UTF-8 decoding; `readLine` waits for the newline. C# uses `StreamReader.ReadLineAsync` for bridge stdout and a separate reader for stderr. The C# writer has a `Channel<string>` with one reader task, so concurrent UI sends are serialized into full lines and flushed. Java queues outbound JSON lines to one writer thread. This is sufficient framing for the process streams; there is no manual length prefix. A newline within JSON text is escaped by serialization. Java explicitly selects UTF-8 on input; `BridgeProcessClient.Start` does not explicitly set `ProcessStartInfo` stream encodings, so portable C# encoding behavior beyond the tested environment is not established by this source.

**Fragmentation walkthrough:** Suppose one logical command is `{"schemaVersion":1,"type":"passPriority","interactionSequence":42}\n`. An underlying read might return `{"schemaVer`, then `sion":1,"type":"pass`, then `Priority","interactionSequence":42}\n{"schemaVersion":1,`. The line reader buffers the first two fragments, emits exactly one command when it sees the first newline, then retains the start of the next object. The bridge parser never receives the partial fragments. Two full lines arriving in a single read are emitted separately.

Three identifiers solve different problems. `schemaVersion` checks message format; no negotiation is implemented. `interactionSequence` increases with each asynchronous Forge interaction snapshot; `selectCard`, `selectPlayer`, `button`, and `passPriority` must echo the **current** one. `requestId` (`q-...`) correlates a synchronous `query` and `reply`, such as ability choice, bounded generic choice, ordering, or combat damage. The bridge keeps pending query maps and validates that a reply refers to an offered choice; invalid generic-choice replies emit an error and reissue the pending query with the same ID. `stateSequence` labels full/delta state from Forge; clients do not echo it. Inspection of `BridgeClientState.Apply` shows it replaces `LatestState` as messages arrive; it does not implement an explicit monotonic `stateSequence` rejection check. Do not claim one.

On malformed inbound JSON, `JsonLineTransport` reports `invalidJson` through `BridgeProtocol`; non-object lines are errors, and unknown input types produce `unknownMessageType`. The C# parser turns malformed, missing-version, or unsupported-version bridge output into `ProtocolProblemMessage`, which the reducer displays as an error. A missing/unsupported input version is rejected before Forge action dispatch. `readLine` can return a final unterminated line at EOF, so a syntactically complete final object can still be parsed even though the documented sender convention requires a newline. EOF on bridge stdin calls `BridgeSession.inputClosed`, releasing its stopped latch. A Forge network close emits a `disconnected` lifecycle event. On C# scene exit, `BridgeProcessClient.Dispose` completes its writer, closes stdin, waits briefly, then kills the child if necessary; reader errors become diagnostics. Required generic choices normally time out after 30 seconds and fail the session; item-ordering timeout has an original-order fallback. Do not assume all queries use the same policy.

Observed limitations: no evidence of automatic reconnect, message replay, schema upgrades, or an application-level guarantee that every accepted action resolves. A possible improvement would test line fragmentation directly and add explicit state-order handling if Forge can deliver out-of-order projections; neither is claimed as implemented.

## 5. C#, Java, and design concepts — **useful familiarity**

`IGameController` and `IGuiGame` are Forge interfaces. The bridge implements a GUI callback surface and holds Forge's controller rather than recreating legal-action logic. `NetGameController` forwards controller methods to the host. This is an adapter boundary: Forge speaks card/player views and callbacks; Godot speaks small JSON data-transfer objects (DTOs) containing IDs and visible fields. `BridgePrinter.snapshotCard` calls `card.canBeShownToAny(localPlayers)` before including a name; hidden hands retain counts but not identities or `isLand` type. The UI must not infer hidden card types.

The C# protocol uses `record` types and a switch serializer; Java uses Gson records/envelopes and maps query IDs to original Forge objects. An opaque `optionId` is an identity for one offered choice, not a card name. This matters when two items share a display label. `BridgeProtocol.queryAbility` maps `SpellAbilityView.getId()` to the offered view, while `getAbilityToPlay` can return a sole unambiguous choice directly. Neither side exposes mutable `SpellAbility` to Godot.

Threading: Java has transport reader/writer threads; parsed async actions are scheduled onto the bridge GUI event thread. A synchronous query uses a `CompletableFuture` and a bounded wait for the reply. C# has separate stdout/stderr reader tasks and one stdin writer task. `Main._Process` drains a thread-safe queue and performs Godot rendering on the scene loop, avoiding background task UI mutations. Input handlers register through Godot button `Pressed` events. A clicked control only sends a command; the reducer waits for state.

**Shortened source excerpt 4 from `premodern-client/Protocol/BridgeClientState.cs`** (other message cases are omitted):

```csharp
case StateMessage state:
    LatestState = state;
    if (PendingAsyncCommand != null && pendingAsyncAccepted) {
        PendingAsyncCommand = null;
        pendingAsyncAccepted = false;
        LastActionStatus = "Forge returned authoritative state after the accepted action.";
    }
    break;
case ActionAcceptedMessage accepted:
    pendingAsyncAccepted = PendingAsyncCommand?.InteractionSequence
        == accepted.InteractionSequence;
    break;
```

The nullable `?.` safely reads the pending command's sequence. Acceptance alone sets a flag; later state clears the pending command. A new interaction can also clear it, and a rejection sets an error. This is **UI coordination**, not a proof of resolved gameplay. Resource cleanup is explicit: `BridgeSession.close` closes the Forge client and protocol; `BridgeProcessClient.Dispose` is idempotent and bounds shutdown waits. Testability comes from narrow seams: bridge unit tests feed piped JSON into `JsonLineTransport`, while process tests replace the Godot UI with an external driver.

## 6. Testing and debugging — **must understand**

`JsonBridgeIntegrationTest.testExternalJsonDriverCastsLightningBolt` starts a real `FServerManager` and lobby, AI seat, separate bridge JAR, and separate Java JSONL driver. Pipe threads forward the driver's stdout to bridge stdin and bridge stdout to driver stdin, while capturing both transcripts and separate diagnostics. The test waits for the lobby's remote seat to be `REMOTE` and ready, then starts Forge. The driver reads **messages**, advancing a stage machine when structured state and offered IDs match: local `MAIN1` priority, Mountain/Bolt in hand, Elves on AI battlefield, a target offered, Bolt on stack, then authoritative final graveyard or life total. It answers an ability query only if one is actually offered. The harness asserts schema v1, increasing interaction sequences, missing/unsupported schema rejection, stale-action rejection, target offers, explicit selection, priority pass, stack target, and final outcome. It has a 150-second test timeout, 90-second driver failure deadline, bounded process waits, and readiness polling with `Thread.sleep(100)`. Timeouts prevent hangs and explain failures; they do not prove low latency or deterministic performance.

**Faithful test pseudocode, translating `JsonBridgeIntegrationTest` and `ExternalJsonDriverMain` (not source syntax):**

```text
start real Forge TCP host with AI and open remote seat
start bridge JVM and external JSONL driver; pipe complete lines both ways
wait until lobby reports remote and ready, within deadline; start game
for each structured message: update stage or choose only offered ID
on Bolt target offer: send selectCard(Elves ID, current interactionSequence)
on stack plus priority: send passPriority(current interactionSequence)
after exit: assert Bolt stack target was Elves, then Elves appeared in graveyard
```

`TwoHumanSlighBridgeIntegrationTest` starts one real Forge server and two separate bridge JVMs with different names, using the exact normally shuffled Sligh fixture. A `CountDownLatch` waits up to 30 seconds for two ready remote seats; per-process message queues then wait up to 60 seconds for distinct `controller` IDs and opening `state`. Assertions require each client to see its own seven card identities but only the opponent's hand count, plus a Play/Draw choice sent by the offered seat. This is a real network path with no Godot UI and stops at opening state. Its focused Surefire report records 1/1 passing; that report predates this document session. The test uses button-label checks, not arbitrary prompt prose.

`premodern-client/tests/TwoClientOpeningProbe.gd`, launched twice by `Run-TwoClientOpeningProbe.ps1`, is the complementary scene-level check. It presses rendered Play and Keep controls, then optionally passes priority to turn two or plays a Forge-selectable Mountain. The runner checks process exits, log markers, and no protocol errors. `docs/TWO_HUMAN_SLIGH.md` records prior passing turn and land-projection runs; these modes do **not** test combat, targeting, or lethal in two Godot clients. The PowerShell runner uses 250 ms condition polling and a deadline, and the GDScript probe reads labels such as `Keep` and `TURN 2`. They are headless Godot scenarios, not Java tests.

`SlighMirrorH1Test` checks the 60-card recipe, opening hand and mulligan controls, and an ordinary turn in an earlier one-human-plus-AI arrangement. `AbilitySelectionBridgeTest` and `GenericChoiceBridgeTest` exercise focused bridge behavior using Forge views and substituted transport/listener components; they do not exercise host TCP or Godot. `ExternalCursedScrollDriverMain` drives target, name choice, reveal, damage, and continuation through JSONL, substituting for the UI.

The older `PROJECT_STATUS.md` records focused Bolt-card and Cursed Scroll passes against AI. The inspected `forge-gui-desktop/target/surefire-reports` files record one passing `JsonBridgeIntegrationTest` **scroll** invocation and one passing `TwoHumanSlighBridgeIntegrationTest` opening-state invocation. No test was executed for this document, and report files alone do not prove the current working tree passes. The Java Bolt test proves its fixture and process path when run; it does not exercise the Godot scene. The Godot probe covers the scene but not Bolt resolution. None establishes arbitrary Premodern cards, a full two-human match, or Internet connectivity.

**Repository-supported debugging case:** Commit `7c1eb07342b` is titled “london mulligan fix”; current `LondonMulligan.afterMulligan` bottoms cards only when `tuckCardsDuringMulligan()>0`, after the player keeps. `InputLondonMulligan.showMessage` exposes actual hand `CardView`s as selectables and enables OK after the required number are selected. `LondonMulliganTest.keepOpeningSevenDoesNotBottomCards` checks a kept seven stays seven; `firstMulliganDrawsSevenThenBottomsOneAfterKeep` checks seven before Keep and six afterward. This identifies a rules/input boundary problem rather than a purely visual one. The commit and test do not establish who conceived or hand-wrote the fix; do not tell an ownership story unsupported by evidence.

For debugging generally, capture the last Forge offer, JSONL `interactionSequence`/`requestId`, command, bridge error or acknowledgement, then next Forge state. If an Elves click appears to do nothing, check whether its ID was offered at that sequence. If rejected, inspect the protocol/bridge boundary. If accepted but the game does not advance, trace Forge controller/input and pending choices. If Forge advances but Godot does not show it, inspect state projection, parsing, reducer, and rendering. This narrows the first boundary that failed.

## 7. Active-study exercises — **must answer before reading key**

### Architecture and claims

1. Give a 30-second architecture explanation naming all three processes and both communication links.
2. A recruiter says, “So your two-client test proves two friends can play any Premodern deck to lethal over the Internet?” Correct this precisely, naming both two-client tests.
3. What does “Forge is authoritative” mean for a Bolt click and for life totals?
4. Which project evidence supports “database experience,” and what should you avoid claiming?
5. Which parts of the second resume bullet belong to Forge, and which to the custom client/bridge?

### Trace and protocol

6. You receive a `state` with Bolt in hand, then an `interaction` offering its ID. What happens from clicking it until an acknowledgement?
7. Predict the response to `{"schemaVersion":1,"type":"selectCard","cardId":101,"interactionSequence":41}` when the bridge's current interaction is 42.
8. What if the sequence is 42 but card 101 is not in any offered-card map?
9. Why must a player target use `selectPlayer` instead of inventing a card ID? Where will player-target damage become visible?
10. A query has `requestId:"q-7"` and two ability IDs. Why is replying with an `interactionSequence` or description insufficient?
11. Explain what happens if one JSON line is split across three reads, then a second line arrives in the third read.
12. Explain the difference between `stateSequence`, `interactionSequence`, and `schemaVersion`.

### Code, debugging, and tests

13. In excerpt 4, why does `actionAccepted` leave the pending command in place?
14. A Bolt `selectCard` is acknowledged, but Elves remains on the battlefield. Name at least three plausible next checks without assuming the UI is wrong.
15. Compare the real and substitute components in the Bolt integration test, the two-bridge Java test, and the two-Godot probe. What does each stop short of proving?
16. How would you restate the “without fixed sleeps or prompt matching” claim accurately? Distinguish gameplay progression, setup polling, and button-label checks.
17. **Small pseudocode exercise:** Write a JSONL line receiver for a byte stream. It should handle partial UTF-8 input, two messages in one read, blank lines, EOF, and malformed JSON. State which library primitive you would use in this project.
18. **Small implementation exercise:** Sketch `validateSelectCard(command, currentSequence, offeredIds)` returning either `STALE_INTERACTION`, `cardNotOffered`, or an accepted card ID. Explain where the network controller call belongs. Propose one focused test for the validation.

## 8. 150-minute study route and interview readiness

| Minutes | Action | Priority |
|---:|---|---|
| 0–20 | Read overview and claim audit; say the three-process architecture aloud twice | Must understand |
| 20–50 | Walk the Bolt trace from click to final state; reconstruct it without looking | Must understand |
| 50–75 | Study JSONL, identifiers, errors; do exercises 6–12 | Must understand |
| 75–100 | Study tests/debugging and do exercises 13–16 | Must understand |
| 100–120 | Read design concepts, do the two pseudocode exercises, then grade them | Useful familiarity |
| 120–140 | Answer exercises 1–5 aloud and practise the five follow-ups below | Must understand |
| 140–150 | Review wrong answers and rehearse a factual project summary | Must understand |

Optional if time remains: trigger ordering, combat-damage recipient details, Cursed Scroll reveal handling, and exact mulligan class internals. They show depth but are less central than the architecture, action path, and test boundary.

**Factual summary to adapt, not memorize as personal attribution:** “This repository contains a Godot C# client for a narrow Sligh game using Forge's Java rules engine. Each client launches a Java bridge and exchanges schema-v1 JSONL over process streams; each bridge joins a Forge host over Forge TCP. Forge supplies legal inputs and authoritative state. The client renders those inputs and returns correlated choices. A Java test proves two independent bridge seats and private opening hands; headless Godot probes cover opening choices, turn progression, and a shared land update. Separate Java drivers cover targeted Bolt and Cursed Scroll scenarios. A complete two-human match through lethal remains unverified.”

**Five likely technical follow-ups:** (1) Which link is TCP and which is JSONL, and how is JSONL framed? (2) How are stale actions and synchronous query replies correlated differently? (3) How do you prevent hidden opponent-hand information leaking? (4) What do the Bolt, two-bridge, and two-Godot tests each prove? (5) How would you diagnose an accepted click followed by no visible change?

**Readiness checklist:** Can you draw all three processes and both protocols? Can you trace one action through exact classes and explain `actionAccepted` versus final state? Can you explain one stale-input error, one query reply, and UTF-8 line framing? Can you describe the three test scopes, driver substitute, waits, and limits? Can you say clearly that two Godot clients have been probed through opening/turn/land, while broad format support, a complete two-human game, and result UI are unproven? If asked about AI assistance, answer truthfully about your own work and explain the implementation you have actually studied; do not infer your history from commits.

## 9. Instructions for the downstream tutor

Use this guide as the only project-specific evidence. Begin with a brief explanation, then teach and quiz **one small topic at a time**. Ask the student to reason before revealing answers. Prefer active recall, execution traces, and the two small implementation exercises. Use the separate answer key to assess reasoning, including common mistakes. Explain unfamiliar terminology as it arises. Distinguish general software knowledge from documented project behavior. Never invent missing implementation details, test results, design motivations, or personal history. Keep the session within approximately 150 minutes and revisit weak areas before finishing.

## 10. Separate answer key — tutor: assess reasoning, not phrasing

1. Godot/C# UI launches bridge JVM and speaks UTF-8 JSONL over child streams; bridge joins Forge host over Forge's TCP network protocol; host JVM owns rules. Common mistake: claiming Godot directly sends JSONL over TCP to Forge.
2. `TwoHumanSlighBridgeIntegrationTest` proves two distinct remote bridge seats and private opening states; `TwoClientOpeningProbe.gd` proves two actual Godot clients can handle opening choices, turn progression, and shared land projection in separate modes. The development host uses localhost by default. Broad deck support, Internet deployment, and a full two-human match through lethal are unproven. Common mistake: overlooking the genuine two-client work or extrapolating it to complete games.
3. The UI sends an offered ID; Forge decides legality, costs, target, stack, damage, and resulting life/zone state. Godot renders later state. Common mistake: treating a click or `actionAccepted` as resolved damage.
4. Forge's existing card database is loaded through `FModel`/`CardDb` and used by `SlighMirrorDeck`; no custom SQL schema, queries, or persistence layer is established. Common mistake: calling Forge's card data a database implementation you built.
5. Forge owns spell/ability rules and resolution. Custom layers translate GUI/controller input and state into JSON DTOs, validate correlation/offered IDs, render turn/phase/priority and targets, and send choices. Common mistake: saying the Godot app itself resolves spells.
6. `OnCardPressed` creates `SelectCardCommand`; serializer adds schema/type/ID/sequence; `TrySend` queues a line; bridge parses and validates, invokes `IGameController.selectCard` on GUI thread, which sends Forge TCP event; bridge emits `actionAccepted`. A later state establishes gameplay change. Common mistake: skipping the Java bridge or assuming one RPC completes casting.
7. `STALE_INTERACTION`; Forge action is not called. Common mistake: silently replacing 41 with 42 and retrying.
8. `cardNotOffered`; an ID alone is not authorization. Common mistake: checking only whether the card exists in visible state.
9. Forge offers players separately (`selectablePlayerIds`), and `IGameController.selectPlayer` is the correct method. Damage appears in a later player `life` field; the stack projection lists card targets only. Common mistake: fake card identity for the player.
10. `reply` needs the exact pending `requestId` and offered `selectedId`; descriptions can duplicate and async sequences serve another purpose. Common mistake: using a label as identity.
11. A buffered UTF-8 line reader waits for newline, emits one complete line, and retains bytes/characters after it for the next line. Common mistake: parsing each raw read as JSON.
12. `schemaVersion` is JSON shape version (1); `interactionSequence` correlates async offered actions and must be current; `stateSequence` labels Forge state ordering and is not echoed. Common mistake: interchangeability or claiming client rejects old states explicitly.
13. It acknowledges bridge validation/submission, not authoritative completion. A later state or changed interaction clears pending coordination. Common mistake: treating acceptance as card movement or damage.
14. Check for a pending ability query, target offer and response, mana-source selection, priority passes, stack state, bridge errors, then host state. Acknowledged selection alone says little about resolution. Common mistake: immediately changing Godot's battlefield locally.
15. Bolt: real Forge host, AI opponent, bridge and TCP/JSONL path; Java driver substitutes for Godot. Two-bridge test: real Forge host and two bridge JVMs, but no Godot scenes; it stops at opening state. Two-Godot probe: real scenes, bridges, and host, but only opening/turn/land modes, not Bolt or lethal. Common mistake: treating these as one test covering all layers and all gameplay.
16. “The Bolt/Scroll drivers advance from structured state and offered IDs rather than natural-language prompt text. The harness and Godot runner use short bounded polling sleeps, and some checks depend on button labels.” Common mistake: claiming no sleeps or textual checks anywhere.
17. Expected reasoning: use a UTF-8 decoding `StreamReader`/`BufferedReader` and `ReadLineAsync`/`readLine`, or keep a buffer plus incremental decoder if working with raw bytes. **Explanatory pseudocode:**

   ```text
   while (line = readUtf8Line()) is not EOF:
       if line is blank: continue
       try: requireObject(parseJson(line)); dispatch(object)
       catch parse/type error: report invalidJson
   on EOF: close session
   ```

   A line reader may return a final unterminated line before EOF; the implementation parses it if it is complete JSON. Common mistake: parse each raw read as a message or discard a split multibyte character.
18. Expected reasoning and **explanatory pseudocode:**

   ```text
   if currentSequence == 0 or command.sequence != currentSequence: return STALE_INTERACTION
   card = strong[command.id] ?? weak[command.id] ?? combat[command.id]
   if card is missing: return cardNotOffered
   on bridge GUI thread: controller.selectCard(card)
   then emit actionAccepted(command.sequence, card.id)
   ```

   Test a stale sequence with an otherwise offered ID and a current sequence with an unoffered ID: neither may call the controller. A valid command should call it once. Common mistake: retry stale input with a newer sequence or accept any visible card.


