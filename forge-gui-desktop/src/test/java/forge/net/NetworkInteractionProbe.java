package forge.net;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only diagnostic sink for the first external-client bridge spike.
 * Captures what the current headless network client can observe without
 * changing Forge rules or creating a new transport.
 */
public class NetworkInteractionProbe {
    private enum SpikeCStage {
        DISABLED,
        PREPARE_MANA,
        WAIT_FOR_MANA_STATE,
        WAIT_FOR_SPELL,
        CHOOSE_ABILITY,
        CHOOSE_TARGET,
        PAY_MANA,
        WAIT_FOR_STACK,
        WAIT_FOR_RESOLUTION,
        COMPLETE
    }

    private final List<String> entries = new CopyOnWriteArrayList<>();
    private final AtomicInteger gameStateUpdates = new AtomicInteger();
    private final AtomicInteger interactions = new AtomicInteger();
    private final AtomicInteger postInteractionGameStateUpdates = new AtomicInteger();
    private final AtomicBoolean sawGameState = new AtomicBoolean();
    private final AtomicBoolean sawInteraction = new AtomicBoolean();
    private volatile String scriptedCardName;
    private volatile int scriptedCardId = -1;
    private volatile int actionGameStateCount = -1;
    private volatile boolean scriptedCardSelected = false;
    private volatile boolean scriptedTransitionObserved = false;
    private volatile String scriptedSpellName;
    private volatile String scriptedTargetName;
    private volatile String scriptedManaSourceName;
    private volatile SpikeCStage spikeCStage = SpikeCStage.DISABLED;
    private volatile int spikeCSpellId = -1;
    private volatile int spikeCTargetId = -1;
    private volatile int spikeCManaSourceId = -1;
    private volatile int spikeCCastGameStateCount = -1;
    private volatile boolean spikeCSpellSelected;
    private volatile boolean spikeCTargetSelected;
    private volatile boolean spikeCManaSourceSelected;
    private volatile boolean spikeCStackObserved;
    private volatile boolean spikeCTargetObservedOnStack;
    private volatile boolean spikeCPriorityPassQueued;
    private volatile boolean spikeCResolutionObserved;

    public void onGameState(String source, GameView gameView, Collection<PlayerView> localPlayers) {
        sawGameState.set(true);
        int count = gameStateUpdates.incrementAndGet();
        if (sawInteraction.get()) {
            postInteractionGameStateUpdates.incrementAndGet();
        }
        updateScriptedTransition(gameView, count);
        updateSpikeCTransition(gameView, count);

        StringBuilder sb = new StringBuilder();
        sb.append("PROBE GAME STATE #").append(count).append(" source=").append(source).append('\n');
        if (gameView == null) {
            sb.append("  gameView=null");
            record(sb);
            return;
        }

        sb.append("  turn=").append(gameView.getTurn())
                .append(" phase=").append(gameView.getPhase())
                .append(" priority=").append(priorityPlayer(gameView))
                .append('\n');

        for (PlayerView player : safePlayers(gameView)) {
            sb.append("  player id=").append(player.getId())
                    .append(" name=\"").append(player.getName()).append('"')
                    .append(" life=").append(player.getLife())
                    .append(" hasPriority=").append(player.getHasPriority())
                    .append('\n');
            appendZone(sb, "handVisible", player.getHand(), localPlayers, true);
            appendZone(sb, "battlefield", player.getBattlefield(), localPlayers, false);
            appendZone(sb, "graveyard", player.getGraveyard(), localPlayers, false);
        }

        FCollectionView<StackItemView> stack = gameView.getStack();
        sb.append("  stack=[");
        if (stack != null) {
            boolean first = true;
            for (StackItemView item : stack) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("{id=").append(item.getId())
                        .append(", text=\"").append(safe(item.getText())).append('"');
                CardView sourceCard = item.getSourceCard();
                if (sourceCard != null) {
                    sb.append(", source=").append(cardSummary(sourceCard));
                }
                sb.append('}');
            }
        }
        sb.append("]\n");

        CombatView combat = gameView.getCombat();
        sb.append("  combatAttackers=[");
        if (combat != null) {
            boolean first = true;
            for (CardView attacker : combat.getAttackers()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(cardSummary(attacker)).append("->").append(combat.getDefender(attacker));
            }
        }
        sb.append(']');
        record(sb);
        if (isSpikeCEnabled()) {
            recordSpikeCState(gameView, localPlayers, count);
        }
    }

    public void scriptPlayCardNamed(String cardName) {
        scriptedCardName = cardName;
    }

    public void scriptCastSpellTargeting(String spellName, String targetName, String manaSourceName) {
        scriptedSpellName = spellName;
        scriptedTargetName = targetName;
        scriptedManaSourceName = manaSourceName;
        spikeCStage = SpikeCStage.PREPARE_MANA;
    }

    public void onPrompt(PlayerView player, String message, CardView card) {
        markInteraction();
        StringBuilder sb = new StringBuilder();
        sb.append("PROBE INTERACTION prompt player=").append(playerSummary(player))
                .append(" text=\"").append(safe(message)).append('"');
        if (card != null) {
            sb.append(" card=").append(cardSummary(card));
        }
        record(sb);
        if (isSpikeCEnabled()) {
            record(new StringBuilder().append("SPIKE_C PROMPT player=").append(playerSummary(player))
                    .append(" text=\"").append(safe(message)).append("\" card=").append(cardSummary(card)));
        }
    }

    public void onButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        markInteraction();
        record(new StringBuilder()
                .append("PROBE INTERACTION buttons owner=").append(playerSummary(owner))
                .append(" ok={text=\"").append(safe(label1)).append("\", enabled=").append(enable1).append('}')
                .append(" cancel={text=\"").append(safe(label2)).append("\", enabled=").append(enable2).append('}')
                .append(" focusOk=").append(focus1));
        if (isSpikeCEnabled()) {
            record(new StringBuilder().append("SPIKE_C BUTTONS owner=").append(playerSummary(owner))
                    .append(" primary={text=\"").append(safe(label1)).append("\", enabled=").append(enable1).append('}')
                    .append(" secondary={text=\"").append(safe(label2)).append("\", enabled=").append(enable2).append('}')
                    .append(" stage=").append(spikeCStage));
        }
    }

    public void onWeakSelectables(Iterable<CardView> cards) {
        markInteraction();
        record(new StringBuilder()
                .append("PROBE INTERACTION weakSelectableCards=")
                .append(cardList(cards)));
        if (isSpikeCEnabled()) {
            record(new StringBuilder().append("SPIKE_C WEAK_SELECTABLE stage=").append(spikeCStage)
                    .append(" cards=").append(cardList(cards)));
        }
    }

    public void onSelectables(Iterable<CardView> cards, int min, int max) {
        markInteraction();
        record(new StringBuilder()
                .append("PROBE INTERACTION selectableCards min=").append(min)
                .append(" max=").append(max)
                .append(" cards=").append(cardList(cards)));
        if (isSpikeCEnabled()) {
            record(new StringBuilder().append("SPIKE_C SELECTABLE stage=").append(spikeCStage)
                    .append(" min=").append(min).append(" max=").append(max)
                    .append(" cards=").append(cardList(cards)));
        }
    }

    public void onAbilityChoices(CardView hostCard, List<SpellAbilityView> abilities) {
        markInteraction();
        StringBuilder sb = new StringBuilder();
        sb.append("PROBE INTERACTION abilityChoices host=").append(cardSummary(hostCard)).append(" choices=[");
        if (abilities != null) {
            boolean first = true;
            for (SpellAbilityView ability : abilities) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("{id=").append(ability.getId())
                        .append(", description=\"").append(safe(ability.getDescription())).append("\"}");
            }
        }
        sb.append(']');
        record(sb);
        if (isSpikeCEnabled()) {
            record(new StringBuilder().append("SPIKE_C ABILITY_CHOICES host=").append(cardSummary(hostCard))
                    .append(" choices=").append(abilityList(abilities)));
        }
    }

    public boolean shouldHoldPriorityForScript(GameView gameView, Collection<PlayerView> localPlayers) {
        if (isSpikeCEnabled()) {
            return shouldHoldForSpikeC(gameView, localPlayers);
        }
        if (scriptedCardName == null) {
            return false;
        }
        if (scriptedCardSelected) {
            return !scriptedTransitionObserved;
        }
        return isLocalMainOnePriority(gameView, localPlayers) && findScriptTargetInHand(gameView, localPlayers) != null;
    }

    public CardView chooseScriptedWeakSelectable(GameView gameView, Collection<PlayerView> localPlayers,
                                                Iterable<CardView> cards) {
        if (isSpikeCEnabled()) {
            return chooseSpikeCWeakSelectable(gameView, localPlayers, cards);
        }
        if (scriptedCardName == null || scriptedCardSelected || !isLocalMainOnePriority(gameView, localPlayers)) {
            return null;
        }
        CardView targetInHand = findScriptTargetInHand(gameView, localPlayers);
        if (targetInHand == null || cards == null) {
            return null;
        }
        for (CardView card : cards) {
            if (card != null && card.getId() == targetInHand.getId()
                    && scriptedCardName.equals(card.getName())
                    && card.getZone() == ZoneType.Hand) {
                return card;
            }
        }
        return null;
    }

    public CardView chooseScriptedSelectable(Iterable<CardView> cards) {
        if (!isSpikeCEnabled() || spikeCStage != SpikeCStage.CHOOSE_ABILITY || cards == null) {
            return null;
        }
        for (CardView card : cards) {
            if (card != null && scriptedTargetName.equals(card.getName())
                    && card.getZone() == ZoneType.Battlefield) {
                spikeCStage = SpikeCStage.CHOOSE_TARGET;
                return card;
            }
        }
        return null;
    }

    public SpellAbilityView chooseScriptedAbility(CardView hostCard, List<SpellAbilityView> abilities) {
        if (!isSpikeCEnabled() || hostCard == null || !scriptedSpellName.equals(hostCard.getName())
                || abilities == null || abilities.isEmpty()) {
            return null;
        }
        SpellAbilityView selected = null;
        for (SpellAbilityView ability : abilities) {
            if (ability != null && ability.canPlay()) {
                selected = ability;
                break;
            }
        }
        if (selected == null && abilities.size() == 1) {
            selected = abilities.get(0);
        }
        if (selected != null) {
            record(new StringBuilder().append("SPIKE_C ACTION kind=ABILITY selected={id=")
                    .append(selected.getId()).append(", description=\"")
                    .append(safe(selected.getDescription())).append("\"} host=")
                    .append(cardSummary(hostCard))
                    .append(" path=\"IGuiGame.getAbilityToPlay synchronous ProtocolMethod response\"")
                    .append(abilities.size() == 1 ? " deterministicDefault=onlyLegalOption" : ""));
        }
        return selected;
    }

    public void onSpikeCCardAction(CardView selected, String controllerPath) {
        String kind;
        if (spikeCStage == SpikeCStage.PREPARE_MANA) {
            kind = "PREPARE_MANA_LAND";
            spikeCManaSourceId = selected.getId();
            spikeCStage = SpikeCStage.WAIT_FOR_MANA_STATE;
        } else if (spikeCStage == SpikeCStage.WAIT_FOR_SPELL) {
            kind = "CAST_SPELL";
            spikeCSpellId = selected.getId();
            spikeCSpellSelected = true;
            spikeCCastGameStateCount = gameStateUpdates.get();
            spikeCStage = SpikeCStage.CHOOSE_ABILITY;
        } else if (spikeCStage == SpikeCStage.CHOOSE_TARGET) {
            kind = "SELECT_TARGET";
            spikeCTargetId = selected.getId();
            spikeCTargetSelected = true;
            spikeCStage = SpikeCStage.PAY_MANA;
        } else if (spikeCStage == SpikeCStage.PAY_MANA) {
            kind = "PAY_MANA_SOURCE";
            spikeCManaSourceId = selected.getId();
            spikeCManaSourceSelected = true;
            spikeCStage = SpikeCStage.WAIT_FOR_STACK;
        } else {
            return;
        }
        record(new StringBuilder().append("SPIKE_C ACTION kind=").append(kind)
                .append(" selected=").append(cardSummary(selected))
                .append(" path=\"").append(safe(controllerPath)).append('\"'));
        if ("PAY_MANA_SOURCE".equals(kind)) {
            record(new StringBuilder().append("SPIKE_C MANA/PAYMENT source=").append(cardSummary(selected))
                    .append(" mechanism=\"select mana source through active InputPayMana\""));
        }
    }

    public boolean shouldPassPriorityForSpikeC(GameView gameView, Collection<PlayerView> localPlayers) {
        return isSpikeCEnabled() && spikeCStackObserved && !spikeCPriorityPassQueued
                && hasLocalPriority(gameView, localPlayers);
    }

    public void onSpikeCPriorityPass(String controllerPath) {
        spikeCPriorityPassQueued = true;
        spikeCStage = SpikeCStage.WAIT_FOR_RESOLUTION;
        record(new StringBuilder().append("SPIKE_C ACTION kind=PASS_PRIORITY path=\"")
                .append(safe(controllerPath)).append('\"'));
    }

    public void onManaPayment(String callback, PlayerView player, GameView gameView) {
        if (!isSpikeCEnabled()) {
            return;
        }
        record(new StringBuilder().append("SPIKE_C MANA/PAYMENT callback=").append(callback)
                .append(" player=").append(playerSummary(player))
                .append(" phase=").append(gameView == null ? "null" : gameView.getPhase())
                .append(" stage=").append(spikeCStage));
    }

    public void onBeforeScriptedAction(GameView gameView, Collection<PlayerView> localPlayers, CardView target) {
        StringBuilder sb = new StringBuilder();
        sb.append("SPIKE_B BEFORE ACTION\n");
        appendGameSummary(sb, gameView);
        sb.append("  target=").append(cardSummary(target)).append('\n');
        appendLocalZones(sb, gameView, localPlayers);
        record(sb);
    }

    public void onScriptedAction(CardView selected, String controllerPath) {
        scriptedCardSelected = true;
        scriptedCardId = selected != null ? selected.getId() : -1;
        actionGameStateCount = gameStateUpdates.get();
        record(new StringBuilder()
                .append("SPIKE_B ACTION selected=").append(cardSummary(selected))
                .append(" path=\"").append(safe(controllerPath)).append('"'));
    }

    public void onSelectablePlayers(Iterable<PlayerView> players, String reason) {
        markInteraction();
        StringBuilder sb = new StringBuilder();
        sb.append("PROBE INTERACTION selectablePlayers reason=\"").append(safe(reason)).append("\" players=[");
        boolean first = true;
        if (players != null) {
            for (PlayerView player : players) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(playerSummary(player));
            }
        }
        sb.append(']');
        record(sb);
        if (isSpikeCEnabled()) {
            record(new StringBuilder().append("SPIKE_C PLAYER_CHOICES reason=\"").append(safe(reason))
                    .append("\" players=").append(playerList(players)));
        }
    }

    public boolean sawGameState() {
        return sawGameState.get();
    }

    public boolean sawInteraction() {
        return sawInteraction.get();
    }

    public boolean sawGameStateAfterInteraction() {
        return postInteractionGameStateUpdates.get() > 0;
    }

    public boolean isStopConditionSatisfied() {
        if (isSpikeCEnabled()) {
            return spikeCResolutionObserved;
        }
        return scriptedCardName == null ? sawGameStateAfterInteraction() : scriptedTransitionObserved;
    }

    public boolean wasScriptedCardSelected() {
        return scriptedCardSelected;
    }

    public boolean sawAuthoritativeUpdateAfterScriptedAction() {
        return scriptedCardSelected && gameStateUpdates.get() > actionGameStateCount;
    }

    public boolean sawScriptedHandToBattlefieldTransition() {
        return scriptedTransitionObserved;
    }

    public boolean wasSpikeCSpellSelected() {
        return spikeCSpellSelected;
    }

    public boolean wasSpikeCTargetSelected() {
        return spikeCTargetSelected;
    }

    public boolean wasSpikeCManaSourceSelected() {
        return spikeCManaSourceSelected;
    }

    public boolean sawSpikeCStack() {
        return spikeCStackObserved;
    }

    public boolean sawSpikeCTargetOnStack() {
        return spikeCTargetObservedOnStack;
    }

    public boolean sawSpikeCResolution() {
        return spikeCResolutionObserved;
    }

    public boolean sawSpikeCAuthoritativeUpdateAfterCast() {
        return spikeCSpellSelected && gameStateUpdates.get() > spikeCCastGameStateCount;
    }

    public boolean isSpikeCActive() {
        return isSpikeCEnabled();
    }

    public int getGameStateUpdateCount() {
        return gameStateUpdates.get();
    }

    public int getInteractionCount() {
        return interactions.get();
    }

    public String getLog() {
        return String.join(System.lineSeparator(), entries);
    }

    public List<String> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    private void markInteraction() {
        sawInteraction.set(true);
        interactions.incrementAndGet();
    }

    private void record(StringBuilder sb) {
        String line = sb.toString();
        entries.add(line);
        System.out.println(line);
    }

    private void updateScriptedTransition(GameView gameView, int count) {
        if (!scriptedCardSelected || scriptedTransitionObserved || gameView == null || count <= actionGameStateCount) {
            return;
        }
        boolean inHand = false;
        boolean onBattlefield = false;
        CardView battlefieldCard = null;
        for (PlayerView player : safePlayers(gameView)) {
            for (CardView card : safeCards(player.getHand())) {
                if (card.getId() == scriptedCardId) {
                    inHand = true;
                }
            }
            for (CardView card : safeCards(player.getBattlefield())) {
                if (card.getId() == scriptedCardId
                        || (scriptedCardName != null && scriptedCardName.equals(card.getName()))) {
                    onBattlefield = true;
                    battlefieldCard = card;
                }
            }
        }
        if (!inHand && onBattlefield) {
            scriptedTransitionObserved = true;
            StringBuilder sb = new StringBuilder();
            sb.append("SPIKE_B AFTER ACTION\n");
            appendGameSummary(sb, gameView);
            sb.append("  selectedCardId=").append(scriptedCardId)
                    .append(" noLongerInHand=").append(true)
                    .append(" battlefield=").append(cardSummary(battlefieldCard))
                    .append('\n');
            for (PlayerView player : safePlayers(gameView)) {
                sb.append("  player=").append(playerSummary(player)).append('\n');
                appendZone(sb, "handVisible", player.getHand(), Collections.emptyList(), false);
                appendZone(sb, "battlefield", player.getBattlefield(), Collections.emptyList(), false);
            }
            record(sb);
        }
    }

    private void updateSpikeCTransition(GameView gameView, int count) {
        if (!isSpikeCEnabled() || gameView == null || spikeCResolutionObserved) {
            return;
        }

        CardView spellInHand = findCard(gameView, spikeCSpellId, scriptedSpellName, ZoneType.Hand);
        CardView manaOnBattlefield = findCard(gameView, spikeCManaSourceId, scriptedManaSourceName, ZoneType.Battlefield);
        CardView targetOnBattlefield = findCard(gameView, spikeCTargetId, scriptedTargetName, ZoneType.Battlefield);
        CardView targetInGraveyard = findCard(gameView, spikeCTargetId, scriptedTargetName, ZoneType.Graveyard);

        if (spikeCStage == SpikeCStage.WAIT_FOR_MANA_STATE && manaOnBattlefield != null) {
            spikeCStage = SpikeCStage.WAIT_FOR_SPELL;
            record(new StringBuilder().append("SPIKE_C STATE setupManaReady=")
                    .append(cardSummary(manaOnBattlefield)));
        }

        StackItemView boltStackItem = null;
        FCollectionView<StackItemView> stack = gameView.getStack();
        if (stack != null) {
            for (StackItemView item : stack) {
                CardView source = item.getSourceCard();
                if (source != null && (source.getId() == spikeCSpellId
                        || scriptedSpellName.equals(source.getName()))) {
                    boltStackItem = item;
                    break;
                }
            }
        }

        if (boltStackItem != null && !spikeCStackObserved) {
            spikeCStackObserved = true;
            spikeCTargetObservedOnStack = containsCard(
                    boltStackItem.getTargetCards(), spikeCTargetId, scriptedTargetName);
            record(new StringBuilder().append("SPIKE_C STACK item={id=").append(boltStackItem.getId())
                    .append(", text=\"").append(safe(boltStackItem.getText())).append("\", source=")
                    .append(cardSummary(boltStackItem.getSourceCard()))
                    .append(", targets=").append(cardList(boltStackItem.getTargetCards())).append('}')
                    .append(" selectedTargetMatched=").append(spikeCTargetObservedOnStack));
        }

        if (spikeCSpellSelected && spikeCTargetSelected && count > spikeCCastGameStateCount
                && spellInHand == null && targetOnBattlefield == null && targetInGraveyard != null) {
            spikeCResolutionObserved = true;
            spikeCStage = SpikeCStage.COMPLETE;
            record(new StringBuilder().append("SPIKE_C RESOLUTION turn=").append(gameView.getTurn())
                    .append(" phase=").append(gameView.getPhase())
                    .append(" priority=").append(priorityPlayer(gameView)).append('\n')
                    .append("  spellId=").append(spikeCSpellId).append(" noLongerInHand=true")
                    .append(" stackPreviouslyObserved=").append(spikeCStackObserved).append('\n')
                    .append("  selectedTargetId=").append(spikeCTargetId)
                    .append(" noLongerOnBattlefield=true graveyard=").append(cardSummary(targetInGraveyard)));
        }
    }

    private CardView chooseSpikeCWeakSelectable(GameView gameView, Collection<PlayerView> localPlayers,
                                                Iterable<CardView> cards) {
        if (cards == null) {
            return null;
        }
        if (spikeCStage == SpikeCStage.PREPARE_MANA && isLocalMainOnePriority(gameView, localPlayers)) {
            return findMatchingCard(cards, scriptedManaSourceName, ZoneType.Hand, false);
        }
        if (spikeCStage == SpikeCStage.WAIT_FOR_SPELL && isSpikeCCastReady(gameView, localPlayers)) {
            return findMatchingCard(cards, scriptedSpellName, ZoneType.Hand, false);
        }
        if (spikeCStage == SpikeCStage.PAY_MANA) {
            return findMatchingCard(cards, scriptedManaSourceName, ZoneType.Battlefield, true);
        }
        return null;
    }

    private boolean shouldHoldForSpikeC(GameView gameView, Collection<PlayerView> localPlayers) {
        switch (spikeCStage) {
        case PREPARE_MANA:
            return isLocalMainOnePriority(gameView, localPlayers)
                    && findLocalCard(gameView, localPlayers, scriptedManaSourceName, ZoneType.Hand) != null;
        case WAIT_FOR_MANA_STATE:
        case CHOOSE_ABILITY:
        case CHOOSE_TARGET:
        case PAY_MANA:
        case WAIT_FOR_STACK:
            return true;
        case WAIT_FOR_SPELL:
            return isSpikeCCastReady(gameView, localPlayers);
        case WAIT_FOR_RESOLUTION:
            return hasLocalPriority(gameView, localPlayers) && hasScriptedSpellOnStack(gameView);
        default:
            return false;
        }
    }

    private boolean isSpikeCCastReady(GameView gameView, Collection<PlayerView> localPlayers) {
        CardView mana = findLocalCard(gameView, localPlayers, scriptedManaSourceName, ZoneType.Battlefield);
        return isLocalMainOnePriority(gameView, localPlayers)
                && findLocalCard(gameView, localPlayers, scriptedSpellName, ZoneType.Hand) != null
                && mana != null && !mana.isTapped()
                && findCard(gameView, -1, scriptedTargetName, ZoneType.Battlefield) != null;
    }

    private boolean hasScriptedSpellOnStack(GameView gameView) {
        if (gameView == null || gameView.getStack() == null) {
            return false;
        }
        for (StackItemView item : gameView.getStack()) {
            CardView source = item.getSourceCard();
            if (source != null && (source.getId() == spikeCSpellId || scriptedSpellName.equals(source.getName()))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLocalPriority(GameView gameView, Collection<PlayerView> localPlayers) {
        if (gameView == null || localPlayers == null) {
            return false;
        }
        for (PlayerView local : localPlayers) {
            for (PlayerView player : safePlayers(gameView)) {
                if (player.getId() == local.getId() && player.getHasPriority()) {
                    return true;
                }
            }
        }
        return false;
    }

    private CardView findLocalCard(GameView gameView, Collection<PlayerView> localPlayers,
                                   String name, ZoneType zone) {
        if (gameView == null || localPlayers == null) {
            return null;
        }
        for (PlayerView local : localPlayers) {
            for (PlayerView player : safePlayers(gameView)) {
                if (player.getId() != local.getId()) {
                    continue;
                }
                CardView card = findInZone(player, -1, name, zone);
                if (card != null) {
                    return card;
                }
            }
        }
        return null;
    }

    private static CardView findCard(GameView gameView, int id, String name, ZoneType zone) {
        if (gameView == null) {
            return null;
        }
        for (PlayerView player : safePlayers(gameView)) {
            CardView card = findInZone(player, id, name, zone);
            if (card != null) {
                return card;
            }
        }
        return null;
    }

    private static CardView findInZone(PlayerView player, int id, String name, ZoneType zone) {
        Iterable<CardView> cards;
        if (zone == ZoneType.Hand) {
            cards = player.getHand();
        } else if (zone == ZoneType.Battlefield) {
            cards = player.getBattlefield();
        } else if (zone == ZoneType.Graveyard) {
            cards = player.getGraveyard();
        } else {
            return null;
        }
        for (CardView card : safeCards(cards)) {
            if ((id < 0 || card.getId() == id) && (name == null || name.equals(card.getName()))) {
                return card;
            }
        }
        return null;
    }

    private static CardView findMatchingCard(Iterable<CardView> cards, String name, ZoneType zone,
                                             boolean requireUntapped) {
        for (CardView card : safeCards(cards)) {
            if (card != null && name.equals(card.getName()) && card.getZone() == zone
                    && (!requireUntapped || !card.isTapped())) {
                return card;
            }
        }
        return null;
    }

    private static boolean containsCard(Iterable<CardView> cards, int id, String name) {
        for (CardView card : safeCards(cards)) {
            if (card != null && (card.getId() == id || name.equals(card.getName()))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSpikeCEnabled() {
        return spikeCStage != SpikeCStage.DISABLED;
    }

    private void recordSpikeCState(GameView gameView, Collection<PlayerView> localPlayers, int count) {
        StringBuilder sb = new StringBuilder().append("SPIKE_C STATE #").append(count)
                .append(" stage=").append(spikeCStage).append(' ');
        appendGameSummary(sb, gameView);
        if (gameView != null) {
            for (PlayerView player : safePlayers(gameView)) {
                sb.append("  player=").append(playerSummary(player))
                        .append(" handVisible=").append(cardList(player.getHand(), localPlayers, true))
                        .append(" battlefield=").append(cardList(player.getBattlefield()))
                        .append(" graveyard=").append(cardList(player.getGraveyard())).append('\n');
            }
        }
        record(sb);
    }

    private CardView findScriptTargetInHand(GameView gameView, Collection<PlayerView> localPlayers) {
        if (gameView == null || scriptedCardName == null || localPlayers == null) {
            return null;
        }
        for (PlayerView localPlayer : localPlayers) {
            for (PlayerView player : safePlayers(gameView)) {
                if (player.getId() != localPlayer.getId()) {
                    continue;
                }
                for (CardView card : safeCards(player.getHand())) {
                    if (scriptedCardName.equals(card.getName()) && card.getZone() == ZoneType.Hand) {
                        return card;
                    }
                }
            }
        }
        return null;
    }

    private boolean isLocalMainOnePriority(GameView gameView, Collection<PlayerView> localPlayers) {
        if (gameView == null || gameView.getPhase() != PhaseType.MAIN1 || localPlayers == null) {
            return false;
        }
        PlayerView turnPlayer = gameView.getPlayerTurn();
        for (PlayerView localPlayer : localPlayers) {
            for (PlayerView player : safePlayers(gameView)) {
                if (player.getId() == localPlayer.getId()
                        && player.getHasPriority()
                        && turnPlayer != null
                        && turnPlayer.getId() == localPlayer.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Iterable<CardView> safeCards(Iterable<CardView> cards) {
        return cards == null ? Collections.emptyList() : cards;
    }

    private static void appendGameSummary(StringBuilder sb, GameView gameView) {
        if (gameView == null) {
            sb.append("  gameView=null\n");
            return;
        }
        sb.append("  turn=").append(gameView.getTurn())
                .append(" phase=").append(gameView.getPhase())
                .append(" priority=").append(priorityPlayer(gameView))
                .append('\n');
    }

    private static void appendLocalZones(StringBuilder sb, GameView gameView, Collection<PlayerView> localPlayers) {
        if (gameView == null || localPlayers == null) {
            return;
        }
        for (PlayerView localPlayer : localPlayers) {
            for (PlayerView player : safePlayers(gameView)) {
                if (player.getId() == localPlayer.getId()) {
                    sb.append("  localPlayer=").append(playerSummary(player)).append('\n');
                    appendZone(sb, "handVisible", player.getHand(), localPlayers, true);
                    appendZone(sb, "battlefield", player.getBattlefield(), localPlayers, false);
                }
            }
        }
    }

    private static Iterable<PlayerView> safePlayers(GameView gameView) {
        FCollectionView<PlayerView> players = gameView.getPlayers();
        return players == null ? Collections.emptyList() : players;
    }

    private static String priorityPlayer(GameView gameView) {
        for (PlayerView player : safePlayers(gameView)) {
            if (player.getHasPriority()) {
                return player.getId() + ":" + player.getName();
            }
        }
        return "none";
    }

    private static void appendZone(StringBuilder sb, String label, Iterable<CardView> cards,
                                   Collection<PlayerView> localPlayers, boolean onlyVisible) {
        sb.append("    ").append(label).append('=').append(cardList(cards, localPlayers, onlyVisible)).append('\n');
    }

    private static String cardList(Iterable<CardView> cards) {
        return cardList(cards, Collections.emptyList(), false);
    }

    private static String cardList(Iterable<CardView> cards, Collection<PlayerView> localPlayers, boolean onlyVisible) {
        StringBuilder sb = new StringBuilder("[");
        if (cards != null) {
            boolean first = true;
            for (CardView card : cards) {
                if (onlyVisible && localPlayers != null && !localPlayers.isEmpty() && !card.canBeShownToAny(localPlayers)) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(cardSummary(card));
            }
        }
        return sb.append(']').toString();
    }

    private static String abilityList(Iterable<SpellAbilityView> abilities) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (abilities != null) {
            for (SpellAbilityView ability : abilities) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("{id=").append(ability.getId())
                        .append(", canPlay=").append(ability.canPlay())
                        .append(", description=\"").append(safe(ability.getDescription())).append("\"}");
            }
        }
        return sb.append(']').toString();
    }

    private static String playerList(Iterable<PlayerView> players) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (players != null) {
            for (PlayerView player : players) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(playerSummary(player));
            }
        }
        return sb.append(']').toString();
    }

    private static String cardSummary(CardView card) {
        if (card == null) {
            return "null";
        }
        ZoneType zone = card.getZone();
        return "{id=" + card.getId() + ", name=\"" + safe(card.getName()) + "\", zone=" + zone + "}";
    }

    private static String playerSummary(PlayerView player) {
        if (player == null) {
            return "null";
        }
        return "{id=" + player.getId() + ", name=\"" + safe(player.getName()) + "\"}";
    }

    private static String safe(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }
}
