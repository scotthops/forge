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
                    System.err.println("DRIVER_BRIDGE_ERROR " + line);
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
        long sequence = interaction.get("sequence").getAsLong();
        if (!actedInteractions.add(sequence)) {
            return;
        }

        JsonArray playerIds = interaction.getAsJsonArray("selectablePlayerIds");
        if (bobId >= 0 && contains(playerIds, bobId)) {
            send(commandWithId("selectPlayer", "playerId", bobId));
            return;
        }

        JsonArray weak = interaction.getAsJsonArray("weaklySelectableCardIds");
        if (stage == Stage.PREPARE_LAND && localMainOnePriority() && contains(weak, mountainId)) {
            send(commandWithId("selectCard", "cardId", mountainId));
            stage = Stage.WAIT_LAND;
            return;
        }
        if (stage == Stage.WAIT_BOLT && readyToCast() && contains(weak, boltId)) {
            send(commandWithId("selectCard", "cardId", boltId));
            stage = Stage.WAIT_ABILITY;
            return;
        }
        if (stage == Stage.WAIT_MANA && contains(weak, mountainId)) {
            send(commandWithId("selectCard", "cardId", mountainId));
            stage = Stage.WAIT_STACK;
            return;
        }

        JsonArray selectable = interaction.getAsJsonArray("selectableCardIds");
        if (stage == Stage.WAIT_TARGET && contains(selectable, elvesId)) {
            send(commandWithId("selectCard", "cardId", elvesId));
            stage = Stage.WAIT_MANA;
            return;
        }

        JsonObject buttons = interaction.getAsJsonObject("buttons");
        if ("buttons".equals(string(interaction, "reason")) && buttons != null
                && buttons.get("okEnabled").getAsBoolean() && !shouldHold()) {
            JsonObject command = new JsonObject();
            command.addProperty("type", "button");
            command.addProperty("button", "ok");
            send(command);
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
                JsonObject command = new JsonObject();
                command.addProperty("type", "passPriority");
                send(command);
                stage = Stage.WAIT_RESOLUTION;
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

    private static JsonObject commandWithId(String type, String field, int id) {
        JsonObject command = new JsonObject();
        command.addProperty("type", type);
        command.addProperty(field, id);
        return command;
    }

    private void send(JsonObject command) {
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
