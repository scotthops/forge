package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
import forge.gui.interfaces.IGuiGame;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ItemOrderingBridgeTest {
    @BeforeClass
    public void initializeLocalizer() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = Files.isDirectory(current.resolve("forge-gui")) ? current : current.getParent();
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", root.resolve("forge-gui/res/languages")
                + File.separator);
    }

    @Test(timeOut = 10000)
    public void testIdenticalDescriptionsHaveDistinctIdsAndReturnedPermutationIsRespected()
            throws Exception {
        try (Harness harness = new Harness(5000)) {
            SpellAbilityView first = abilityView(101, "Jackal Pup", "damage trigger");
            SpellAbilityView second = abilityView(102, "Jackal Pup", "damage trigger");
            SpellAbilityView third = abilityView(103, "Jackal Pup", "damage trigger");
            List<SpellAbilityView> original = List.of(first, second, third);

            Future<IGuiGame.OrderResult<SpellAbilityView>> result = harness.executor.submit(() ->
                    harness.game.order("Order simultaneous triggers", "Resolve first",
                            0, 0, original, List.of(), null, false, true));

            JsonObject query = harness.awaitMessage("query", "kind", "itemOrdering", 1);
            Assert.assertEquals(query.get("title").getAsString(), "Order simultaneous triggers");
            Assert.assertEquals(query.get("prompt").getAsString(), "Resolve first");
            Assert.assertTrue(query.get("mandatory").getAsBoolean());
            Assert.assertTrue(query.get("rememberAllowed").getAsBoolean());

            JsonArray items = query.getAsJsonArray("items");
            Assert.assertEquals(items.size(), 3);
            Assert.assertEquals(items.asList().stream()
                    .map(item -> item.getAsJsonObject().get("itemId").getAsString())
                    .distinct().count(), 3);
            Assert.assertTrue(items.asList().stream().allMatch(item ->
                    "damage trigger".equals(item.getAsJsonObject()
                            .get("description").getAsString())));
            Assert.assertEquals(items.get(0).getAsJsonObject().get("sourceCardId").getAsInt(), 101);
            Assert.assertEquals(items.get(1).getAsJsonObject().get("sourceCardId").getAsInt(), 102);
            Assert.assertEquals(items.get(2).getAsJsonObject().get("sourceCardId").getAsInt(), 103);

            List<String> ids = query.getAsJsonArray("originalOrder").asList().stream()
                    .map(element -> element.getAsString()).toList();
            harness.replyOrder(query.get("requestId").getAsString(),
                    List.of(ids.get(2), ids.get(0), ids.get(1)), true);

            IGuiGame.OrderResult<SpellAbilityView> ordered = result.get(5, TimeUnit.SECONDS);
            Assert.assertEquals(ordered.ordered(), List.of(third, first, second));
            Assert.assertTrue(ordered.rememberDecision());
            Assert.assertFalse(harness.output().contains("unsupportedQuery"));
        }
    }

    @Test(timeOut = 10000)
    public void testMissingDuplicateAndUnknownIdsAreRejectedUntilCompletePermutation()
            throws Exception {
        try (Harness harness = new Harness(5000)) {
            List<SpellAbilityView> original = List.of(
                    abilityView(101, "Source", "First"),
                    abilityView(102, "Source", "Second"),
                    abilityView(103, "Source", "Third"));
            Future<IGuiGame.OrderResult<SpellAbilityView>> result = harness.executor.submit(() ->
                    harness.game.order("Order", "First", 0, 0,
                            original, List.of(), null, false, false));
            JsonObject query = harness.awaitMessage("query", "kind", "itemOrdering", 1);
            String requestId = query.get("requestId").getAsString();
            List<String> ids = query.getAsJsonArray("originalOrder").asList().stream()
                    .map(element -> element.getAsString()).toList();

            harness.replyOrder(requestId, List.of(ids.get(0), ids.get(1)), false);
            harness.awaitMessage("error", "code", "invalidItemOrder", 1);
            Assert.assertFalse(result.isDone());

            harness.replyOrder(requestId, List.of(ids.get(0), ids.get(0), ids.get(2)), false);
            harness.awaitMessage("error", "code", "invalidItemOrder", 2);
            Assert.assertFalse(result.isDone());

            harness.replyOrder(requestId, List.of(ids.get(0), ids.get(1), "not-offered"), false);
            harness.awaitMessage("error", "code", "invalidItemOrder", 3);
            Assert.assertFalse(result.isDone());

            harness.replyOrder(requestId, ids, false);
            Assert.assertEquals(result.get(5, TimeUnit.SECONDS).ordered(), original);
        }
    }

    @Test(timeOut = 10000)
    public void testTimeoutReturnsOriginalOrderInsteadOfNull() throws Exception {
        try (Harness harness = new Harness(75)) {
            List<SpellAbilityView> original = List.of(
                    abilityView(101, "Source", "First"),
                    abilityView(102, "Source", "Second"));

            IGuiGame.OrderResult<SpellAbilityView> result = harness.game.order(
                    "Order", "First", 0, 0, original, List.of(), null, false, false);

            Assert.assertNotNull(result);
            Assert.assertEquals(result.ordered(), original);
            harness.awaitMessage("error", "code", "queryTimeout", 1);
        }
    }

    @Test(timeOut = 10000)
    public void testProtocolCloseReturnsOriginalOrderInsteadOfNull() throws Exception {
        try (Harness harness = new Harness(5000)) {
            List<SpellAbilityView> original = List.of(
                    abilityView(101, "Source", "First"),
                    abilityView(102, "Source", "Second"));
            Future<IGuiGame.OrderResult<SpellAbilityView>> result = harness.executor.submit(() ->
                    harness.game.order("Order", "First", 0, 0,
                            original, List.of(), null, false, false));
            harness.awaitMessage("query", "kind", "itemOrdering", 1);

            harness.protocol.close();

            IGuiGame.OrderResult<SpellAbilityView> fallback = result.get(5, TimeUnit.SECONDS);
            Assert.assertNotNull(fallback);
            Assert.assertEquals(fallback.ordered(), original);
        }
    }

    private static SpellAbilityView abilityView(int cardId, String cardName, String description) {
        Card source = new Card(cardId, null);
        source.setName(cardName);
        SpellAbility ability = new SpellAbility.EmptySa(ApiType.Cleanup, source);
        ability.setDescription(description);
        return ability.getView();
    }

    private static final class Harness implements AutoCloseable {
        private final PipedInputStream bridgeInput = new PipedInputStream();
        private final PipedOutputStream clientCommands = new PipedOutputStream(bridgeInput);
        private final ByteArrayOutputStream bridgeOutput = new ByteArrayOutputStream();
        private final PrintStream bridgePrintStream = new PrintStream(
                bridgeOutput, true, StandardCharsets.UTF_8);
        private final JsonLineTransport transport = new JsonLineTransport(
                bridgeInput, bridgePrintStream);
        private final BridgeGuiBase guiBase = new BridgeGuiBase(".");
        private final BridgeProtocol protocol;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final BridgeGuiGame game;

        private Harness(long timeoutMillis) throws Exception {
            protocol = new BridgeProtocol(transport, guiBase, timeoutMillis);
            game = new BridgeGuiGame(new BridgePrinter(protocol), protocol, new NoOpListener());
            protocol.start(command -> { }, () -> { });
        }

        private JsonObject awaitMessage(String type, String field, String expected, int occurrence)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                List<JsonObject> matches = output().lines()
                        .filter(line -> !line.isBlank())
                        .map(line -> JsonParser.parseString(line).getAsJsonObject())
                        .filter(message -> type.equals(string(message, "type"))
                                && expected.equals(string(message, field)))
                        .toList();
                if (matches.size() >= occurrence) {
                    return matches.get(occurrence - 1);
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Timed out waiting for " + type + "/" + expected
                    + " occurrence " + occurrence + ": " + output());
        }

        private void replyOrder(String requestId, List<String> orderedItemIds,
                boolean rememberDecision) throws Exception {
            JsonObject reply = new JsonObject();
            reply.addProperty("schemaVersion", 1);
            reply.addProperty("type", "reply");
            reply.addProperty("requestId", requestId);
            JsonArray order = new JsonArray();
            orderedItemIds.forEach(order::add);
            reply.add("orderedItemIds", order);
            reply.addProperty("rememberDecision", rememberDecision);
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
