package forge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.card.CardView;
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
    public void testBlockedCombatDamageUsesCorrelatedQuery() throws Exception {
        try (Harness harness = new Harness()) {
            CardView attacker = new CardView(101, null, "Ball Lightning");
            CardView blocker = new CardView(202, null, "Grizzly Bears");

            Future<Map<CardView, Integer>> result = harness.executor.submit(() ->
                    harness.game.assignCombatDamage(attacker, List.of(blocker), 6,
                            null, false, false));

            JsonObject query = harness.awaitQuery("combatDamageAssignment");
            Assert.assertEquals(query.get("hostCardId").getAsInt(), attacker.getId());
            Assert.assertEquals(query.get("hostCardName").getAsString(), "Ball Lightning");
            Assert.assertEquals(query.getAsJsonArray("choices").size(), 1);
            Assert.assertTrue(query.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .get("description").getAsString().contains("Grizzly Bears"));

            harness.reply(query.get("requestId").getAsString(), 0);
            Map<CardView, Integer> assignment = result.get(5, TimeUnit.SECONDS);

            Assert.assertEquals(assignment, Map.of(blocker, 6));
            Assert.assertFalse(harness.output().contains("unsupportedQuery"));
        }
    }

    @Test
    public void testNonPositiveDamageNeedsNoHumanQuery() throws Exception {
        try (Harness harness = new Harness()) {
            Map<CardView, Integer> assignment = harness.game.assignCombatDamage(
                    new CardView(101, null, "Attacker"),
                    List.of(new CardView(202, null, "Blocker")), 0,
                    null, false, false);

            Assert.assertTrue(assignment.isEmpty());
            Assert.assertFalse(harness.output().contains("\"type\":\"query\""));
        }
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
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (String line : output().lines().toList()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    if ("query".equals(string(message, "type"))
                            && kind.equals(string(message, "kind"))) {
                        return message;
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Timed out waiting for " + kind + ": " + output());
        }

        private void reply(String requestId, int selectedId) throws Exception {
            String reply = "{\"schemaVersion\":1,\"type\":\"reply\",\"requestId\":\""
                    + requestId + "\",\"selectedId\":" + selectedId + "}\n";
            clientCommands.write(reply.getBytes(StandardCharsets.UTF_8));
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
