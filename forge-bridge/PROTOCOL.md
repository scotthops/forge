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

`requestId` belongs to one synchronous `query`. A `reply` must echo it and select only an ID from
that query's offered choices. Replayed Forge callbacks receive new request IDs and must be answered
independently. `requestId` is authoritative for synchronous replies; `interactionSequence` does not
replace it.

## Examples

Asynchronous interaction and action:

```json
{"schemaVersion":1,"type":"interaction","interactionSequence":42,"reason":"selectables","prompt":"Choose a card","weaklySelectableCardIds":[],"selectableCardIds":[123],"selectablePlayerIds":[],"min":1,"max":1,"buttons":{"okLabel":"OK","cancelLabel":"Cancel","okEnabled":false,"cancelEnabled":true,"focusOk":false}}
{"schemaVersion":1,"type":"selectCard","interactionSequence":42,"cardId":123}
```

Synchronous query and reply:

```json
{"schemaVersion":1,"type":"query","requestId":"q-7","kind":"abilityChoice","hostCardId":123,"hostCardName":"Lightning Bolt","choices":[{"id":456,"description":"Lightning Bolt deals 3 damage to any target.","canPlay":true}]}
{"schemaVersion":1,"type":"reply","requestId":"q-7","selectedId":456}
```

Rejected stale action:

```json
{"schemaVersion":1,"type":"selectCard","interactionSequence":41,"cardId":123}
{"schemaVersion":1,"type":"error","code":"STALE_INTERACTION","message":"Async action does not match the current interaction context","receivedInteractionSequence":41,"currentInteractionSequence":42}
```
