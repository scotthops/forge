package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.card.CardView;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollectionView;
import forge.game.keyword.KeywordView;
import forge.game.player.PlayerView;
import forge.trackable.TrackableProperty;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class CombatDamageBridgeTest {
    @Test(timeOut = 10000)
    public void testTrampleQueryExposesPlayerAndAcceptsOneDamagePlusFiveTrample() throws Exception {
        try (Harness harness = new Harness()) {
            CardView attacker = card(101, "Ball Lightning", 1, "Trample");
            CardView blocker = card(202, "One-Toughness Blocker", 1);
            PlayerView defender = player(303, "Opponent");

            Future<Map<CardView, Integer>> result = harness.executor.submit(() ->
                    harness.game.assignCombatDamage(attacker, List.of(blocker), 6,
                            defender, false, false));

            JsonObject query = harness.awaitQuery("combatDamageAssignment");
            Assert.assertEquals(query.get("hostCardId").getAsInt(), attacker.getId());
            Assert.assertEquals(query.get("hostCardName").getAsString(), "Ball Lightning");
            Assert.assertEquals(query.get("totalDamage").getAsInt(), 6);

            JsonArray recipients = query.getAsJsonArray("recipients");
            Assert.assertEquals(recipients.size(), 2);
            assertRecipient(recipients.get(0).getAsJsonObject(), "blocker:0", "card",
                    blocker.getId(), "blocker", 1);
            assertRecipient(recipients.get(1).getAsJsonObject(), "defender", "player",
                    defender.getId(), "defender", 0);
            Assert.assertTrue(query.getAsJsonObject("constraints")
                    .get("orderedAssignment").getAsBoolean());
            Assert.assertTrue(query.getAsJsonObject("constraints")
                    .get("defenderRequiresLethalBlockers").getAsBoolean());

            harness.replyDamage(query.get("requestId").getAsString(),
                    Map.of("blocker:0", 1, "defender", 5));
            Map<CardView, Integer> assignment = result.get(5, TimeUnit.SECONDS);

            Assert.assertEquals(assignment.get(blocker), Integer.valueOf(1));
            Assert.assertEquals(assignment.get(null), Integer.valueOf(5));
            Assert.assertEquals(assignment.size(), 2);
            Assert.assertFalse(harness.output().contains("unsupportedQuery"));
        }
    }

    @Test(timeOut = 10000)
    public void testTrampleMayLegallyAssignAllDamageToBlocker() throws Exception {
        try (Harness harness = new Harness()) {
            CardView attacker = card(101, "Generic Tramper", 6, "Trample");
            CardView blocker = card(202, "Small Blocker", 1);
            PlayerView defender = player(303, "Opponent");

            Future<Map<CardView, Integer>> result = harness.executor.submit(() ->
                    harness.game.assignCombatDamage(attacker, List.of(blocker), 6,
                            defender, false, false));
            JsonObject query = harness.awaitQuery("combatDamageAssignment");
            harness.replyDamage(query.get("requestId").getAsString(),
                    Map.of("blocker:0", 6, "defender", 0));

            Map<CardView, Integer> assignment = result.get(5, TimeUnit.SECONDS);
            Assert.assertEquals(assignment.get(blocker), Integer.valueOf(6));
            Assert.assertEquals(assignment.get(null), Integer.valueOf(0));
        }
    }

    @Test(timeOut = 10000)
    public void testMultipleBlockersRetainOrderAndDefenderRecipient() throws Exception {
        try (Harness harness = new Harness()) {
            CardView attacker = card(101, "Generic Tramper", 6, "Trample");
            CardView first = card(201, "First Blocker", 1);
            CardView second = card(202, "Second Blocker", 2);
            PlayerView defender = player(303, "Opponent");

            Future<Map<CardView, Integer>> result = harness.executor.submit(() ->
                    harness.game.assignCombatDamage(attacker, List.of(first, second), 6,
                            defender, false, false));
            JsonObject query = harness.awaitQuery("combatDamageAssignment");
            JsonArray recipients = query.getAsJsonArray("recipients");
            Assert.assertEquals(recipients.size(), 3);
            Assert.assertEquals(recipients.get(0).getAsJsonObject().get("entityId").getAsInt(),
                    first.getId());
            Assert.assertEquals(recipients.get(1).getAsJsonObject().get("entityId").getAsInt(),
                    second.getId());
            Assert.assertEquals(recipients.get(2).getAsJsonObject().get("entityType").getAsString(),
                    "player");

            harness.replyDamage(query.get("requestId").getAsString(),
                    Map.of("blocker:0", 1, "blocker:1", 2, "defender", 3));
            Map<CardView, Integer> assignment = result.get(5, TimeUnit.SECONDS);
            Assert.assertEquals(assignment.get(first), Integer.valueOf(1));
            Assert.assertEquals(assignment.get(second), Integer.valueOf(2));
            Assert.assertEquals(assignment.get(null), Integer.valueOf(3));
        }
    }

    @Test(timeOut = 10000)
    public void testIllegalTrampleReplyIsRejectedWithoutCompletingQuery() throws Exception {
        try (Harness harness = new Harness()) {
            CardView attacker = card(101, "Generic Tramper", 6, "Trample");
            CardView blocker = card(202, "Small Blocker", 1);
            PlayerView defender = player(303, "Opponent");

            Future<Map<CardView, Integer>> result = harness.executor.submit(() ->
                    harness.game.assignCombatDamage(attacker, List.of(blocker), 6,
                            defender, false, false));
            JsonObject query = harness.awaitQuery("combatDamageAssignment");
            String requestId = query.get("requestId").getAsString();
            harness.replyDamage(requestId, Map.of("blocker:0", 0, "defender", 6));
            harness.awaitError("invalidCombatDamageAssignment");
            Assert.assertFalse(result.isDone(), "An illegal reply must not resume Forge combat");

            harness.replyDamage(requestId, Map.of("blocker:0", 1, "defender", 5));
            Assert.assertEquals(result.get(5, TimeUnit.SECONDS).get(null), Integer.valueOf(5));
        }
    }

    @Test
    public void testUnblockedAndOrdinarySingleBlockerNeedNoDamageQuery() throws Exception {
        try (Harness harness = new Harness()) {
            PlayerView defender = player(303, "Opponent");
            CardView trampler = card(101, "Trampler", 6, "Trample");
            Map<CardView, Integer> unblocked = harness.game.assignCombatDamage(
                    trampler, List.of(), 6, defender, false, false);
            Assert.assertEquals(unblocked.get(null), Integer.valueOf(6));

            CardView ordinary = card(102, "Ordinary Attacker", 3);
            CardView blocker = card(202, "Blocker", 3);
            Map<CardView, Integer> blocked = harness.game.assignCombatDamage(
                    ordinary, List.of(blocker), 3, defender, false, false);
            Assert.assertEquals(blocked, Map.of(blocker, 3));
            Assert.assertFalse(harness.output().contains("\"type\":\"query\""));
        }
    }

    @Test
    public void testNonPositiveDamageNeedsNoHumanQuery() throws Exception {
        try (Harness harness = new Harness()) {
            Map<CardView, Integer> assignment = harness.game.assignCombatDamage(
                    card(101, "Attacker", 1), List.of(card(202, "Blocker", 1)), 0,
                    player(303, "Opponent"), false, false);

            Assert.assertTrue(assignment.isEmpty());
            Assert.assertFalse(harness.output().contains("\"type\":\"query\""));
        }
    }

    private static CardView card(int id, String name, int toughness, String... keywords) {
        CardView card = new CardView(id, null, name);
        card.set(TrackableProperty.LethalDamage, toughness);
        if (List.of(keywords).contains("Trample")) {
            card.getCurrentState().set(TrackableProperty.Keywords,
                    new KeywordCollectionView(List.of(new TestKeywordView(Keyword.TRAMPLE))));
        }
        return card;
    }

    private static PlayerView player(int id, String name) {
        PlayerView player = new PlayerView(id, null);
        player.set(TrackableProperty.Name, name);
        player.set(TrackableProperty.Life, 20);
        return player;
    }

    private static void assertRecipient(JsonObject recipient, String key, String entityType,
            int entityId, String role, int minimumDamage) {
        Assert.assertEquals(recipient.get("key").getAsString(), key);
        Assert.assertEquals(recipient.get("entityType").getAsString(), entityType);
        Assert.assertEquals(recipient.get("entityId").getAsInt(), entityId);
        Assert.assertEquals(recipient.get("role").getAsString(), role);
        Assert.assertEquals(recipient.get("minimumDamage").getAsInt(), minimumDamage);
    }

    private record TestKeywordView(Keyword keyword) implements KeywordView {
        @Override public String original() { return keyword.toString(); }
        @Override public String title() { return keyword.toString(); }
        @Override public String reminderText() { return ""; }
    }

    private static final class Harness implements AutoCloseable {
        private final PipedInputStream bridgeInput = new PipedInputStream();
        private final PipedOutputStream clientCommands = new PipedOutputStream(bridgeInput);
        private final ByteArrayOutputStream bridgeOutput = new ByteArrayOutputStream();
        private final PrintStream bridgePrintStream = new PrintStream(
                bridgeOutput, true, StandardCharsets.UTF_8);
        private final JsonLineTransport transport = new JsonLineTransport(bridgeInput, bridgePrintStream);
        private final BridgeGuiBase guiBase = new BridgeGuiBase(".");
        private final BridgeProtocol protocol = new BridgeProtocol(transport, guiBase);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final BridgeGuiGame game;

        private Harness() throws Exception {
            BridgePrinter printer = new BridgePrinter(protocol);
            game = new BridgeGuiGame(printer, protocol, new NoOpListener());
            protocol.start(command -> { }, () -> { });
        }

        private JsonObject awaitQuery(String kind) throws InterruptedException {
            return awaitMessage("query", "kind", kind);
        }

        private JsonObject awaitError(String code) throws InterruptedException {
            return awaitMessage("error", "code", code);
        }

        private JsonObject awaitMessage(String type, String field, String expected)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (String line : output().lines().toList()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    if (type.equals(string(message, "type"))
                            && expected.equals(string(message, field))) {
                        return message;
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Timed out waiting for " + type + "/" + expected
                    + ": " + output());
        }

        private void replyDamage(String requestId, Map<String, Integer> amounts) throws Exception {
            JsonObject reply = new JsonObject();
            reply.addProperty("schemaVersion", 1);
            reply.addProperty("type", "reply");
            reply.addProperty("requestId", requestId);
            JsonArray assignments = new JsonArray();
            amounts.forEach((recipientKey, amount) -> {
                JsonObject assignment = new JsonObject();
                assignment.addProperty("recipientKey", recipientKey);
                assignment.addProperty("amount", amount);
                assignments.add(assignment);
            });
            reply.add("assignments", assignments);
            clientCommands.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            clientCommands.flush();
        }

        private String output() {
            return bridgeOutput.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws Exception {
            protocol.close();
            transport.close();
            guiBase.close();
            clientCommands.close();
            bridgePrintStream.close();
            executor.shutdownNow();
        }
    }

    private static String string(JsonObject object, String field) {
        return object.has(field) ? object.get(field).getAsString() : null;
    }

    private static final class NoOpListener implements BridgeGuiGame.Listener {
        @Override public void controllerCreated() { }
        @Override public void stateObserved() { }
        @Override public void interactionObserved() { }
        @Override public void unsupportedRequiredQuery() { }
    }
}
