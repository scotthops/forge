package forge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.combat.CombatDamageAssignment;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gui.interfaces.IGuiGame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class BridgeProtocol implements AutoCloseable {
    static final int SCHEMA_VERSION = 1;
    private static final long DEFAULT_QUERY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

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
    private final long queryTimeoutMillis;
    private final Map<String, PendingChoiceQuery<?>> pendingChoiceQueries = new ConcurrentHashMap<>();
    private final Map<String, PendingCombatDamageQuery> pendingCombatDamageQueries =
            new ConcurrentHashMap<>();
    private final Map<String, PendingItemOrderQuery<?>> pendingItemOrderQueries =
            new ConcurrentHashMap<>();
    private Consumer<Command> actionHandler;

    BridgeProtocol(JsonLineTransport transport, BridgeGuiBase guiBase) {
        this(transport, guiBase, DEFAULT_QUERY_TIMEOUT_MILLIS);
    }

    BridgeProtocol(JsonLineTransport transport, BridgeGuiBase guiBase, long queryTimeoutMillis) {
        this.transport = transport;
        this.guiBase = guiBase;
        this.queryTimeoutMillis = queryTimeoutMillis;
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
            int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        if (damage <= 0) {
            return Collections.emptyMap();
        }

        CombatDamageAssignment assignment = CombatDamageAssignment.create(attacker, blockers,
                damage, defender, overrideOrder, maySkip);
        if (assignment.recipients().isEmpty()) {
            Map<CardView, Integer> fallback = new LinkedHashMap<>();
            fallback.put(null, damage);
            return fallback;
        }
        if (assignment.recipients().size() == 1) {
            CombatDamageAssignment.Recipient only = assignment.recipients().get(0);
            return assignment.toForgeResult(Map.of(only.key(), damage));
        }

        String requestId = "q-" + requestIds.incrementAndGet();
        List<CombatDamageRecipient> recipients = assignment.recipients().stream()
                .map(recipient -> new CombatDamageRecipient(
                        recipient.key(), entityType(recipient.entity()), recipient.entity().getId(),
                        clean(recipient.entity().getName()),
                        recipient.role().name().toLowerCase(), recipient.order(),
                        recipient.minimumDamage()))
                .toList();
        CombatDamageConstraints constraints = new CombatDamageConstraints(
                assignment.orderedAssignment(),
                assignment.defenderRequiresLethalBlockers(),
                assignment.freeAssignment(), assignment.maySkip());
        CombatDamageQueryMessage query = new CombatDamageQueryMessage(
                "query", requestId, "combatDamageAssignment",
                attacker == null ? null : attacker.getId(),
                attacker == null ? null : clean(attacker.getName()), damage,
                recipients, constraints);
        PendingCombatDamageQuery pending = new PendingCombatDamageQuery(assignment, query,
                new CompletableFuture<>());
        pendingCombatDamageQueries.put(requestId, pending);
        send(query);

        try {
            return pending.reply().get(queryTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            error("queryTimeout", "No reply received for combat damage assignment", requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error("queryInterrupted", "Combat damage assignment was interrupted", requestId);
        } catch (ExecutionException e) {
            error("queryCancelled",
                    clean(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()),
                    requestId);
        } finally {
            pendingCombatDamageQueries.remove(requestId, pending);
        }
        return null;
    }

    <T> IGuiGame.OrderResult<T> queryItemOrder(String title, String prompt,
            List<T> originalOrder, boolean mandatory, boolean showRememberCheckbox) {
        List<T> fallback = List.copyOf(originalOrder);
        if (fallback.size() < 2) {
            return new IGuiGame.OrderResult<>(fallback, false);
        }

        String requestId = "q-" + requestIds.incrementAndGet();
        Map<String, T> itemsById = new LinkedHashMap<>();
        List<OrderingItem> items = new ArrayList<>(fallback.size());
        List<String> originalItemIds = new ArrayList<>(fallback.size());
        for (int index = 0; index < fallback.size(); index++) {
            T item = fallback.get(index);
            String itemId = "item-" + (index + 1);
            itemsById.put(itemId, item);
            originalItemIds.add(itemId);

            CardView sourceCard = null;
            if (item instanceof SpellAbilityView ability) {
                sourceCard = ability.getHostCard();
            } else if (item instanceof CardView card) {
                sourceCard = card;
            }
            items.add(new OrderingItem(itemId, clean(String.valueOf(item)),
                    sourceCard == null ? null : sourceCard.getId(),
                    sourceCard == null ? null : clean(sourceCard.getName()), index));
        }

        ItemOrderingQueryMessage query = new ItemOrderingQueryMessage(
                "query", requestId, "itemOrdering", clean(title), clean(prompt),
                mandatory, showRememberCheckbox, items, originalItemIds);
        PendingItemOrderQuery<T> pending = new PendingItemOrderQuery<>(
                itemsById, query, new CompletableFuture<>());
        pendingItemOrderQueries.put(requestId, pending);
        send(query);

        try {
            return pending.reply().get(queryTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            orderingFallback(requestId, "timed out");
            error("queryTimeout", "No reply received for item ordering; using original order",
                    requestId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            orderingFallback(requestId, "was interrupted");
            error("queryInterrupted", "Item ordering was interrupted; using original order",
                    requestId);
        } catch (ExecutionException e) {
            String cause = clean(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            orderingFallback(requestId, "was cancelled: " + cause);
            error("queryCancelled", "Item ordering was cancelled; using original order",
                    requestId);
        } finally {
            pendingItemOrderQueries.remove(requestId, pending);
        }
        return new IGuiGame.OrderResult<>(fallback, false);
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
            return pending.reply().get(queryTimeoutMillis, TimeUnit.MILLISECONDS);
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
        PendingChoiceQuery<?> pendingChoice = pendingChoiceQueries.get(requestId);
        PendingCombatDamageQuery pendingDamage = pendingCombatDamageQueries.get(requestId);
        PendingItemOrderQuery<?> pendingOrder = pendingItemOrderQueries.get(requestId);
        if (pendingChoice == null && pendingDamage == null && pendingOrder == null) {
            error("staleRequestId", "No pending query has this requestId", requestId);
            return;
        }
        if (pendingOrder != null) {
            handleItemOrderReply(input, requestId, pendingOrder);
            return;
        }
        if (pendingDamage != null) {
            handleCombatDamageReply(input, requestId, pendingDamage);
            return;
        }
        Integer selectedId = integerField(input, "selectedId");
        Object selected = selectedId == null ? null : pendingChoice.choices().get(selectedId);
        if (selected == null) {
            error("invalidQueryChoice", "selectedId is not one of the offered choices", requestId);
            return;
        }
        completePending(pendingChoice, selected);
    }

    private void handleItemOrderReply(JsonObject input, String requestId,
            PendingItemOrderQuery<?> pending) {
        JsonElement orderElement = input.get("orderedItemIds");
        if (orderElement == null || !orderElement.isJsonArray()) {
            rejectItemOrderReply(requestId, pending,
                    "Reply must contain an orderedItemIds array");
            return;
        }

        List<String> orderedIds = new ArrayList<>();
        Set<String> distinctIds = new HashSet<>();
        for (JsonElement element : orderElement.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                rejectItemOrderReply(requestId, pending,
                        "Every ordered item ID must be a string");
                return;
            }
            String itemId = element.getAsString();
            if (!pending.itemsById().containsKey(itemId) || !distinctIds.add(itemId)) {
                rejectItemOrderReply(requestId, pending,
                        "Ordered item IDs must contain every offered ID exactly once");
                return;
            }
            orderedIds.add(itemId);
        }
        if (orderedIds.size() != pending.itemsById().size()) {
            rejectItemOrderReply(requestId, pending,
                    "Ordered item IDs must contain every offered ID exactly once");
            return;
        }

        Boolean rememberDecision = booleanField(input, "rememberDecision");
        completePendingOrder(pending, orderedIds,
                pending.query().rememberAllowed() && Boolean.TRUE.equals(rememberDecision));
    }

    private void rejectItemOrderReply(String requestId, PendingItemOrderQuery<?> pending,
            String message) {
        error("invalidItemOrder", message, requestId);
        send(pending.query());
    }

    private void handleCombatDamageReply(JsonObject input, String requestId,
            PendingCombatDamageQuery pending) {
        Boolean skip = booleanField(input, "skip");
        if (Boolean.TRUE.equals(skip)) {
            if (!pending.assignment().maySkip()) {
                rejectCombatDamageReply(requestId, pending,
                        "This damage assignment cannot be skipped");
                return;
            }
            pending.reply().complete(null);
            return;
        }

        JsonElement assignmentsElement = input.get("assignments");
        if (assignmentsElement == null || !assignmentsElement.isJsonArray()) {
            rejectCombatDamageReply(requestId, pending,
                    "Reply must contain an assignments array");
            return;
        }
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (JsonElement element : assignmentsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                rejectCombatDamageReply(requestId, pending,
                        "Each assignment must be an object");
                return;
            }
            JsonObject object = element.getAsJsonObject();
            String recipientKey = stringField(object, "recipientKey");
            Integer amount = integerField(object, "amount");
            if (recipientKey == null || amount == null
                    || amounts.putIfAbsent(recipientKey, amount) != null) {
                rejectCombatDamageReply(requestId, pending,
                        "Each recipient must have one integer damage amount");
                return;
            }
        }

        CombatDamageAssignment.Validation validation = pending.assignment().validate(amounts);
        if (!validation.valid()) {
            rejectCombatDamageReply(requestId, pending, validation.message());
            return;
        }
        pending.reply().complete(pending.assignment().toForgeResult(amounts));
    }

    private void rejectCombatDamageReply(String requestId, PendingCombatDamageQuery pending,
            String message) {
        error("invalidCombatDamageAssignment", message, requestId);
        send(pending.query());
    }

    @SuppressWarnings("unchecked")
    private static <T> void completePending(PendingChoiceQuery<T> pending, Object selected) {
        pending.reply().complete((T) selected);
    }

    private static <T> void completePendingOrder(PendingItemOrderQuery<T> pending,
            List<String> orderedIds, boolean rememberDecision) {
        List<T> ordered = orderedIds.stream().map(pending.itemsById()::get).toList();
        pending.reply().complete(new IGuiGame.OrderResult<>(ordered, rememberDecision));
    }

    private static void orderingFallback(String requestId, String reason) {
        System.err.println("[forge-bridge] Item ordering query " + requestId + " " + reason
                + "; using the original Forge-provided order.");
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

    private static Boolean booleanField(JsonObject input, String name) {
        JsonElement value = input.get(name);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
    }

    private static String entityType(GameEntityView entity) {
        if (entity instanceof PlayerView) {
            return "player";
        }
        if (entity instanceof CardView) {
            return "card";
        }
        return "entity";
    }

    private static String clean(String value) {
        return value == null ? null : value.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void close() {
        pendingChoiceQueries.forEach((requestId, pending) ->
                pending.reply().completeExceptionally(new IllegalStateException("Bridge protocol closed")));
        pendingChoiceQueries.clear();
        pendingCombatDamageQueries.forEach((requestId, pending) ->
                pending.reply().completeExceptionally(
                        new IllegalStateException("Bridge protocol closed")));
        pendingCombatDamageQueries.clear();
        pendingItemOrderQueries.forEach((requestId, pending) ->
                pending.reply().completeExceptionally(
                        new IllegalStateException("Bridge protocol closed")));
        pendingItemOrderQueries.clear();
    }

    private record PendingChoiceQuery<T>(Map<Integer, T> choices, CompletableFuture<T> reply) { }

    private record PendingCombatDamageQuery(CombatDamageAssignment assignment,
                                             CombatDamageQueryMessage query,
                                             CompletableFuture<Map<CardView, Integer>> reply) { }

    private record PendingItemOrderQuery<T>(Map<String, T> itemsById,
                                            ItemOrderingQueryMessage query,
                                            CompletableFuture<IGuiGame.OrderResult<T>> reply) { }

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

    private record CombatDamageRecipient(String key, String entityType, int entityId,
                                         String name, String role, int order,
                                         int minimumDamage) { }

    private record CombatDamageConstraints(boolean orderedAssignment,
                                           boolean defenderRequiresLethalBlockers,
                                           boolean freeAssignment, boolean maySkip) { }

    private record CombatDamageQueryMessage(String type, String requestId, String kind,
                                             Integer hostCardId, String hostCardName,
                                             int totalDamage,
                                             List<CombatDamageRecipient> recipients,
                                             CombatDamageConstraints constraints) { }

    private record OrderingItem(String itemId, String description, Integer sourceCardId,
                                String sourceCardName, int originalPosition) { }

    private record ItemOrderingQueryMessage(String type, String requestId, String kind,
                                            String title, String prompt, boolean mandatory,
                                            boolean rememberAllowed, List<OrderingItem> items,
                                            List<String> originalOrder) { }

    private record UnsupportedQueryMessage(String type, String requestId, String kind,
                                           String offered, boolean replySupported) { }
}
