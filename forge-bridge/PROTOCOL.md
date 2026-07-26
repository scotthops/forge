# Forge Bridge JSONL Protocol

## Transport

The bridge reads UTF-8 newline-delimited JSON from standard input and writes UTF-8
newline-delimited JSON to standard output. Each non-empty line is exactly one complete JSON
object. Standard output contains protocol messages only; Forge diagnostics and logging go to
standard error.

The current schema version is `1`. Every message in either direction must contain:

```json
{"schemaVersion":1,"type":"..."}
```

The bridge rejects a missing `schemaVersion` with `MISSING_SCHEMA_VERSION` and any version other
than `1` with `UNSUPPORTED_SCHEMA_VERSION`. Rejected messages do not invoke Forge actions.

## Forge To Client

- `lifecycle`: Bridge process, model, lobby, and connection events.
- `controller`: Reports creation of the remote player's normal Forge controller.
- `state`: An immutable, visibility-safe projection of authoritative Forge state.
- `interaction`: The current asynchronous prompt, buttons, and selectable object IDs.
- `query`: A synchronous callback that requires one correlated `reply`.
- `actionAccepted`: Confirms that an asynchronous action passed validation and was sent through
  `IGameController`.
- `error`: A structured protocol or validation failure.

Visible card snapshots include `isLand`, derived from the card's current Forge type. The value is
`true` for any card whose current type includes Land, including artifact lands and creature lands.
It is always `false` for hidden cards so the projection does not reveal private type information.

State messages also include `combat`, one entry per attacking card:

```json
{"attackerCardId":101,"blockerCardIds":[201,202]}
```

The IDs come from Forge's `CombatView`. During blocker declaration, `blockerCardIds` reflects
Forge's current planned blockers, so a relationship appears only after Forge has processed the
selection and published authoritative state. An attacker with no blockers has an empty array.
Multiple blockers and multiple attackers are represented without UI-derived history.

Interaction card states remain semantically separate:

- `weaklySelectableCardIds` and `selectableCardIds` are ordinary Forge-offered actionable cards.
- `combatSelectableCardIds` are attacking cards Forge permits as combat-navigation choices during
  blocker declaration. Clients may make them clickable without rendering the ordinary actionable
  treatment.
- `highlightedCardIds` are Forge's current highlighted/selected cards. During blocker declaration,
  this includes the attacker whose blockers are currently being edited.

## Client To Forge

- `selectCard`: Select a currently offered card by ID.
- `selectPlayer`: Select a currently offered player by ID.
- `button`: Select the current `ok` or `cancel` button.
- `passPriority`: Pass priority through the current controller.
- `reply`: Answer one synchronous query by `requestId`.

## Identifiers And Ordering

`stateSequence` belongs only to `state`. It is the Forge full-state/delta sequence associated with
the authoritative projected state. A value of `-1` means Forge did not provide a sequence for that
full-state callback. Clients do not send it back.

`interactionSequence` belongs to asynchronous interactions. It strictly increases for every
emitted `interaction` during one bridge process. `selectCard`, `selectPlayer`, `button`, and
`passPriority` must echo the sequence from the interaction on which the action is based. The bridge
compares it with the current sequence immediately before calling `IGameController`. A mismatch
produces `STALE_INTERACTION`; Forge is not called.

`requestId` belongs to one synchronous `query`. A `reply` must echo it. Choice queries select only
an ID offered by that query. Combat-damage queries instead return one integer amount for every
offered recipient. Replayed Forge callbacks receive new request IDs and must be answered
independently. `requestId` is authoritative for synchronous replies; `interactionSequence` does not
replace it.

### Combat Damage Assignment

`combatDamageAssignment` is an amount query rather than a menu of prebuilt choices. Forge supplies
the total damage, recipients in damage-assignment order, and the constraints the client should
present. Each recipient has a query-local `key`, a generic `entityType` and `entityId`, its combat
`role`, and Forge's current `minimumDamage` for progressing past that blocker. A defender can be a
`player` or a `card`; it is not represented as a fake card ID.

The bridge validates the complete reply against the Forge-owned assignment model before returning
it to `PlayerControllerHuman`. Invalid replies produce `invalidCombatDamageAssignment` and reissue
the still-pending query with the same `requestId` so the client can correct the amounts.

## Examples

Asynchronous interaction and action:

```json
{"schemaVersion":1,"type":"interaction","interactionSequence":42,"reason":"selectables","prompt":"Choose a card","weaklySelectableCardIds":[],"selectableCardIds":[123],"combatSelectableCardIds":[],"highlightedCardIds":[],"selectablePlayerIds":[],"min":1,"max":1,"buttons":{"okLabel":"OK","cancelLabel":"Cancel","okEnabled":false,"cancelEnabled":true,"focusOk":false}}
{"schemaVersion":1,"type":"selectCard","interactionSequence":42,"cardId":123}
```

Synchronous query and reply:

```json
{"schemaVersion":1,"type":"query","requestId":"q-7","kind":"abilityChoice","hostCardId":123,"hostCardName":"Lightning Bolt","choices":[{"id":456,"description":"Lightning Bolt deals 3 damage to any target.","canPlay":true}]}
{"schemaVersion":1,"type":"reply","requestId":"q-7","selectedId":456}
```

Trample assignment and reply:

```json
{"schemaVersion":1,"type":"query","requestId":"q-8","kind":"combatDamageAssignment","hostCardId":101,"hostCardName":"Ball Lightning","totalDamage":6,"recipients":[{"key":"blocker:0","entityType":"card","entityId":202,"name":"Goblin Patrol","role":"blocker","order":0,"minimumDamage":1},{"key":"defender","entityType":"player","entityId":303,"name":"Opponent","role":"defender","order":1,"minimumDamage":0}],"constraints":{"orderedAssignment":true,"defenderRequiresLethalBlockers":true,"freeAssignment":false,"maySkip":false}}
{"schemaVersion":1,"type":"reply","requestId":"q-8","assignments":[{"recipientKey":"blocker:0","amount":1},{"recipientKey":"defender","amount":5}],"skip":false}
```

Rejected stale action:

```json
{"schemaVersion":1,"type":"selectCard","interactionSequence":41,"cardId":123}
{"schemaVersion":1,"type":"error","code":"STALE_INTERACTION","message":"Async action does not match the current interaction context","receivedInteractionSequence":41,"currentInteractionSequence":42}
```
