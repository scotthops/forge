package forge.bridge;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BridgeGuiGame extends NetworkGuiGame {
    interface Listener {
        void controllerCreated();
        void stateObserved();
        void interactionObserved();
        void unsupportedRequiredQuery();
    }

    private final BridgePrinter printer;
    private final BridgeProtocol protocol;
    private final Listener listener;
    private final Map<Integer, CardView> selectableCards = new LinkedHashMap<>();
    private final Map<Integer, CardView> weaklySelectableCards = new LinkedHashMap<>();
    private final Map<Integer, PlayerView> selectablePlayers = new LinkedHashMap<>();
    private IGameController gameController;
    private boolean okEnabled;
    private boolean cancelEnabled;

    BridgeGuiGame(BridgePrinter printer, BridgeProtocol protocol, Listener listener) {
        this.printer = printer;
        this.protocol = protocol;
        this.listener = listener;
    }

    @Override
    public void setOriginalGameController(PlayerView player, IGameController gameController) {
        super.setOriginalGameController(player, gameController);
        this.gameController = gameController;
        printer.controller(player, gameController);
        listener.controllerCreated();
    }

    @Override
    public void setGameView(GameView gameView) {
        super.setGameView(gameView);
        observeState("full", -1);
    }

    @Override
    public void setGameView(GameView gameView, long sequenceNumber) {
        super.setGameView(gameView);
        observeState("full", sequenceNumber);
    }

    @Override
    public void applyDelta(DeltaPacket packet) {
        super.applyDelta(packet);
        observeState("delta", packet == null ? -1 : packet.getSequenceNumber());
    }

    private void observeState(String source, long sequenceNumber) {
        if (getGameView() != null) {
            printer.state(source, sequenceNumber, getGameView(), getLocalPlayers());
            listener.stateObserved();
        }
    }

    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { }
    @Override protected void updateCurrentPlayer(PlayerView player) { }
    @Override public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) { return false; }

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        selectablePlayers.clear();
        if (message != null && message.contains("Click on the portrait") && getGameView() != null
                && getGameView().getPlayers() != null) {
            for (PlayerView player : getGameView().getPlayers()) {
                selectablePlayers.put(player.getId(), player);
            }
        }
        printer.prompt(message, List.copyOf(selectablePlayers.keySet()));
        listener.interactionObserved();
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2,
            boolean enable1, boolean enable2, boolean focus1) {
        okEnabled = enable1;
        cancelEnabled = enable2;
        printer.buttons(label1, label2, enable1, enable2, focus1);
        listener.interactionObserved();
    }

    @Override
    public void updateButtons(PlayerView owner, boolean enable1, boolean enable2, boolean focus1) {
        updateButtons(owner, "OK", "Cancel", enable1, enable2, focus1);
    }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) {
        super.setSelectables(cards, min, max);
        List<CardView> offered = copyCards(cards);
        selectableCards.clear();
        offered.forEach(card -> selectableCards.put(card.getId(), card));
        printer.selectables(offered, getLocalPlayers(), min, max);
        listener.interactionObserved();
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        selectableCards.clear();
        printer.selectables(Collections.emptyList(), getLocalPlayers(), 0, 0);
        listener.interactionObserved();
    }

    @Override
    public void setWeaklySelectable(Iterable<CardView> cards) {
        super.setWeaklySelectable(cards);
        List<CardView> offered = copyCards(cards);
        weaklySelectableCards.clear();
        offered.forEach(card -> weaklySelectableCards.put(card.getId(), card));
        printer.weaklySelectable(offered, getLocalPlayers());
        listener.interactionObserved();
    }

    @Override
    public void clearWeaklySelectable() {
        super.clearWeaklySelectable();
        weaklySelectableCards.clear();
        printer.weaklySelectable(Collections.emptyList(), getLocalPlayers());
        listener.interactionObserved();
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities,
            ITriggerEvent triggerEvent) {
        listener.interactionObserved();
        List<SpellAbilityView> offered = abilities == null ? Collections.emptyList() : List.copyOf(abilities);
        SpellAbilityView selected = protocol.queryAbility(hostCard, offered);
        if (selected == null) {
            listener.unsupportedRequiredQuery();
        }
        return selected;
    }

    private UnsupportedOperationException unsupported(String callback, Object offered) {
        protocol.unsupportedQuery(callback, offered);
        listener.interactionObserved();
        listener.unsupportedRequiredQuery();
        return new UnsupportedOperationException("forge-bridge Spike E cannot answer " + callback);
    }

    void handleCommand(BridgeProtocol.Command command) {
        if (command.interactionSequence() != protocol.currentInteractionSequence()
                || protocol.currentInteractionSequence() == 0) {
            protocol.staleInteraction(command.interactionSequence());
            return;
        }
        if (gameController == null) {
            protocol.error("controllerUnavailable", "Forge has not provided a game controller yet", null);
            return;
        }
        switch (command.type()) {
        case SELECT_CARD:
            CardView card = selectableCards.get(command.selectedId());
            if (card == null) {
                card = weaklySelectableCards.get(command.selectedId());
            }
            if (card == null) {
                protocol.error("cardNotOffered", "cardId is not currently selectable", null);
                return;
            }
            gameController.selectCard(card, null, null);
            protocol.actionAccepted("selectCard", card.getId(), command.interactionSequence());
            break;
        case SELECT_PLAYER:
            PlayerView player = selectablePlayers.get(command.selectedId());
            if (player == null) {
                protocol.error("playerNotOffered", "playerId is not currently selectable", null);
                return;
            }
            gameController.selectPlayer(player, null);
            protocol.actionAccepted("selectPlayer", player.getId(), command.interactionSequence());
            break;
        case BUTTON:
            if ("ok".equals(command.button())) {
                if (!okEnabled) {
                    protocol.error("buttonDisabled", "The OK button is not enabled", null);
                    return;
                }
                gameController.selectButtonOk();
            } else {
                if (!cancelEnabled) {
                    protocol.error("buttonDisabled", "The cancel button is not enabled", null);
                    return;
                }
                gameController.selectButtonCancel();
            }
            protocol.actionAccepted("button:" + command.button(), null, command.interactionSequence());
            break;
        case PASS_PRIORITY:
            gameController.passPriority();
            protocol.actionAccepted("passPriority", null, command.interactionSequence());
            break;
        default:
            protocol.error("unsupportedAction", "Unsupported action command", null);
            break;
        }
    }

    private static List<CardView> copyCards(Iterable<CardView> cards) {
        if (cards == null) {
            return Collections.emptyList();
        }
        List<CardView> copy = new ArrayList<>();
        cards.forEach(copy::add);
        return List.copyOf(copy);
    }

    @Override public PlayerZoneUpdates openZones(PlayerView controller, Collection<ZoneType> zones, Map<PlayerView, Object> players, boolean backupLastZones) { return null; }
    @Override public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) { }
    @Override public void showCombat() { }
    @Override public void finishGame() { }
    @Override public void flashIncorrectAction() { }
    @Override public void alertUser() { }
    @Override public void updatePhase(boolean saveState) { }
    @Override public void updateTurn(PlayerView player) { }
    @Override public void updatePlayerControl() { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void showManaPool(PlayerView player) { }
    @Override public void hideManaPool(PlayerView player) { }
    @Override public void updateStack() { }
    @Override public Iterable<PlayerZoneUpdate> tempShowZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) { return zonesToUpdate; }
    @Override public void hideZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) { }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { }
    @Override public void updateCards(Iterable<CardView> cards) { }
    @Override public GameState getGamestate() { return null; }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { }
    @Override public void setPanelSelection(CardView hostCard) { }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers,
            int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        throw unsupported("assignCombatDamage", blockers);
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target,
            int amount, boolean atLeastOne, String amountLabel) {
        throw unsupported("assignGenericAmount", target);
    }

    @Override public void message(String message, String title) { printer.lifecycle("message", "title=" + title + " text=" + message); }
    @Override public void showErrorDialog(String message, String title) { printer.error("forgeDialog", "title=" + title + " message=" + message); }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText,
            String noButtonText, boolean defaultYes) {
        throw unsupported("showConfirmDialog", List.of(yesButtonText, noButtonText));
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon,
            List<String> options, int defaultOption) {
        throw unsupported("showOptionDialog", options);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon,
            String initialInput, List<String> inputOptions, boolean isNumeric) {
        throw unsupported("showInputDialog", inputOptions);
    }

    @Override
    public boolean confirm(CardView card, String question, boolean defaultIsYes, List<String> options) {
        protocol.unsupportedQuery("confirm", options);
        listener.interactionObserved();
        listener.unsupportedRequiredQuery();
        return false;
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices,
            List<T> selected, FSerializableFunction<T, String> display) {
        protocol.unsupportedQuery("getChoices", choices);
        listener.interactionObserved();
        listener.unsupportedRequiredQuery();
        if (min <= 0) {
            return Collections.emptyList();
        }
        throw new UnsupportedOperationException("forge-bridge Spike E cannot answer required getChoices");
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(String title, String top, int remainingObjectsMin,
            int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices,
            CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        throw unsupported("order", sourceChoices);
    }

    @Override public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) { throw unsupported("sideboard", sideboard); }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title,
            List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        protocol.unsupportedQuery("chooseSingleEntityForEffect", optionList);
        listener.interactionObserved();
        listener.unsupportedRequiredQuery();
        if (isOptional) {
            return null;
        }
        throw new UnsupportedOperationException("forge-bridge Spike E cannot answer required entity choice");
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title,
            List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        if (min <= 0) {
            protocol.unsupportedQuery("chooseEntitiesForEffect", optionList);
            listener.interactionObserved();
            listener.unsupportedRequiredQuery();
            return Collections.emptyList();
        }
        throw unsupported("chooseEntitiesForEffect", optionList);
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards,
            Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> offered = new ArrayList<>();
        if (cards != null) {
            cards.forEach(offered::add);
        }
        throw unsupported("manipulateCardList", offered);
    }

    @Override public void setCard(CardView card) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon icon) { }
}
