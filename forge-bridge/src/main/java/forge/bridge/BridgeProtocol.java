package forge.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.card.CardView;
import forge.game.spellability.SpellAbilityView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class BridgeProtocol implements AutoCloseable {
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    enum CommandType {
        SELECT_CARD,
        SELECT_PLAYER,
        BUTTON,
        PASS_PRIORITY
    }

    record Command(CommandType type, Integer selectedId, String button) { }

    private final JsonLineTransport transport;
    private final BridgeGuiBase guiBase;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<String, PendingAbilityQuery> pendingAbilityQueries = new ConcurrentHashMap<>();
    private Consumer<Command> actionHandler;

    BridgeProtocol(JsonLineTransport transport, BridgeGuiBase guiBase) {
        this.transport = transport;
        this.guiBase = guiBase;
    }

    void start(Consumer<Command> handler, Runnable eofHandler) {
        actionHandler = handler;
        transport.startInput(this::handleInput,
                message -> error("invalidJson", message, null), eofHandler);
    }

    void send(Object message) {
        transport.send(message);
    }

    void lifecycle(String event, String detail) {
        send(new LifecycleMessage("lifecycle", event, detail));
    }

    void actionAccepted(String action, Integer selectedId) {
        send(new ActionMessage("actionAccepted", action, selectedId));
    }

    void error(String code, String message, String requestId) {
        send(new ErrorMessage("error", code, message, requestId));
    }

    SpellAbilityView queryAbility(CardView hostCard, List<SpellAbilityView> offered) {
        String requestId = "q-" + requestIds.incrementAndGet();
        Map<Integer, SpellAbilityView> choices = new LinkedHashMap<>();
        List<AbilityChoice> outputChoices = offered.stream()
                .map(ability -> {
                    choices.put(ability.getId(), ability);
                    return new AbilityChoice(ability.getId(), clean(ability.getDescription()), ability.canPlay());
                })
                .toList();
        PendingAbilityQuery pending = new PendingAbilityQuery(choices, new CompletableFuture<>());
        pendingAbilityQueries.put(requestId, pending);
        send(new AbilityQueryMessage("query", requestId, "abilityChoice",
                hostCard == null ? null : hostCard.getId(),
                hostCard == null ? null : clean(hostCard.getName()), outputChoices));

        try {
            return pending.reply().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            error("queryTimeout", "No reply received for synchronous ability choice", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error("queryInterrupted", "Synchronous ability choice was interrupted", requestId);
        } catch (ExecutionException e) {
            error("queryCancelled", clean(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()),
                    requestId);
        } finally {
            pendingAbilityQueries.remove(requestId, pending);
        }
        return null;
    }

    void unsupportedQuery(String kind, Object offered) {
        String requestId = "q-" + requestIds.incrementAndGet();
        send(new UnsupportedQueryMessage("query", requestId, kind, clean(String.valueOf(offered)), false));
        error("unsupportedQuery", "Spike E does not support this required synchronous callback", requestId);
    }

    private void handleInput(JsonObject input) {
        String type = stringField(input, "type");
        if (type == null) {
            error("invalidMessage", "Missing string field 'type'", null);
            return;
        }
        if ("reply".equals(type)) {
            handleReply(input);
            return;
        }

        Command command;
        switch (type) {
        case "selectCard":
            command = new Command(CommandType.SELECT_CARD, integerField(input, "cardId"), null);
            break;
        case "selectPlayer":
            command = new Command(CommandType.SELECT_PLAYER, integerField(input, "playerId"), null);
            break;
        case "button":
            command = new Command(CommandType.BUTTON, null, stringField(input, "button"));
            break;
        case "passPriority":
            command = new Command(CommandType.PASS_PRIORITY, null, null);
            break;
        default:
            error("unknownMessageType", "Unsupported input type: " + type, null);
            return;
        }
        if ((command.type() == CommandType.SELECT_CARD || command.type() == CommandType.SELECT_PLAYER)
                && command.selectedId() == null) {
            error("invalidMessage", "Selection command is missing its integer ID", null);
            return;
        }
        if (command.type() == CommandType.BUTTON
                && !("ok".equals(command.button()) || "cancel".equals(command.button()))) {
            error("invalidMessage", "Button must be 'ok' or 'cancel'", null);
            return;
        }
        guiBase.invokeInEdtLater(() -> actionHandler.accept(command));
    }

    private void handleReply(JsonObject input) {
        String requestId = stringField(input, "requestId");
        if (requestId == null) {
            error("invalidMessage", "Reply is missing string field 'requestId'", null);
            return;
        }
        PendingAbilityQuery pending = pendingAbilityQueries.get(requestId);
        if (pending == null) {
            error("staleRequestId", "No pending query has this requestId", requestId);
            return;
        }
        Integer selectedId = integerField(input, "selectedId");
        SpellAbilityView selected = selectedId == null ? null : pending.choices().get(selectedId);
        if (selected == null) {
            error("invalidQueryChoice", "selectedId is not one of the offered choices", requestId);
            return;
        }
        pending.reply().complete(selected);
    }

    private static String stringField(JsonObject input, String name) {
        JsonElement value = input.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer integerField(JsonObject input, String name) {
        JsonElement value = input.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? null : value.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void close() {
        pendingAbilityQueries.forEach((requestId, pending) ->
                pending.reply().completeExceptionally(new IllegalStateException("Bridge protocol closed")));
        pendingAbilityQueries.clear();
    }

    private record PendingAbilityQuery(Map<Integer, SpellAbilityView> choices,
                                       CompletableFuture<SpellAbilityView> reply) { }

    private record LifecycleMessage(String type, String event, String detail) { }

    private record ActionMessage(String type, String action, Integer selectedId) { }

    private record ErrorMessage(String type, String code, String message, String requestId) { }

    private record AbilityChoice(int id, String description, boolean canPlay) { }

    private record AbilityQueryMessage(String type, String requestId, String kind,
                                       Integer hostCardId, String hostCardName,
                                       List<AbilityChoice> choices) { }

    private record UnsupportedQueryMessage(String type, String requestId, String kind,
                                           String offered, boolean replySupported) { }
}
