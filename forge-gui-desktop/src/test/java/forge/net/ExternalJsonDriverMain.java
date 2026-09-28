package forge.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A Forge-independent JSONL client used only by the Spike E process test. */
public final class ExternalJsonDriverMain {
    private static final int SCHEMA_VERSION = 1;
    private static final long FAILURE_DEADLINE_SECONDS = 90;
    private static final int RECENT_MESSAGE_LIMIT = 12;

    private enum Stage {
        PREPARE_LAND,
        WAIT_LAND,
        WAIT_BOLT,
        WAIT_ABILITY,
        WAIT_TARGET,
        WAIT_MANA,
        WAIT_STACK,
        WAIT_RESOLUTION
    }

    private enum Target {
        CARD,
        OPPONENT,
        SELF
    }

    private final Gson gson = new Gson();
    private final PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final Set<Long> actedInteractions = new HashSet<>();
    private Stage stage = Stage.PREPARE_LAND;
    private final Target target;
    private JsonObject state;
    private int bobId = -1;
    private int aliceId = -1;
    private int boltId = -1;
    private int elvesId = -1;
    private int mountainId = -1;
    private boolean abilityReplied;
    private boolean landAbilityReplied;
    private boolean landSelectionHandled;
    private boolean hardeningProbesSent;
    private boolean stackReadyToPass;
    private JsonObject pendingAsyncCommand;
    private String pendingQuery = "none";
    private String lastInteraction = "none";
    private final Deque<String> recentMessages = new ArrayDeque<>();

    private ExternalJsonDriverMain(Target target) {
        this.target = target;
    }

    public static void main(String[] args) throws Exception {
        Target target = args.length == 0 ? Target.CARD : Target.valueOf(args[0].toUpperCase());
        boolean complete = new ExternalJsonDriverMain(target).run();
        System.exit(complete ? 0 : 1);
    }

    private boolean run() throws Exception {
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "external-json-driver-deadline");
            thread.setDaemon(true);
            return thread;
        });
        deadline.schedule(() -> {
            printFailureDiagnostics("bounded failure deadline expired");
            System.exit(2);
        }, FAILURE_DEADLINE_SECONDS, TimeUnit.SECONDS);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                remember(message);
                if (integer(message, "schemaVersion", -1) != SCHEMA_VERSION) {
                    System.err.println("DRIVER_FAILURE unsupported bridge schema: " + line);
                    return false;
                }
                String type = string(message, "type");
                if ("state".equals(type)) {
                    state = message;
                    indexState();
                    if (resolved()) {
                        System.err.println("DRIVER_SUCCESS boltId=" + boltId + " target=" + target
                                + " landAbilitySelection=" + (landAbilityReplied ? "explicit" : "automatic")
                                + " abilitySelection=" + (abilityReplied ? "explicit" : "automatic"));
                        return true;
                    }
                    passResolvedSpellIfReady();
                } else if ("interaction".equals(type)) {
                    handleInteraction(message);
                } else if ("query".equals(type)) {
                    handleQuery(message);
                } else if ("error".equals(type)) {
                    handleError(message, line);
                } else if ("actionAccepted".equals(type)) {
                    handleActionAccepted(message);
                }
            }
        } finally {
            deadline.shutdownNow();
        }
        printFailureDiagnostics("bridge output closed before resolution");
        return false;
    }

    private void indexState() {
        JsonObject bob = playerNamed("Bob (JSON Driver)");
        if (bob != null) {
            bobId = integer(bob, "id", -1);
            JsonObject bolt = cardNamed(bob.getAsJsonArray("handVisible"), "Lightning Bolt");
            JsonObject mountainInHand = cardNamed(bob.getAsJsonArray("handVisible"), "Mountain");
            JsonObject mountainOnBattlefield = cardNamed(bob.getAsJsonArray("battlefield"), "Mountain");
            if (bolt != null) {
                boltId = integer(bolt, "id", boltId);
            }
            if (mountainOnBattlefield != null) {
                mountainId = integer(mountainOnBattlefield, "id", mountainId);
                if (stage == Stage.WAIT_LAND) {
                    landSelectionHandled = true;
                    stage = Stage.WAIT_BOLT;
                }
            } else if (mountainInHand != null && mountainId < 0) {
                mountainId = integer(mountainInHand, "id", mountainId);
            }
        }
        JsonObject alice = playerNamed("Alice (Host AI)");
        if (alice != null) {
            aliceId = integer(alice, "id", aliceId);
            JsonObject elves = cardNamed(alice.getAsJsonArray("battlefield"), "Llanowar Elves");
            if (elves == null) {
                elves = cardNamed(alice.getAsJsonArray("graveyard"), "Llanowar Elves");
            }
            if (elves != null) {
                elvesId = integer(elves, "id", elvesId);
            }
        }
    }

    private void handleInteraction(JsonObject interaction) {
        long sequence = interaction.get("interactionSequence").getAsLong();
        lastInteraction = "sequence=" + sequence + " reason=" + string(interaction, "reason");
        if (!actedInteractions.add(sequence)) {
            return;
        }

        JsonArray weak = interaction.getAsJsonArray("weaklySelectableCardIds");
        boolean actionableSnapshot = "buttons".equals(string(interaction, "reason"));
        if (stage == Stage.PREPARE_LAND && actionableSnapshot
                && localMainOnePriority() && contains(weak, mountainId)) {
            sendHardeningProbes(mountainId, sequence);
            sendTracked(commandWithId("selectCard", "cardId", mountainId, sequence));
            stage = Stage.WAIT_LAND;
            return;
        }
        if (stage == Stage.WAIT_BOLT && actionableSnapshot && readyToCast() && contains(weak, boltId)) {
            sendTracked(commandWithId("selectCard", "cardId", boltId, sequence));
            stage = Stage.WAIT_ABILITY;
            return;
        }
        if (stage == Stage.WAIT_MANA && actionableSnapshot && contains(weak, mountainId)) {
            sendTracked(commandWithId("selectCard", "cardId", mountainId, sequence));
            stage = Stage.WAIT_STACK;
            return;
        }

        JsonArray selectable = interaction.getAsJsonArray("selectableCardIds");
        JsonArray selectablePlayers = interaction.getAsJsonArray("selectablePlayerIds");
        // Forge may ask the bridge to choose among multiple abilities, or may select a single
        // unambiguous ability itself. The first structured target offer is authoritative evidence
        // that the latter path has completed; no synthetic ability event is required.
        if (stage == Stage.WAIT_ABILITY && targetIsOffered(selectable, selectablePlayers)) {
            stage = Stage.WAIT_TARGET;
        }
        if (stage == Stage.WAIT_TARGET && target == Target.SELF
                && contains(selectablePlayers, bobId)) {
            sendTracked(commandWithId("selectPlayer", "playerId", bobId, sequence));
            stage = Stage.WAIT_MANA;
            return;
        }
        if (stage == Stage.WAIT_TARGET && target == Target.OPPONENT
                && contains(selectablePlayers, aliceId)) {
            sendTracked(commandWithId("selectPlayer", "playerId", aliceId, sequence));
            stage = Stage.WAIT_MANA;
            return;
        }
        if (stage == Stage.WAIT_TARGET && target == Target.CARD
                && contains(selectable, elvesId)) {
            sendTracked(commandWithId("selectCard", "cardId", elvesId, sequence));
            stage = Stage.WAIT_MANA;
            return;
        }

        if (stage == Stage.WAIT_STACK && stackReadyToPass && actionableSnapshot) {
            JsonObject command = new JsonObject();
            command.addProperty("type", "passPriority");
            command.addProperty("interactionSequence", sequence);
            sendTracked(command);
            stage = Stage.WAIT_RESOLUTION;
            return;
        }

        JsonObject buttons = interaction.getAsJsonObject("buttons");
        if ("buttons".equals(string(interaction, "reason")) && buttons != null
                && buttons.get("okEnabled").getAsBoolean() && !shouldHold()) {
            JsonObject command = new JsonObject();
            command.addProperty("type", "button");
            command.addProperty("button", "ok");
            command.addProperty("interactionSequence", sequence);
            sendTracked(command);
        }
    }

    private void handleError(JsonObject error, String line) {
        System.err.println("DRIVER_BRIDGE_ERROR " + line);
        if (!"STALE_INTERACTION".equals(string(error, "code")) || pendingAsyncCommand == null) {
            return;
        }
        long received = error.get("receivedInteractionSequence").getAsLong();
        long pending = pendingAsyncCommand.get("interactionSequence").getAsLong();
        if (received != pending) {
            return;
        }
        pendingAsyncCommand.addProperty("interactionSequence",
                error.get("currentInteractionSequence").getAsLong());
        send(pendingAsyncCommand.deepCopy());
    }

    private void handleActionAccepted(JsonObject accepted) {
        if (pendingAsyncCommand != null
                && accepted.get("interactionSequence").getAsLong()
                == pendingAsyncCommand.get("interactionSequence").getAsLong()) {
            pendingAsyncCommand = null;
        }
    }

    private void handleQuery(JsonObject query) {
        pendingQuery = "requestId=" + string(query, "requestId") + " kind=" + string(query, "kind");
        if (!"abilityChoice".equals(string(query, "kind"))) {
            System.err.println("DRIVER_UNSUPPORTED_QUERY " + gson.toJson(query));
            return;
        }
        String hostCardName = string(query, "hostCardName");
        boolean landQuery = "Mountain".equals(hostCardName)
                && (stage == Stage.WAIT_LAND || stage == Stage.WAIT_STACK);
        boolean boltQuery = "Lightning Bolt".equals(hostCardName)
                && (stage == Stage.WAIT_ABILITY || stage == Stage.WAIT_TARGET);
        if (!landQuery && !boltQuery) {
            System.err.println("DRIVER_UNSUPPORTED_QUERY " + gson.toJson(query));
            return;
        }
        JsonObject selected = null;
        for (JsonElement element : query.getAsJsonArray("choices")) {
            JsonObject choice = element.getAsJsonObject();
            if (choice.get("canPlay").getAsBoolean()) {
                if (selected != null) {
                    System.err.println("DRIVER_FAILURE multiple playable Lightning Bolt abilities");
                    return;
                }
                selected = choice;
            }
        }
        if (selected == null) {
            System.err.println("DRIVER_FAILURE no playable Lightning Bolt ability");
            return;
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "reply");
        reply.addProperty("requestId", string(query, "requestId"));
        reply.addProperty("selectedId", selected.get("id").getAsInt());
        send(reply);
        pendingQuery = "none";
        if (landQuery) {
            landAbilityReplied = true;
        } else {
            abilityReplied = true;
            stage = Stage.WAIT_TARGET;
        }
    }

    private void passResolvedSpellIfReady() {
        if (stage != Stage.WAIT_STACK || state == null || bobId < 0
                || integer(state, "priorityPlayerId", -1) != bobId) {
            return;
        }
        for (JsonElement element : state.getAsJsonArray("stack")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject source = item.getAsJsonObject("source");
            if (source != null && "Lightning Bolt".equals(string(source, "name"))
                    && (target != Target.CARD
                            || cardNamed(item.getAsJsonArray("targets"), "Llanowar Elves") != null)) {
                stackReadyToPass = true;
                return;
            }
        }
    }

    private boolean localMainOnePriority() {
        return state != null && bobId >= 0
                && "MAIN1".equals(string(state, "phase"))
                && integer(state, "activePlayerId", -1) == bobId
                && integer(state, "priorityPlayerId", -1) == bobId;
    }

    private boolean readyToCast() {
        JsonObject bob = playerNamed("Bob (JSON Driver)");
        JsonObject alice = playerNamed("Alice (Host AI)");
        return localMainOnePriority() && bob != null && alice != null
                && cardNamed(bob.getAsJsonArray("handVisible"), "Lightning Bolt") != null
                && untappedCardNamed(bob.getAsJsonArray("battlefield"), "Mountain") != null
                && cardNamed(alice.getAsJsonArray("battlefield"), "Llanowar Elves") != null;
    }

    private boolean shouldHold() {
        if (stage == Stage.WAIT_LAND || stage == Stage.WAIT_ABILITY || stage == Stage.WAIT_TARGET
                || stage == Stage.WAIT_MANA || stage == Stage.WAIT_STACK
                || stage == Stage.WAIT_RESOLUTION) {
            return true;
        }
        if (stage == Stage.PREPARE_LAND) {
            JsonObject bob = playerNamed("Bob (JSON Driver)");
            return localMainOnePriority() && bob != null
                    && cardNamed(bob.getAsJsonArray("handVisible"), "Mountain") != null;
        }
        return stage == Stage.WAIT_BOLT && readyToCast();
    }

    private boolean resolved() {
        if (stage != Stage.WAIT_RESOLUTION || !landSelectionHandled) {
            return false;
        }
        JsonObject alice = playerNamed("Alice (Host AI)");
        JsonObject bob = playerNamed("Bob (JSON Driver)");
        if (alice == null || bob == null) {
            return false;
        }
        return switch (target) {
        case CARD -> cardNamed(alice.getAsJsonArray("battlefield"), "Llanowar Elves") == null
                && cardNamed(alice.getAsJsonArray("graveyard"), "Llanowar Elves") != null;
        case OPPONENT -> integer(alice, "life", -1) == 17 && integer(bob, "life", -1) == 20;
        case SELF -> integer(bob, "life", -1) == 17 && integer(alice, "life", -1) == 20;
        };
    }

    private boolean targetIsOffered(JsonArray cards, JsonArray players) {
        return switch (target) {
        case CARD -> contains(cards, elvesId);
        case OPPONENT -> contains(players, aliceId);
        case SELF -> contains(players, bobId);
        };
    }

    private void remember(JsonObject message) {
        String summary = "type=" + string(message, "type");
        if (message.has("interactionSequence")) {
            summary += " interactionSequence=" + message.get("interactionSequence").getAsLong();
        }
        if (message.has("requestId")) {
            summary += " requestId=" + string(message, "requestId");
        }
        if (message.has("reason")) {
            summary += " reason=" + string(message, "reason");
        }
        if (message.has("kind")) {
            summary += " kind=" + string(message, "kind");
        }
        recentMessages.addLast(summary);
        while (recentMessages.size() > RECENT_MESSAGE_LIMIT) {
            recentMessages.removeFirst();
        }
    }

    private void printFailureDiagnostics(String reason) {
        System.err.println("DRIVER_FAILURE " + reason + " stage=" + stage
                + " pendingQuery={" + pendingQuery + "} pendingCommand="
                + (pendingAsyncCommand == null ? "none" : gson.toJson(pendingAsyncCommand))
                + " lastInteraction={" + lastInteraction + "}");
        System.err.println("DRIVER_RECENT " + String.join(" | ", recentMessages));
    }

    private JsonObject playerNamed(String name) {
        if (state == null) {
            return null;
        }
        for (JsonElement element : state.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject();
            if (name.equals(string(player, "name"))) {
                return player;
            }
        }
        return null;
    }

    private static JsonObject cardNamed(JsonArray cards, String name) {
        if (cards != null) {
            for (JsonElement element : cards) {
                JsonObject card = element.getAsJsonObject();
                if (name.equals(string(card, "name"))) {
                    return card;
                }
            }
        }
        return null;
    }

    private static JsonObject untappedCardNamed(JsonArray cards, String name) {
        JsonObject card = cardNamed(cards, name);
        return card != null && !card.get("tapped").getAsBoolean() ? card : null;
    }

    private static boolean contains(JsonArray values, int expected) {
        if (values != null && expected >= 0) {
            for (JsonElement value : values) {
                if (value.getAsInt() == expected) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sendHardeningProbes(int cardId, long sequence) {
        if (hardeningProbesSent) {
            return;
        }
        JsonObject missingVersion = commandWithId("selectCard", "cardId", cardId, sequence);
        sendWithoutVersion(missingVersion);

        JsonObject unsupportedVersion = commandWithId("selectCard", "cardId", cardId, sequence);
        unsupportedVersion.addProperty("schemaVersion", 999);
        send(unsupportedVersion);

        JsonObject stale = commandWithId("selectCard", "cardId", cardId, sequence - 1);
        send(stale);
        hardeningProbesSent = true;
    }

    private static JsonObject commandWithId(String type, String field, int id, long interactionSequence) {
        JsonObject command = new JsonObject();
        command.addProperty("type", type);
        command.addProperty(field, id);
        command.addProperty("interactionSequence", interactionSequence);
        return command;
    }

    private void send(JsonObject command) {
        if (!command.has("schemaVersion")) {
            command.addProperty("schemaVersion", SCHEMA_VERSION);
        }
        String line = gson.toJson(command);
        System.err.println("DRIVER_ACTION " + line);
        output.println(line);
    }

    private void sendTracked(JsonObject command) {
        pendingAsyncCommand = command.deepCopy();
        send(command);
    }

    private void sendWithoutVersion(JsonObject command) {
        String line = gson.toJson(command);
        System.err.println("DRIVER_ACTION " + line);
        output.println(line);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(JsonObject object, String field, int fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }
}
