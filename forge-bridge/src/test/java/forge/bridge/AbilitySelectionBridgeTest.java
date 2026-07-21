package forge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;
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

public class AbilitySelectionBridgeTest {
    @BeforeClass
    public void initializeLocalizer() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = Files.isDirectory(current.resolve("forge-gui")) ? current : current.getParent();
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", root.resolve("forge-gui/res/languages")
                + File.separator);
    }

    @Test
    public void testSoleForgeOfferedAbilityNeedsNoHumanQuery() throws Exception {
        try (Harness harness = new Harness()) {
            SpellAbilityView offered = abilityView(1, "Mandatory triggered ability");
            Assert.assertFalse(offered.canPlay(),
                    "The regression requires a trigger-like ability that is not normally activatable");

            SpellAbilityView selected = harness.game.getAbilityToPlay(
                    new CardView(101, null, "Generic Trigger Source"), List.of(offered), null);

            Assert.assertSame(selected, offered);
            Assert.assertFalse(harness.output().contains("\"type\":\"query\""));
        }
    }

    @Test(timeOut = 10000)
    public void testMultipleForgeOfferedAbilitiesRemainCorrelatedQuery() throws Exception {
        try (Harness harness = new Harness()) {
            SpellAbilityView first = abilityView(1, "First ability");
            SpellAbilityView second = abilityView(2, "Second ability");

            Future<SpellAbilityView> result = harness.executor.submit(() ->
                    harness.game.getAbilityToPlay(
                            new CardView(101, null, "Generic Choice Source"),
                            List.of(first, second), null));

            JsonObject query = harness.awaitQuery("abilityChoice");
            harness.reply(query.get("requestId").getAsString(), second.getId());

            Assert.assertSame(result.get(5, TimeUnit.SECONDS), second);
        }
    }

    private static SpellAbilityView abilityView(int cardId, String description) {
        Card source = new Card(cardId, null);
        source.setName("Generic Source " + cardId);
        SpellAbility ability = new SpellAbility.EmptySa(ApiType.Cleanup, source);
        ability.setDescription(description);
        SpellAbilityView view = ability.getView();
        view.updateCanPlay(ability);
        return view;
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
            game = new BridgeGuiGame(new BridgePrinter(protocol), protocol, new NoOpListener());
            protocol.start(command -> { }, () -> { });
        }

        private JsonObject awaitQuery(String kind) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (String line : output().lines().toList()) {
                    if (!line.isBlank()) {
                        JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                        if ("query".equals(string(message, "type"))
                                && kind.equals(string(message, "kind"))) {
                            return message;
                        }
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
