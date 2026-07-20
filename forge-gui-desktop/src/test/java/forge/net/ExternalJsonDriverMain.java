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
import java.util.HashSet;
import java.util.Set;

/** A Forge-independent JSONL client used only by the Spike E process test. */
public final class ExternalJsonDriverMain {
    private static final int SCHEMA_VERSION = 1;

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

    private final Gson gson = new Gson();
    private final PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final Set<Long> actedInteractions = new HashSet<>();
    private Stage stage = Stage.PREPARE_LAND;
    private JsonObject state;
    private int bobId = -1;
    private int boltId = -1;
    private int elvesId = -1;
    private int mountainId = -1;
    private boolean abilityReplied;
    private boolean landAbilityReplied;
    private boolean hardeningProbesSent;
    private boolean stackReadyToPass;
    private JsonObject pendingAsyncCommand;

    private ExternalJsonDriverMain() {
    }

    public static void main(String[] args) throws Exception {
        boolean complete = new ExternalJsonDriverMain().run();
        System.exit(complete ? 0 : 1);
    }

    private boolean run() throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                if (integer(message, "schemaVersion", -1) != SCHEMA_VERSION) {
                    System.err.println("DRIVER_FAILURE unsupported bridge schema: " + line);
                    return false;
                }
                String type = string(message, "type");
                if ("state".equals(type)) {
                    state = message;
                    indexState();
                    if (resolved()) {
                        System.err.println("DRIVER_SUCCESS boltId=" + boltId + " targetId=" + elvesId
                                + " landAbilityReply=" + landAbilityReplied
                                + " abilityReply=" + abilityReplied);
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
        }
        System.err.println("DRIVER_FAILURE bridge output closed before resolution stage=" + stage);
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
                    stage = Stage.WAIT_BOLT;
                }
            } else if (mountainInHand != null && mountainId < 0) {
                mountainId = integer(mountainInHand, "id", mountainId);
            }
        }
        JsonObject alice = playerNamed("Alice (Host AI)");
        if (alice != null) {
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
        if (!actedInteractions.add(sequence)) {
            return;
        }

        JsonArray playerIds = interaction.getAsJsonArray("selectablePlayerIds");
        if (bobId >= 0 && contains(playerIds, bobId)) {
            sendTracked(commandWithId("selectPlayer", "playerId", bobId, sequence));
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
        if (stage == Stage.WAIT_TARGET && actionableSnapshot && contains(selectable, elvesId)) {
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
                    && cardNamed(item.getAsJsonArray("targets"), "Llanowar Elves") != null) {
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
        if (stage != Stage.WAIT_RESOLUTION || !landAbilityReplied || !abilityReplied) {
            return false;
        }
        JsonObject alice = playerNamed("Alice (Host AI)");
        return alice != null
                && cardNamed(alice.getAsJsonArray("battlefield"), "Llanowar Elves") == null
                && cardNamed(alice.getAsJsonArray("graveyard"), "Llanowar Elves") != null;
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
