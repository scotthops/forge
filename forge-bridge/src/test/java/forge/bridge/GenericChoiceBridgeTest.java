package forge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

public class GenericChoiceBridgeTest {
    @BeforeClass
    public void initializeLocalizer() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = Files.isDirectory(current.resolve("forge-gui")) ? current : current.getParent();
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", root.resolve("forge-gui/res/languages")
                + File.separator);
    }

    @Test(timeOut = 10000)
    public void testOpaqueChoiceIdReturnsOriginalObject() throws Exception {
        try (Harness harness = new Harness(5000)) {
            Choice mountain = new Choice("Mountain", 1);
            Choice shock = new Choice("Shock", 2);
            Future<List<Choice>> result = harness.executor.submit(() -> harness.game.getChoices(
                    "Choose a card name", 1, 1, List.of(mountain, shock), null, Choice::label));

            JsonObject query = harness.awaitMessage("query", "kind", "genericChoice", 1);
            Assert.assertEquals(query.get("prompt").getAsString(), "Choose a card name");
            Assert.assertEquals(query.get("min").getAsInt(), 1);
            Assert.assertEquals(query.get("max").getAsInt(), 1);
            JsonArray options = query.getAsJsonArray("options");
            Assert.assertEquals(options.size(), 2);
            Assert.assertEquals(options.get(0).getAsJsonObject().get("label").getAsString(),
                    "Mountain");
            String optionId = options.get(0).getAsJsonObject().get("optionId").getAsString();

            harness.reply(query.get("requestId").getAsString(), List.of(optionId));
            List<Choice> selected = result.get(5, TimeUnit.SECONDS);
            Assert.assertSame(selected.get(0), mountain);
        }
    }

    @Test(timeOut = 10000)
    public void testInvalidRepliesDoNotCompletePendingChoice() throws Exception {
        try (Harness harness = new Harness(5000)) {
            List<Choice> choices = List.of(new Choice("Mountain", 1), new Choice("Shock", 2));
            Future<List<Choice>> result = harness.executor.submit(() -> harness.game.getChoices(
                    "Choose", 1, 1, choices, null, Choice::label));
            JsonObject query = harness.awaitMessage("query", "kind", "genericChoice", 1);
            String requestId = query.get("requestId").getAsString();
            String firstId = query.getAsJsonArray("options").get(0).getAsJsonObject()
                    .get("optionId").getAsString();

            harness.reply(requestId, List.of());
            harness.awaitMessage("error", "code", "invalidGenericChoice", 1);
            Assert.assertFalse(result.isDone());

            harness.reply(requestId, List.of(firstId, firstId));
            harness.awaitMessage("error", "code", "invalidGenericChoice", 2);
            Assert.assertFalse(result.isDone());

            harness.reply(requestId, List.of("not-offered"));
            harness.awaitMessage("error", "code", "invalidGenericChoice", 3);
            Assert.assertFalse(result.isDone());

            harness.reply(requestId, List.of(firstId));
            Assert.assertEquals(result.get(5, TimeUnit.SECONDS), List.of(choices.get(0)));

            harness.reply(requestId, List.of(firstId));
            harness.awaitMessage("error", "code", "staleRequestId", 1);
        }
    }

    @Test(timeOut = 10000)
    public void testNetworkRevealSentinelEmitsInformationWithoutQuery() throws Exception {
        try (Harness harness = new Harness(5000)) {
            List<Choice> revealed = List.of(new Choice("Mountain", 1));
            Assert.assertEquals(harness.game.getChoices(
                    "Revealed card", -1, -1, revealed, null, Choice::label), revealed);

            JsonObject message = harness.awaitMessage("reveal", "message", "Revealed card", 1);
            Assert.assertEquals(message.getAsJsonArray("items").size(), 1);
            Assert.assertEquals(message.getAsJsonArray("items").get(0).getAsJsonObject()
                    .get("label").getAsString(), "Choice[label=Mountain, identity=1]");
            Assert.assertFalse(harness.output().contains("\"kind\":\"genericChoice\""));
        }
    }

    @Test(timeOut = 10000)
    public void testRequiredChoiceTimeoutFailsClearlyInsteadOfReturningEmpty() throws Exception {
        try (Harness harness = new Harness(75)) {
            Assert.expectThrows(IllegalStateException.class, () -> harness.game.getChoices(
                    "Choose", 1, 1,
                    List.of(new Choice("Mountain", 1), new Choice("Shock", 2)),
                    null, Choice::label));
            harness.awaitMessage("error", "code", "queryTimeout", 1);
            harness.awaitMessage("lifecycle", "event", "requiredDecisionFailed", 1);
        }
    }

    private record Choice(String label, int identity) { }

    private static final class Harness implements AutoCloseable {
        private final PipedInputStream bridgeInput = new PipedInputStream();
        private final PipedOutputStream clientCommands = new PipedOutputStream(bridgeInput);
        private final ByteArrayOutputStream bridgeOutput = new ByteArrayOutputStream();
        private final PrintStream bridgePrintStream = new PrintStream(
                bridgeOutput, true, StandardCharsets.UTF_8);
        private final JsonLineTransport transport = new JsonLineTransport(bridgeInput, bridgePrintStream);
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
                List<JsonObject> matches = output().lines().filter(line -> !line.isBlank())
                        .map(line -> JsonParser.parseString(line).getAsJsonObject())
                        .filter(message -> type.equals(string(message, "type"))
                                && expected.equals(string(message, field))).toList();
                if (matches.size() >= occurrence) {
                    return matches.get(occurrence - 1);
                }
                Thread.sleep(20);
            }
            throw new AssertionError("Timed out waiting for " + type + "/" + expected
                    + " occurrence " + occurrence + ": " + output());
        }

        private void reply(String requestId, List<String> selectedIds) throws Exception {
            JsonObject reply = new JsonObject();
            reply.addProperty("schemaVersion", 1);
            reply.addProperty("type", "reply");
            reply.addProperty("requestId", requestId);
            JsonArray ids = new JsonArray();
            selectedIds.forEach(ids::add);
            reply.add("selectedOptionIds", ids);
            clientCommands.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            clientCommands.flush();
        }

        private String output() { return bridgeOutput.toString(StandardCharsets.UTF_8); }

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
