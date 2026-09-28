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

/** Drives one Cursed Scroll activation solely from structured bridge messages. */
public final class ExternalCursedScrollDriverMain {
    private static final int SCHEMA_VERSION = 1;
    private static final int RECENT_LIMIT = 14;
    private final Gson gson = new Gson();
    private final PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private final Set<Long> actedInteractions = new HashSet<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private JsonObject state;
    private JsonObject pendingCommand;
    private String pendingQuery = "none";
    private String stage = "setup";
    private int bobId = -1;
    private int aliceId = -1;
    private int scrollId = -1;
    private boolean castingScroll;
    private boolean activatingScroll;
    private boolean activationPassed;
    private boolean namedMountain;
    private boolean revealSeen;
    private boolean damageSeen;
    private boolean continuationSent;

    public static void main(String[] args) throws Exception {
        System.exit(new ExternalCursedScrollDriverMain().run() ? 0 : 1);
    }

    private boolean run() throws Exception {
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cursed-scroll-driver-deadline");
            thread.setDaemon(true);
            return thread;
        });
        deadline.schedule(() -> {
            diagnostics("bounded failure deadline expired");
            System.exit(2);
        }, 90, TimeUnit.SECONDS);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                remember(message);
                if (integer(message, "schemaVersion", -1) != SCHEMA_VERSION) {
                    diagnostics("unsupported bridge schema");
                    return false;
                }
                switch (string(message, "type")) {
                case "state" -> handleState(message);
                case "interaction" -> handleInteraction(message);
                case "query" -> handleQuery(message);
                case "reveal" -> handleReveal(message);
                case "error" -> handleError(message);
                case "actionAccepted" -> {
                    handleAccepted(message);
                    if (continuationSent && damageSeen && revealSeen) {
                        System.err.println("DRIVER_SUCCESS cursedScroll damage=2 reveal=Mountain "
                                + "continuation=accepted");
                        return true;
                    }
                }
                default -> { }
                }
            }
        } finally {
            deadline.shutdownNow();
        }
        diagnostics("bridge output closed before completion");
        return false;
    }

    private void handleState(JsonObject message) {
        state = message;
        JsonObject bob = playerNamed("Bob (JSON Driver)");
        JsonObject alice = playerNamed("Alice (Host AI)");
        if (bob != null) {
            bobId = integer(bob, "id", bobId);
            JsonObject scroll = cardNamed(bob.getAsJsonArray("handVisible"), "Cursed Scroll");
            if (scroll == null) {
                scroll = cardNamed(bob.getAsJsonArray("battlefield"), "Cursed Scroll");
            }
            if (scroll != null) {
                scrollId = integer(scroll, "id", scrollId);
            }
        }
        if (alice != null) {
            aliceId = integer(alice, "id", aliceId);
            if (integer(alice, "life", 20) == 18) {
                damageSeen = true;
                stage = "resume-after-resolution";
            }
        }
    }

    private void handleInteraction(JsonObject interaction) {
        long sequence = interaction.get("interactionSequence").getAsLong();
        if (!actedInteractions.add(sequence)) {
            return;
        }
        JsonArray weak = interaction.getAsJsonArray("weaklySelectableCardIds");
        JsonArray selectablePlayers = interaction.getAsJsonArray("selectablePlayerIds");
        JsonObject buttons = interaction.getAsJsonObject("buttons");

        if (damageSeen && buttons != null && buttons.get("okEnabled").getAsBoolean()) {
            continuationSent = true;
            sendTracked(button("ok", sequence));
            return;
        }
        if (activatingScroll) {
            if (contains(selectablePlayers, aliceId)) {
                stage = "select-target";
                sendTracked(withId("selectPlayer", "playerId", aliceId, sequence));
                return;
            }
            int offeredLand = firstOfferedUntappedMountain(weak);
            if (offeredLand >= 0) {
                stage = "pay-activation";
                sendTracked(withId("selectCard", "cardId", offeredLand, sequence));
                return;
            }
            if (activationOnStack() && !activationPassed) {
                stage = "pass-activation";
                activationPassed = true;
                sendTracked(pass(sequence));
                return;
            }
        }
        if (castingScroll) {
            int offeredLand = firstOfferedUntappedMountain(weak);
            if (offeredLand >= 0) {
                stage = "pay-scroll";
                sendTracked(withId("selectCard", "cardId", offeredLand, sequence));
                return;
            }
            if (scrollSpellOnStack()) {
                stage = "pass-scroll";
                sendTracked(pass(sequence));
                return;
            }
        }

        if (localMainPriority()) {
            JsonObject bob = playerNamed("Bob (JSON Driver)");
            if (bob != null) {
                JsonArray battlefield = bob.getAsJsonArray("battlefield");
                JsonObject scrollInPlay = cardNamed(battlefield, "Cursed Scroll");
                int handMountain = firstCardId(bob.getAsJsonArray("handVisible"), "Mountain");
                int lands = countNamed(battlefield, "Mountain");
                if (handMountain >= 0 && contains(weak, handMountain) && lands < 3) {
                    stage = "play-land";
                    sendTracked(withId("selectCard", "cardId", handMountain, sequence));
                    return;
                }
                if (scrollInPlay == null && scrollId >= 0 && lands >= 1
                        && contains(weak, scrollId)) {
                    stage = "cast-scroll";
                    castingScroll = true;
                    sendTracked(withId("selectCard", "cardId", scrollId, sequence));
                    return;
                }
                if (scrollInPlay != null && untappedMountains(battlefield) >= 3
                        && contains(weak, scrollId)) {
                    stage = "activate-scroll";
                    activatingScroll = true;
                    sendTracked(withId("selectCard", "cardId", scrollId, sequence));
                    return;
                }
            }
        }
        if (buttons != null && buttons.get("okEnabled").getAsBoolean()
                && pendingQuery.equals("none")) {
            sendTracked(button("ok", sequence));
        }
    }

    private void handleQuery(JsonObject query) {
        String requestId = string(query, "requestId");
        String kind = string(query, "kind");
        pendingQuery = "requestId=" + requestId + " kind=" + kind;
        if ("abilityChoice".equals(kind)) {
            JsonObject selected = null;
            for (JsonElement element : query.getAsJsonArray("choices")) {
                JsonObject choice = element.getAsJsonObject();
                if (choice.get("canPlay").getAsBoolean()) {
                    if (selected != null) {
                        diagnostics("multiple playable abilities");
                        return;
                    }
                    selected = choice;
                }
            }
            if (selected == null) {
                diagnostics("no playable ability");
                return;
            }
            JsonObject reply = reply(requestId);
            reply.addProperty("selectedId", selected.get("id").getAsInt());
            send(reply);
            pendingQuery = "none";
            return;
        }
        if (!"genericChoice".equals(kind)) {
            diagnostics("unsupported query kind=" + kind);
            return;
        }
        String mountainId = null;
        for (JsonElement element : query.getAsJsonArray("options")) {
            JsonObject option = element.getAsJsonObject();
            if ("Mountain".equals(string(option, "label"))) {
                mountainId = string(option, "optionId");
                break;
            }
        }
        if (mountainId == null || integer(query, "min", -1) != 1
                || integer(query, "max", -1) != 1) {
            diagnostics("Mountain was not offered as an exact-one generic choice");
            return;
        }
        JsonObject reply = reply(requestId);
        JsonArray selected = new JsonArray();
        selected.add(mountainId);
        reply.add("selectedOptionIds", selected);
        stage = "name-mountain";
        namedMountain = true;
        send(reply);
        pendingQuery = "none";
    }

    private void handleReveal(JsonObject reveal) {
        if (!namedMountain) {
            diagnostics("reveal arrived before card-name reply");
            return;
        }
        JsonArray items = reveal.getAsJsonArray("items");
        revealSeen = items != null && items.size() == 1
                && "Mountain".equals(string(items.get(0).getAsJsonObject(), "label"));
        stage = revealSeen ? "revealed-mountain" : "unexpected-reveal";
    }

    private void handleError(JsonObject error) {
        System.err.println("DRIVER_BRIDGE_ERROR " + gson.toJson(error));
        if (!"STALE_INTERACTION".equals(string(error, "code")) || pendingCommand == null) {
            return;
        }
        long received = error.get("receivedInteractionSequence").getAsLong();
        if (received == pendingCommand.get("interactionSequence").getAsLong()) {
            pendingCommand.addProperty("interactionSequence",
                    error.get("currentInteractionSequence").getAsLong());
            send(pendingCommand.deepCopy());
        }
    }

    private void handleAccepted(JsonObject accepted) {
        if (pendingCommand != null && accepted.get("interactionSequence").getAsLong()
                == pendingCommand.get("interactionSequence").getAsLong()) {
            pendingCommand = null;
        }
    }

    private boolean localMainPriority() {
        return state != null && bobId >= 0 && "MAIN1".equals(string(state, "phase"))
                && integer(state, "activePlayerId", -1) == bobId
                && integer(state, "priorityPlayerId", -1) == bobId;
    }

    private boolean scrollSpellOnStack() {
        return stackHasScroll() && !scrollOnBattlefield();
    }

    private boolean activationOnStack() {
        return stackHasScroll() && scrollOnBattlefield();
    }

    private boolean stackHasScroll() {
        if (state == null) return false;
        for (JsonElement element : state.getAsJsonArray("stack")) {
            JsonObject source = element.getAsJsonObject().getAsJsonObject("source");
            if (source != null && "Cursed Scroll".equals(string(source, "name"))) return true;
        }
        return false;
    }

    private boolean scrollOnBattlefield() {
        JsonObject bob = playerNamed("Bob (JSON Driver)");
        return bob != null && cardNamed(bob.getAsJsonArray("battlefield"), "Cursed Scroll") != null;
    }

    private int firstOfferedUntappedMountain(JsonArray offered) {
        JsonObject bob = playerNamed("Bob (JSON Driver)");
        if (bob == null) return -1;
        for (JsonElement element : bob.getAsJsonArray("battlefield")) {
            JsonObject card = element.getAsJsonObject();
            int id = integer(card, "id", -1);
            if ("Mountain".equals(string(card, "name"))
                    && !card.get("tapped").getAsBoolean() && contains(offered, id)) return id;
        }
        return -1;
    }

    private JsonObject playerNamed(String name) {
        if (state == null) return null;
        for (JsonElement element : state.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject();
            if (name.equals(string(player, "name"))) return player;
        }
        return null;
    }

    private static JsonObject cardNamed(JsonArray cards, String name) {
        if (cards != null) for (JsonElement element : cards) {
            JsonObject card = element.getAsJsonObject();
            if (name.equals(string(card, "name"))) return card;
        }
        return null;
    }

    private static int firstCardId(JsonArray cards, String name) {
        JsonObject card = cardNamed(cards, name);
        return card == null ? -1 : integer(card, "id", -1);
    }

    private static int countNamed(JsonArray cards, String name) {
        int count = 0;
        if (cards != null) for (JsonElement element : cards) {
            if (name.equals(string(element.getAsJsonObject(), "name"))) count++;
        }
        return count;
    }

    private static int untappedMountains(JsonArray cards) {
        int count = 0;
        if (cards != null) for (JsonElement element : cards) {
            JsonObject card = element.getAsJsonObject();
            if ("Mountain".equals(string(card, "name"))
                    && !card.get("tapped").getAsBoolean()) count++;
        }
        return count;
    }

    private static boolean contains(JsonArray array, int id) {
        if (array != null && id >= 0) for (JsonElement element : array) {
            if (element.getAsInt() == id) return true;
        }
        return false;
    }

    private static JsonObject withId(String type, String field, int id, long sequence) {
        JsonObject command = new JsonObject();
        command.addProperty("type", type);
        command.addProperty(field, id);
        command.addProperty("interactionSequence", sequence);
        return command;
    }

    private static JsonObject button(String name, long sequence) {
        JsonObject command = new JsonObject();
        command.addProperty("type", "button");
        command.addProperty("button", name);
        command.addProperty("interactionSequence", sequence);
        return command;
    }

    private static JsonObject pass(long sequence) {
        JsonObject command = new JsonObject();
        command.addProperty("type", "passPriority");
        command.addProperty("interactionSequence", sequence);
        return command;
    }

    private static JsonObject reply(String requestId) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "reply");
        reply.addProperty("requestId", requestId);
        return reply;
    }

    private void sendTracked(JsonObject command) {
        pendingCommand = command.deepCopy();
        send(command);
    }

    private void send(JsonObject command) {
        command.addProperty("schemaVersion", SCHEMA_VERSION);
        System.err.println("DRIVER_ACTION " + gson.toJson(command));
        output.println(gson.toJson(command));
    }

    private void remember(JsonObject message) {
        String value = "type=" + string(message, "type")
                + (message.has("interactionSequence")
                        ? " interaction=" + message.get("interactionSequence").getAsLong() : "")
                + (message.has("requestId") ? " request=" + string(message, "requestId") : "")
                + (message.has("kind") ? " kind=" + string(message, "kind") : "");
        recent.addLast(value);
        while (recent.size() > RECENT_LIMIT) recent.removeFirst();
    }

    private void diagnostics(String reason) {
        System.err.println("DRIVER_FAILURE " + reason + " stage=" + stage
                + " pendingQuery={" + pendingQuery + "} pendingCommand="
                + (pendingCommand == null ? "none" : gson.toJson(pendingCommand)));
        System.err.println("DRIVER_RECENT " + String.join(" | ", recent));
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
