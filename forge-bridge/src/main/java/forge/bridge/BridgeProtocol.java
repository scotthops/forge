package forge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.card.CardView;
import forge.game.spellability.SpellAbilityView;

import java.util.Collections;
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
    static final int SCHEMA_VERSION = 1;
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    enum CommandType {
        SELECT_CARD,
        SELECT_PLAYER,
        BUTTON,
        PASS_PRIORITY
    }

    record Command(CommandType type, Integer selectedId, String button, long interactionSequence) { }

    private final JsonLineTransport transport;
    private final BridgeGuiBase guiBase;
    private final Gson gson = new Gson();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicLong interactionSequences = new AtomicLong();
    private final Map<String, PendingChoiceQuery<?>> pendingChoiceQueries = new ConcurrentHashMap<>();
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
        JsonObject envelope = gson.toJsonTree(message).getAsJsonObject();
        envelope.addProperty("schemaVersion", SCHEMA_VERSION);
        transport.send(envelope);
    }

    void lifecycle(String event, String detail) {
        send(new LifecycleMessage("lifecycle", event, detail));
    }

    void actionAccepted(String action, Integer selectedId, long interactionSequence) {
        send(new ActionMessage("actionAccepted", action, selectedId, interactionSequence));
    }

    void error(String code, String message, String requestId) {
        send(new ErrorMessage("error", code, message, requestId, null, null, null, null));
    }

    void staleInteraction(long receivedSequence) {
        send(new ErrorMessage("error", "STALE_INTERACTION",
                "Async action does not match the current interaction context", null,
                receivedSequence, currentInteractionSequence(), null, null));
    }

    long nextInteractionSequence() {
        return interactionSequences.incrementAndGet();
    }

    long currentInteractionSequence() {
        return interactionSequences.get();
    }

    SpellAbilityView queryAbility(CardView hostCard, List<SpellAbilityView> offered) {
        Map<Integer, SpellAbilityView> choices = new LinkedHashMap<>();
        List<AbilityChoice> outputChoices = offered.stream()
                .map(ability -> {
                    choices.put(ability.getId(), ability);
                    return new AbilityChoice(ability.getId(), clean(ability.getDescription()), ability.canPlay());
                })
                .toList();
        return queryChoice("abilityChoice", hostCard, choices, outputChoices,
                "synchronous ability choice");
    }

    Map<CardView, Integer> queryCombatDamage(CardView attacker, List<CardView> blockers,
            int damage) {
        if (damage <= 0) {
            return Collections.emptyMap();
        }
        if (blockers == null || blockers.isEmpty()) {
            Map<CardView, Integer> assignment = new LinkedHashMap<>();
            assignment.put(null, damage);
            return assignment;
        }

        CardView firstBlocker = blockers.get(0);
        Map<CardView, Integer> assignment = Map.of(firstBlocker, damage);
        String description = "Assign all " + damage + " combat damage to "
                + clean(firstBlocker.getName());
        return queryChoice("combatDamageAssignment", attacker,
                Map.of(0, assignment),
                List.of(new AbilityChoice(0, description, true)),
                "combat damage assignment");
    }

    private <T> T queryChoice(String kind, CardView hostCard, Map<Integer, T> choices,
            List<AbilityChoice> outputChoices, String description) {
        String requestId = "q-" + requestIds.incrementAndGet();
        PendingChoiceQuery<T> pending = new PendingChoiceQuery<>(choices, new CompletableFuture<>());
        pendingChoiceQueries.put(requestId, pending);
        send(new AbilityQueryMessage("query", requestId, kind,
                hostCard == null ? null : hostCard.getId(),
                hostCard == null ? null : clean(hostCard.getName()), outputChoices));

        try {
            return pending.reply().get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            error("queryTimeout", "No reply received for " + description, requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error("queryInterrupted", description + " was interrupted", requestId);
        } catch (ExecutionException e) {
            error("queryCancelled", clean(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()),
                    requestId);
        } finally {
            pendingChoiceQueries.remove(requestId, pending);
        }
        return null;
    }

    void unsupportedQuery(String kind, Object offered) {
        String requestId = "q-" + requestIds.incrementAndGet();
        send(new UnsupportedQueryMessage("query", requestId, kind, clean(String.valueOf(offered)), false));
        error("unsupportedQuery", "Spike E does not support this required synchronous callback", requestId);
    }

    private void handleInput(JsonObject input) {
        Long receivedSchemaVersion = longField(input, "schemaVersion");
        if (receivedSchemaVersion == null) {
            send(new ErrorMessage("error", "MISSING_SCHEMA_VERSION",
                    "Every input message must include an integer schemaVersion", null,
                    null, null, null, SCHEMA_VERSION));
            return;
        }
        if (receivedSchemaVersion != SCHEMA_VERSION) {
            send(new ErrorMessage("error", "UNSUPPORTED_SCHEMA_VERSION",
                    "Input schemaVersion is not supported", null,
                    null, null, receivedSchemaVersion, SCHEMA_VERSION));
            return;
        }
        String type = stringField(input, "type");
        if (type == null) {
            error("invalidMessage", "Missing string field 'type'", null);
            return;
        }
        if ("reply".equals(type)) {
            handleReply(input);
            return;
        }

        Long interactionSequence = longField(input, "interactionSequence");
        if (interactionSequence == null) {
            error("MISSING_INTERACTION_SEQUENCE",
                    "Async action must include an integer interactionSequence", null);
            return;
        }
        Command command;
        switch (type) {
        case "selectCard":
            command = new Command(CommandType.SELECT_CARD, integerField(input, "cardId"), null,
                    interactionSequence);
            break;
        case "selectPlayer":
            command = new Command(CommandType.SELECT_PLAYER, integerField(input, "playerId"), null,
                    interactionSequence);
            break;
        case "button":
            command = new Command(CommandType.BUTTON, null, stringField(input, "button"),
                    interactionSequence);
            break;
        case "passPriority":
            command = new Command(CommandType.PASS_PRIORITY, null, null, interactionSequence);
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
        PendingChoiceQuery<?> pending = pendingChoiceQueries.get(requestId);
        if (pending == null) {
            error("staleRequestId", "No pending query has this requestId", requestId);
            return;
        }
        Integer selectedId = integerField(input, "selectedId");
        Object selected = selectedId == null ? null : pending.choices().get(selectedId);
        if (selected == null) {
            error("invalidQueryChoice", "selectedId is not one of the offered choices", requestId);
            return;
        }
        completePending(pending, selected);
    }

    @SuppressWarnings("unchecked")
    private static <T> void completePending(PendingChoiceQuery<T> pending, Object selected) {
        pending.reply().complete((T) selected);
    }

    private static String stringField(JsonObject input, String name) {
        JsonElement value = input.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer integerField(JsonObject input, String name) {
        Long value = longField(input, name);
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private static Long longField(JsonObject input, String name) {
        JsonElement value = input.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        String raw = value.getAsString();
        if (!raw.matches("-?[0-9]+")) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? null : value.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void close() {
        pendingChoiceQueries.forEach((requestId, pending) ->
                pending.reply().completeExceptionally(new IllegalStateException("Bridge protocol closed")));
        pendingChoiceQueries.clear();
    }

    private record PendingChoiceQuery<T>(Map<Integer, T> choices, CompletableFuture<T> reply) { }

    private record LifecycleMessage(String type, String event, String detail) { }

    private record ActionMessage(String type, String action, Integer selectedId,
                                 long interactionSequence) { }

    private record ErrorMessage(String type, String code, String message, String requestId,
                                Long receivedInteractionSequence, Long currentInteractionSequence,
                                Long receivedSchemaVersion, Integer supportedSchemaVersion) { }

    private record AbilityChoice(int id, String description, boolean canPlay) { }

    private record AbilityQueryMessage(String type, String requestId, String kind,
                                       Integer hostCardId, String hostCardName,
                                       List<AbilityChoice> choices) { }

    private record UnsupportedQueryMessage(String type, String requestId, String kind,
                                           String offered, boolean replySupported) { }
}
