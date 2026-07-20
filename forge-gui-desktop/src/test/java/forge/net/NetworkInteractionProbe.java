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

    public void onGameState(String source, GameView gameView, Collection<PlayerView> localPlayers) {
        sawGameState.set(true);
        int count = gameStateUpdates.incrementAndGet();
        if (sawInteraction.get()) {
            postInteractionGameStateUpdates.incrementAndGet();
        }
        updateScriptedTransition(gameView, count);

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
    }

    public void scriptPlayCardNamed(String cardName) {
        scriptedCardName = cardName;
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
    }

    public void onButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        markInteraction();
        record(new StringBuilder()
                .append("PROBE INTERACTION buttons owner=").append(playerSummary(owner))
                .append(" ok={text=\"").append(safe(label1)).append("\", enabled=").append(enable1).append('}')
                .append(" cancel={text=\"").append(safe(label2)).append("\", enabled=").append(enable2).append('}')
                .append(" focusOk=").append(focus1));
    }

    public void onWeakSelectables(Iterable<CardView> cards) {
        markInteraction();
        record(new StringBuilder()
                .append("PROBE INTERACTION weakSelectableCards=")
                .append(cardList(cards)));
    }

    public void onSelectables(Iterable<CardView> cards, int min, int max) {
        markInteraction();
        record(new StringBuilder()
                .append("PROBE INTERACTION selectableCards min=").append(min)
                .append(" max=").append(max)
                .append(" cards=").append(cardList(cards)));
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
    }

    public boolean shouldHoldPriorityForScript(GameView gameView, Collection<PlayerView> localPlayers) {
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
