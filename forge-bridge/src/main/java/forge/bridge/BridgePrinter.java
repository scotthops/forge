package forge.bridge;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class BridgePrinter {
    private final BridgeProtocol protocol;
    private InteractionSnapshot interaction = InteractionSnapshot.empty();

    BridgePrinter(BridgeProtocol protocol) {
        this.protocol = protocol;
    }

    void lifecycle(String event, String detail) {
        protocol.lifecycle(event, clean(detail));
    }

    void error(String code, String detail) {
        protocol.error(code, clean(detail), null);
    }

    void controller(PlayerView player, Object controller) {
        protocol.send(new ControllerMessage(
                "controller",
                player == null ? null : player.getId(),
                player == null ? null : clean(player.getName()),
                "forge.interfaces.IGameController",
                controller.getClass().getName()));
    }

    void state(String source, long sequenceNumber, GameView gameView, Collection<PlayerView> localPlayers) {
        protocol.send(snapshotState(source, sequenceNumber, gameView, localPlayers));
    }

    synchronized void prompt(String prompt, List<Integer> selectablePlayerIds) {
        interaction = interaction.withPrompt(clean(prompt), List.copyOf(selectablePlayerIds));
        printInteraction("prompt");
    }

    synchronized void buttons(String okLabel, String cancelLabel, boolean okEnabled,
            boolean cancelEnabled, boolean focusOk) {
        interaction = interaction.withButtons(new ButtonSnapshot(
                clean(okLabel), clean(cancelLabel), okEnabled, cancelEnabled, focusOk));
        printInteraction("buttons");
    }

    synchronized void selectables(Iterable<CardView> cards, Collection<PlayerView> localPlayers,
            int min, int max) {
        interaction = interaction.withSelectables(cardIds(cards, localPlayers), min, max);
        printInteraction("selectables");
    }

    synchronized void selectablePlayers(List<Integer> playerIds) {
        interaction = interaction.withSelectablePlayers(List.copyOf(playerIds));
        printInteraction("selectablePlayers");
    }

    synchronized void weaklySelectable(Iterable<CardView> cards, Collection<PlayerView> localPlayers) {
        interaction = interaction.withWeaklySelectable(cardIds(cards, localPlayers));
        printInteraction("weaklySelectable");
    }

    synchronized void combatSelectable(Iterable<CardView> cards,
            Collection<PlayerView> localPlayers) {
        interaction = interaction.withCombatSelectable(cardIds(cards, localPlayers));
        printInteraction("combatSelectable");
    }

    synchronized void highlighted(Iterable<CardView> cards, Collection<PlayerView> localPlayers) {
        interaction = interaction.withHighlighted(cardIds(cards, localPlayers));
        printInteraction("highlighted");
    }

    private void printInteraction(String reason) {
        protocol.send(new InteractionMessage(
                "interaction",
                protocol.nextInteractionSequence(),
                reason,
                interaction.prompt(),
                interaction.weaklySelectableCardIds(),
                interaction.selectableCardIds(),
                interaction.combatSelectableCardIds(),
                interaction.highlightedCardIds(),
                interaction.selectablePlayerIds(),
                interaction.min(),
                interaction.max(),
                interaction.buttons()));
    }

    private static StateMessage snapshotState(String source, long sequenceNumber, GameView gameView,
            Collection<PlayerView> localPlayers) {
        if (gameView == null) {
            return new StateMessage("state", source, sequenceNumber, 0, null,
                    null, null, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }
        List<PlayerSnapshot> players = new ArrayList<>();
        Integer priorityPlayerId = null;
        if (gameView.getPlayers() != null) {
            for (PlayerView player : gameView.getPlayers()) {
                if (player.getHasPriority()) {
                    priorityPlayerId = player.getId();
                }
                players.add(new PlayerSnapshot(
                        player.getId(),
                        clean(player.getName()),
                        player.getLife(),
                        player.getHasPriority(),
                        player.getHand() == null ? 0 : player.getHand().size(),
                        snapshotCards(player.getHand(), localPlayers, true),
                        snapshotCards(player.getBattlefield(), localPlayers, false),
                        snapshotCards(player.getGraveyard(), localPlayers, false)));
            }
        }
        List<StackSnapshot> stack = new ArrayList<>();
        if (gameView.getStack() != null) {
            for (StackItemView item : gameView.getStack()) {
                CardView sourceCard = item.getSourceCard();
                stack.add(new StackSnapshot(
                        item.getId(),
                        clean(item.getText()),
                        sourceCard == null ? null : snapshotCard(sourceCard, localPlayers),
                        snapshotCards(item.getTargetCards(), localPlayers, false)));
            }
        }
        return new StateMessage(
                "state",
                clean(source),
                sequenceNumber,
                gameView.getTurn(),
                gameView.getPhase() == null ? null : gameView.getPhase().name(),
                gameView.getPlayerTurn() == null ? null : gameView.getPlayerTurn().getId(),
                priorityPlayerId,
                List.copyOf(players),
                List.copyOf(stack),
                snapshotCombat(gameView.getCombat()));
    }

    static List<CombatSnapshot> snapshotCombat(CombatView combat) {
        if (combat == null) {
            return Collections.emptyList();
        }
        List<CardView> attackers = new ArrayList<>();
        combat.getAttackers().forEach(attackers::add);
        attackers.sort(Comparator.comparingInt(CardView::getId));

        List<CombatSnapshot> result = new ArrayList<>();
        for (CardView attacker : attackers) {
            List<Integer> blockerIds = new ArrayList<>();
            Collection<CardView> blockers = combat.getPlannedBlockers(attacker);
            if (blockers != null) {
                blockers.forEach(blocker -> blockerIds.add(blocker.getId()));
            }
            result.add(new CombatSnapshot(attacker.getId(), List.copyOf(blockerIds)));
        }
        return List.copyOf(result);
    }

    private static List<CardSnapshot> snapshotCards(Iterable<CardView> cards,
            Collection<PlayerView> localPlayers, boolean visibleOnly) {
        if (cards == null) {
            return Collections.emptyList();
        }
        List<CardSnapshot> result = new ArrayList<>();
        for (CardView card : cards) {
            CardSnapshot snapshot = snapshotCard(card, localPlayers);
            if (!visibleOnly || !snapshot.hidden()) {
                result.add(snapshot);
            }
        }
        return List.copyOf(result);
    }

    private static List<Integer> cardIds(Iterable<CardView> cards, Collection<PlayerView> localPlayers) {
        if (cards == null) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>();
        for (CardView card : cards) {
            if (card != null && card.canBeShownToAny(localPlayers)) {
                result.add(card.getId());
            }
        }
        return List.copyOf(result);
    }

    private static CardSnapshot snapshotCard(CardView card, Collection<PlayerView> localPlayers) {
        boolean visible = localPlayers != null && !localPlayers.isEmpty()
                && card.canBeShownToAny(localPlayers);
        ZoneType zone = card.getZone();
        boolean isLand = visible && card.getCurrentState() != null
                && card.getCurrentState().isLand();
        return new CardSnapshot(card.getId(), visible ? clean(card.getName()) : null,
                zone == null ? null : zone.name(), !visible, visible && card.isTapped(), isLand);
    }

    private static String clean(String value) {
        return value == null ? null : value.replace('\n', ' ').replace('\r', ' ');
    }

    private record ControllerMessage(String type, Integer playerId, String playerName,
                                     String controllerInterface, String implementation) { }

    private record StateMessage(String type, String source, long stateSequence, int turn, String phase,
                                Integer activePlayerId, Integer priorityPlayerId,
                                List<PlayerSnapshot> players, List<StackSnapshot> stack,
                                List<CombatSnapshot> combat) { }

    private record PlayerSnapshot(int id, String name, int life, boolean hasPriority, int handCount,
                                  List<CardSnapshot> handVisible,
                                  List<CardSnapshot> battlefield,
                                  List<CardSnapshot> graveyard) { }

    private record CardSnapshot(int id, String name, String zone, boolean hidden, boolean tapped,
                                boolean isLand) { }

    private record StackSnapshot(int id, String text, CardSnapshot source,
                                 List<CardSnapshot> targets) { }

    record CombatSnapshot(int attackerCardId, List<Integer> blockerCardIds) { }

    private record InteractionMessage(String type, long interactionSequence, String reason, String prompt,
                                      List<Integer> weaklySelectableCardIds,
                                      List<Integer> selectableCardIds,
                                      List<Integer> combatSelectableCardIds,
                                      List<Integer> highlightedCardIds,
                                      List<Integer> selectablePlayerIds,
                                      int min, int max, ButtonSnapshot buttons) { }

    private record ButtonSnapshot(String okLabel, String cancelLabel, boolean okEnabled,
                                  boolean cancelEnabled, boolean focusOk) { }

    private record InteractionSnapshot(String prompt, List<Integer> weaklySelectableCardIds,
                                       List<Integer> selectableCardIds,
                                       List<Integer> combatSelectableCardIds,
                                       List<Integer> highlightedCardIds,
                                       List<Integer> selectablePlayerIds, int min, int max,
                                       ButtonSnapshot buttons) {
        private static InteractionSnapshot empty() {
            return new InteractionSnapshot(null, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, 0,
                    new ButtonSnapshot(null, null, false, false, false));
        }

        private InteractionSnapshot withPrompt(String value, List<Integer> playerIds) {
            return new InteractionSnapshot(value, weaklySelectableCardIds, selectableCardIds,
                    combatSelectableCardIds, highlightedCardIds, playerIds, min, max, buttons);
        }

        private InteractionSnapshot withButtons(ButtonSnapshot value) {
            return new InteractionSnapshot(prompt, weaklySelectableCardIds, selectableCardIds,
                    combatSelectableCardIds, highlightedCardIds, selectablePlayerIds, min, max, value);
        }

        private InteractionSnapshot withSelectables(List<Integer> value, int newMin, int newMax) {
            return new InteractionSnapshot(prompt, weaklySelectableCardIds, value,
                    combatSelectableCardIds, highlightedCardIds, selectablePlayerIds,
                    newMin, newMax, buttons);
        }

        private InteractionSnapshot withSelectablePlayers(List<Integer> value) {
            return new InteractionSnapshot(prompt, weaklySelectableCardIds, selectableCardIds,
                    combatSelectableCardIds, highlightedCardIds, value, min, max, buttons);
        }

        private InteractionSnapshot withWeaklySelectable(List<Integer> value) {
            return new InteractionSnapshot(prompt, value, selectableCardIds,
                    combatSelectableCardIds, highlightedCardIds, selectablePlayerIds,
                    min, max, buttons);
        }

        private InteractionSnapshot withCombatSelectable(List<Integer> value) {
            return new InteractionSnapshot(prompt, weaklySelectableCardIds, selectableCardIds,
                    value, highlightedCardIds, selectablePlayerIds, min, max, buttons);
        }

        private InteractionSnapshot withHighlighted(List<Integer> value) {
            return new InteractionSnapshot(prompt, weaklySelectableCardIds, selectableCardIds,
                    combatSelectableCardIds, value, selectablePlayerIds, min, max, buttons);
        }
    }
}
