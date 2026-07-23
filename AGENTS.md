# AGENTS.md

## Start Here

- Read `PROJECT_STATUS.md` before making changes.
- Inspect the current implementation before assuming how a subsystem works.
- Check the current branch and `git status` before editing; preserve unrelated and untracked work.
- Treat source code and tests as authoritative when `PROJECT_STATUS.md` is stale.
- Read `forge-bridge/PROTOCOL.md` before changing bridge messages or interaction behavior.

## Current Priority

- Drive toward a complete two-human Sligh mirror from mulligan through lethal.
- Keep compatibility deck-scoped; do not broaden work into general Premodern or all Forge cards unless explicitly asked.
- Do not start a custom/Rust rules engine or Forge replacement unless explicitly asked.

## Core Architecture Rules

- Keep Forge authoritative for Magic rules, legality, choices, RNG, and game state.
- Keep Godot as presentation and input only; do not reimplement Magic legality there.
- Never make optimistic authoritative state changes in Godot. Wait for Forge state.
- Prefer generic bridge/protocol primitives over card-specific hacks.
- Never hardcode Sligh card names to repair a generic rules or interaction problem.
- Keep hidden information visibility-safe, especially opponent hands and hidden card types.
- Preserve `schemaVersion`, `stateSequence`, `interactionSequence`, and `requestId` semantics.
- Reject stale or invalid actions instead of guessing or silently retrying them.
- Keep bridge stdout JSONL-only; send diagnostics and logs to stderr.
- Avoid exposing mutable or unnecessary Forge internals across the bridge.

## Layer Responsibilities

- **Forge:** Own authoritative rules, legal choices, state transitions, and game results. Publish choices through existing controller/GUI boundaries.
- **Bridge:** Translate state/interactions into generic, visibility-safe messages. Validate IDs, sequencing, correlation, and structured replies without UI or card-name assumptions.
- **Godot:** Render projections, enable Forge-offered controls, and return correlated input. Reuse shared controls and treat artwork as optional presentation data.

## Change Philosophy

- Reproduce and trace the root cause before modifying code.
- Prefer the smallest clean change that solves the actual problem.
- Avoid unrelated refactors and broad upstream Forge edits.
- Add generic capability only when a current interaction requires it.
- When two solutions are equally good now, prefer cleaner boundaries, determinism, structured logs, or reusable golden scenarios.
- Do not edit generated output under `target/`, `bin/`, `obj/`, or `premodern-client/.godot/`.
- Do not commit local card artwork; `premodern-client/card-images/**/*.webp` is intentionally ignored.

## Gameplay Bug Workflow

1. Reproduce the failure with the smallest realistic fixture.
2. Trace the full path: Forge rules/input → network/controller → bridge/protocol → Godot.
3. Identify which layer first violates the expected behavior; do not assume the visible layer is at fault.
4. Fix the narrowest authoritative boundary.
5. Add a focused regression through the real failing path where practical, then run adjacent tests.

Do not:

- fix a generic interaction with a card-name special case;
- automatically choose for a player when Forge should preserve a legal choice;
- assume every target, selectable, defender, or damage recipient is a card;
- weaken sequencing, validation, or hidden-information rules to make a test pass.

## Critical Regressions to Avoid

- Ask Keep/Mulligan before London-mulligan cards are bottomed.
- Expose the correct selectable hand cards during mulligan bottoming.
- Never leak hidden opponent-hand identities or hidden card type information.
- Keep player targets/selectables first-class across Forge network, bridge, and Godot.
- Keep defending-player recipients in trample/combat-damage assignment; do not model them as fake cards.
- Preserve single unambiguous mandatory-trigger behavior such as the Jackal Pup regression.
- Keep server stop/restart and Ctrl+C/JVM shutdown safe and idempotent.

<!-- ADDED: durable UI-specific rules that reflect current architecture without duplicating PROJECT_STATUS.md -->
## UI Changes

- Preserve `CardControl` as the reusable visual component for hand and battlefield cards.
- Keep gameplay legality and action decisions outside purely visual controls.
- Preserve left-click gameplay actions and right-click preview as separate behaviors.
- Prefer stable board zones whose geometry does not depend on current card count.
- For focused UI requests, avoid unrelated visual redesigns or gameplay changes.

## Protocol Changes

- Update Java serialization, validation, `PROTOCOL.md`, C# message models, parser, serializer, state handling, and UI consumers together.
- Do not silently change the meaning of schema-v1 fields; version deliberate incompatibilities.
- Use `interactionSequence` only for asynchronous actions and `requestId` for synchronous query replies.
- Keep `stateSequence` as Forge state ordering metadata; clients do not echo it.
- Test missing/unsupported versions, stale interactions, invalid choices, and retryable query errors when relevant.

## Testing Expectations

- Run the narrowest relevant test first and match broader verification to the change's risk.
- Add or update regression coverage for gameplay, protocol, network, and shutdown bugs when practical.
- Run relevant Maven tests and a package build for Java changes.
- Run the C# build for Godot changes.
- Verify both Java and C# sides whenever a protocol field or message changes.
- Distinguish pre-existing failures from regressions caused by the change.
- Consult `PROJECT_STATUS.md` for the current known `JsonBridgeIntegrationTest` failure; reproduce it before claiming it is fixed or newly introduced.

From the repository root in Windows PowerShell:

```powershell
mvn --% -pl forge-gui-desktop,forge-bridge -am -DskipTests package
dotnet build "premodern-client/Premodern Client.csproj" -nologo