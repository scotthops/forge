package forge.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.deck.Deck;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class JsonBridgeIntegrationTest {
    @BeforeClass
    public static void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(timeOut = 150000,
            description = "External JSONL process casts targeted spell through standalone bridge")
    public void testExternalJsonDriverCastsLightningBolt() throws Exception {
        FServerManager server = FServerManager.getInstance();
        ServerGameLobby lobby = null;
        Process bridge = null;
        Process driver = null;
        List<Thread> threads = new ArrayList<>();
        StringBuilder bridgeJson = new StringBuilder();
        StringBuilder driverJson = new StringBuilder();
        StringBuilder bridgeDiagnostics = new StringBuilder();
        StringBuilder driverDiagnostics = new StringBuilder();
        StringBuilder pipeErrors = new StringBuilder();
        String oldShowActionable = FModel.getPreferences().getPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS);
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);

        try {
            int port = PortAllocator.allocatePort();
            server.startServer(port);
            lobby = new ServerGameLobby();
            server.setLobby(lobby);
            server.setLobbyListener(new LoggingLobbyListener());
            configureLobby(lobby);

            Path root = repositoryRoot();
            Path java = javaExecutable();
            bridge = new ProcessBuilder(
                    java.toString(),
                    "-jar", findBridgeJar(root).toString(),
                    "--host", "localhost",
                    "--port", Integer.toString(port),
                    "--username", "Bob (JSON Driver)",
                    "--assets-dir", root.resolve("forge-gui").toString())
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();

            String driverClasspath = root.resolve("forge-gui-desktop/target/test-classes")
                    + File.pathSeparator + gsonLocation();
            driver = new ProcessBuilder(
                    java.toString(), "-cp", driverClasspath, ExternalJsonDriverMain.class.getName())
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();

            threads.add(startJsonPipe("Bridge-To-Driver", bridge.getInputStream(), driver.getOutputStream(),
                    bridgeJson, pipeErrors));
            threads.add(startJsonPipe("Driver-To-Bridge", driver.getInputStream(), bridge.getOutputStream(),
                    driverJson, pipeErrors));
            threads.add(startReader("Bridge-Diagnostics", bridge.getErrorStream(), bridgeDiagnostics));
            threads.add(startReader("Driver-Diagnostics", driver.getErrorStream(), driverDiagnostics));

            ServerGameLobby activeLobby = lobby;
            waitFor(() -> activeLobby.getSlot(1).getType() == LobbySlotType.REMOTE
                            && activeLobby.getSlot(1).isReady()
                            && server.findClientByIndex(1) != null,
                    30000, "JSON bridge to connect and become ready", bridgeJson, bridgeDiagnostics);

            Runnable startGame = lobby.startGame();
            Assert.assertNotNull(startGame, "Host lobby should be ready to start the JSON bridge game");
            startGame.run();

            Assert.assertTrue(driver.waitFor(100, TimeUnit.SECONDS), failure(
                    "External driver did not complete", bridgeJson, driverJson,
                    bridgeDiagnostics, driverDiagnostics, pipeErrors));
            Assert.assertEquals(driver.exitValue(), 0, failure(
                    "External driver failed", bridgeJson, driverJson,
                    bridgeDiagnostics, driverDiagnostics, pipeErrors));
            Assert.assertTrue(bridge.waitFor(10, TimeUnit.SECONDS), failure(
                    "Bridge did not stop after driver EOF", bridgeJson, driverJson,
                    bridgeDiagnostics, driverDiagnostics, pipeErrors));
            threads.forEach(thread -> join(thread, 5000));

            Assert.assertEquals(bridge.exitValue(), 0, failure(
                    "Bridge process failed", bridgeJson, driverJson,
                    bridgeDiagnostics, driverDiagnostics, pipeErrors));
            Assert.assertEquals(pipeErrors.length(), 0, pipeErrors.toString());

            List<JsonObject> outputMessages = parseJsonLines(bridgeJson.toString());
            List<JsonObject> commands = parseJsonLines(driverJson.toString());
            assertProtocolProof(outputMessages, commands, driverDiagnostics.toString());
        } finally {
            FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, oldShowActionable);
            try {
                server.clearPlayerGuis();
            } catch (Exception ignored) {
            }
            destroy(driver);
            destroy(bridge);
            threads.forEach(thread -> join(thread, 1000));
            if (server.isHosting()) {
                server.stopServer();
            }
            HeadlessGuiDesktop.clearLastMatch();
        }
    }

    private static void configureLobby(ServerGameLobby lobby) {
        var cardDb = FModel.getMagicDb().getCommonCards();
        var lightningBolt = cardDb.getCard("Lightning Bolt");
        var llanowarElves = cardDb.getCard("Llanowar Elves");
        Assert.assertNotNull(lightningBolt);
        Assert.assertNotNull(llanowarElves);

        Deck hostDeck = TestDeckLoader.createMinimalDeck("Forest", 4);
        Deck bridgeDeck = TestDeckLoader.createMinimalDeck("Mountain", 7);
        for (int i = 0; i < 4; i++) {
            hostDeck.getMain().add(llanowarElves);
        }
        bridgeDeck.getMain().add(lightningBolt);

        LobbySlot host = lobby.getSlot(0);
        host.setType(LobbySlotType.AI);
        host.setName("Alice (Host AI)");
        host.setDeck(hostDeck);
        host.setIsReady(true);

        LobbySlot remote = lobby.getSlot(1);
        remote.setType(LobbySlotType.OPEN);
        remote.setDeck(bridgeDeck);
        remote.setIsReady(false);
    }

    private static void assertProtocolProof(List<JsonObject> output, List<JsonObject> commands,
            String driverDiagnostics) {
        Assert.assertTrue(output.stream().allMatch(message -> integer(message, "schemaVersion", -1) == 1),
                "Every bridge output must use schemaVersion 1");
        Assert.assertEquals(commands.stream().filter(message -> !message.has("schemaVersion")).count(), 1,
                "Driver should send exactly one missing-version probe");
        Assert.assertEquals(commands.stream()
                .filter(message -> integer(message, "schemaVersion", -1) == 999).count(), 1,
                "Driver should send exactly one unsupported-version probe");
        Assert.assertTrue(commands.stream()
                .filter(message -> message.has("schemaVersion"))
                .allMatch(message -> integer(message, "schemaVersion", -1) == 1
                        || integer(message, "schemaVersion", -1) == 999),
                "Driver emitted an unexpected schema version");
        Assert.assertTrue(hasType(output, "controller"), "No controller message");
        Assert.assertTrue(hasType(output, "state"), "No state message");
        Assert.assertTrue(hasType(output, "interaction"), "No interaction message");
        Assert.assertTrue(output.stream().filter(message -> "state".equals(type(message)))
                .allMatch(message -> message.has("stateSequence")), "State is missing stateSequence");
        assertInteractionSequencesIncrease(output);
        assertExpectedHardeningErrors(output);

        int mountainId = findCardId(output, "Bob (JSON Driver)", "handVisible", "Mountain");
        int boltId = findCardId(output, "Bob (JSON Driver)", "handVisible", "Lightning Bolt");
        int elvesId = findCardId(output, "Alice (Host AI)", "battlefield", "Llanowar Elves");
        Assert.assertTrue(mountainId >= 0, "Mountain was never visible in Bob's hand");
        Assert.assertTrue(boltId >= 0, "Lightning Bolt was never visible in Bob's hand");
        Assert.assertTrue(elvesId >= 0, "Llanowar Elves was never on Alice's battlefield");
        assertStaleCardLeftStateUnchanged(output, mountainId);
        Assert.assertTrue(hasSelection(commands, boltId), "Driver did not explicitly select Lightning Bolt");
        Assert.assertTrue(hasSelection(commands, elvesId), "Driver did not explicitly select Llanowar Elves");
        Assert.assertTrue(commands.stream().anyMatch(message -> "passPriority".equals(type(message))),
                "Driver did not pass priority");
        Assert.assertEquals(output.stream()
                        .filter(message -> "actionAccepted".equals(type(message)))
                        .filter(message -> "selectCard".equals(string(message, "action")))
                        .filter(message -> integer(message, "selectedId", -1) == mountainId)
                        .count(),
                2, "Only the valid land-play and mana-payment Mountain actions may reach Forge");

        JsonObject query = output.stream()
                .filter(message -> "query".equals(type(message)))
                .filter(message -> "abilityChoice".equals(string(message, "kind")))
                .filter(message -> "Lightning Bolt".equals(string(message, "hostCardName")))
                .findFirst().orElseThrow(() -> new AssertionError("No synchronous abilityChoice query"));
        String requestId = string(query, "requestId");
        Assert.assertTrue(commands.stream().anyMatch(message -> "reply".equals(type(message))
                        && requestId.equals(string(message, "requestId"))),
                "No correlated abilityChoice reply for " + requestId);

        Assert.assertTrue(sawStackTarget(output, boltId, elvesId),
                "Authoritative state never exposed Bolt targeting Llanowar Elves on the stack");
        Assert.assertTrue(sawFinalGraveyard(output, elvesId),
                "Authoritative state never moved Llanowar Elves to Alice's graveyard");
        Assert.assertTrue(driverDiagnostics.contains("DRIVER_SUCCESS")
                        && driverDiagnostics.contains("abilityReply=true"),
                driverDiagnostics);
    }

    private static void assertExpectedHardeningErrors(List<JsonObject> output) {
        List<JsonObject> errors = output.stream()
                .filter(message -> "error".equals(type(message))).toList();
        Assert.assertTrue(errors.stream().allMatch(message -> {
            String code = string(message, "code");
            return "MISSING_SCHEMA_VERSION".equals(code)
                    || "UNSUPPORTED_SCHEMA_VERSION".equals(code)
                    || "STALE_INTERACTION".equals(code);
        }), "Unexpected bridge errors: " + errors);
        Assert.assertEquals(errorCount(errors, "MISSING_SCHEMA_VERSION"), 1, errors.toString());
        Assert.assertEquals(errorCount(errors, "UNSUPPORTED_SCHEMA_VERSION"), 1, errors.toString());
        Assert.assertTrue(errorCount(errors, "STALE_INTERACTION") >= 1, errors.toString());
        JsonObject stale = errors.stream()
                .filter(message -> "STALE_INTERACTION".equals(string(message, "code")))
                .findFirst().orElseThrow();
        Assert.assertTrue(stale.has("receivedInteractionSequence"), stale.toString());
        Assert.assertTrue(stale.has("currentInteractionSequence"), stale.toString());
    }

    private static long errorCount(List<JsonObject> errors, String code) {
        return errors.stream().filter(message -> code.equals(string(message, "code"))).count();
    }

    private static void assertInteractionSequencesIncrease(List<JsonObject> output) {
        long previous = 0;
        for (JsonObject message : output) {
            if (!"interaction".equals(type(message))) {
                continue;
            }
            long current = message.get("interactionSequence").getAsLong();
            Assert.assertTrue(current > previous,
                    "interactionSequence must strictly increase: " + previous + " then " + current);
            previous = current;
        }
        Assert.assertTrue(previous > 0, "No interaction sequence was observed");
    }

    private static int findCardId(List<JsonObject> messages, String playerName, String zone, String cardName) {
        for (JsonObject message : messages) {
            if (!"state".equals(type(message))) {
                continue;
            }
            for (JsonElement playerElement : message.getAsJsonArray("players")) {
                JsonObject player = playerElement.getAsJsonObject();
                if (!playerName.equals(string(player, "name"))) {
                    continue;
                }
                for (JsonElement cardElement : player.getAsJsonArray(zone)) {
                    JsonObject card = cardElement.getAsJsonObject();
                    if (cardName.equals(string(card, "name"))) {
                        return card.get("id").getAsInt();
                    }
                }
            }
        }
        return -1;
    }

    private static boolean hasSelection(List<JsonObject> messages, int cardId) {
        return messages.stream().anyMatch(message -> "selectCard".equals(type(message))
                && message.has("cardId") && message.get("cardId").getAsInt() == cardId);
    }

    private static void assertStaleCardLeftStateUnchanged(List<JsonObject> output, int mountainId) {
        int unsupportedIndex = -1;
        int staleIndex = -1;
        JsonObject precedingState = null;
        for (int i = 0; i < output.size(); i++) {
            JsonObject message = output.get(i);
            if ("state".equals(type(message))) {
                precedingState = message;
            } else if ("UNSUPPORTED_SCHEMA_VERSION".equals(string(message, "code"))) {
                unsupportedIndex = i;
            } else if (unsupportedIndex >= 0 && "STALE_INTERACTION".equals(string(message, "code"))) {
                staleIndex = i;
                break;
            }
        }
        Assert.assertTrue(staleIndex > unsupportedIndex, "Deliberate stale-card rejection was not observed");
        Assert.assertNotNull(precedingState, "No authoritative state preceded stale-card rejection");
        JsonObject bob = playerNamed(precedingState, "Bob (JSON Driver)");
        Assert.assertNotNull(bob, "Bob was absent from the state preceding stale-card rejection");
        Assert.assertTrue(containsCard(bob.getAsJsonArray("handVisible"), mountainId),
                "Rejected Mountain action changed the latest authoritative hand state");
        Assert.assertFalse(containsCard(bob.getAsJsonArray("battlefield"), mountainId),
                "Rejected Mountain action changed the latest authoritative battlefield state");
        Assert.assertTrue(output.subList(staleIndex + 1, output.size()).stream()
                        .anyMatch(message -> "actionAccepted".equals(type(message))
                                && "selectCard".equals(string(message, "action"))
                                && integer(message, "selectedId", -1) == mountainId),
                "No later current-sequence Mountain action was accepted");
    }

    private static JsonObject playerNamed(JsonObject state, String name) {
        for (JsonElement playerElement : state.getAsJsonArray("players")) {
            JsonObject player = playerElement.getAsJsonObject();
            if (name.equals(string(player, "name"))) {
                return player;
            }
        }
        return null;
    }

    private static boolean sawStackTarget(List<JsonObject> messages, int boltId, int elvesId) {
        for (JsonObject message : messages) {
            if (!"state".equals(type(message))) {
                continue;
            }
            for (JsonElement stackElement : message.getAsJsonArray("stack")) {
                JsonObject item = stackElement.getAsJsonObject();
                JsonObject source = item.getAsJsonObject("source");
                if (source == null || source.get("id").getAsInt() != boltId) {
                    continue;
                }
                for (JsonElement target : item.getAsJsonArray("targets")) {
                    if (target.getAsJsonObject().get("id").getAsInt() == elvesId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean sawFinalGraveyard(List<JsonObject> messages, int elvesId) {
        for (JsonObject message : messages) {
            if (!"state".equals(type(message))) {
                continue;
            }
            for (JsonElement playerElement : message.getAsJsonArray("players")) {
                JsonObject player = playerElement.getAsJsonObject();
                if (!"Alice (Host AI)".equals(string(player, "name"))) {
                    continue;
                }
                boolean battlefield = containsCard(player.getAsJsonArray("battlefield"), elvesId);
                boolean graveyard = containsCard(player.getAsJsonArray("graveyard"), elvesId);
                if (!battlefield && graveyard) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsCard(Iterable<JsonElement> cards, int id) {
        for (JsonElement element : cards) {
            if (element.getAsJsonObject().get("id").getAsInt() == id) {
                return true;
            }
        }
        return false;
    }

    private static Thread startJsonPipe(String name, InputStream source, OutputStream destination,
            StringBuilder transcript, StringBuilder errors) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(destination, true, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonElement parsed = JsonParser.parseString(line);
                    if (!parsed.isJsonObject()) {
                        append(errors, name + " non-object line: " + line + System.lineSeparator());
                    }
                    append(transcript, line + System.lineSeparator());
                    writer.println(line);
                }
            } catch (Exception e) {
                append(errors, name + " failed: " + e.getMessage() + System.lineSeparator());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Thread startReader(String name, InputStream source, StringBuilder output) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(output, line + System.lineSeparator());
                }
            } catch (IOException e) {
                append(output, name + " reader failed: " + e.getMessage() + System.lineSeparator());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static List<JsonObject> parseJsonLines(String text) {
        return text.lines().filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static boolean hasType(List<JsonObject> messages, String expected) {
        return messages.stream().anyMatch(message -> expected.equals(type(message)));
    }

    private static String type(JsonObject message) {
        return string(message, "type");
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(JsonObject object, String field, int fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static synchronized void append(StringBuilder target, String value) {
        target.append(value);
    }

    private static void waitFor(Condition condition, long timeoutMs, String description,
            StringBuilder protocol, StringBuilder diagnostics) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            Thread.sleep(100);
        }
        Assert.fail("Timed out waiting for " + description + "\nJSONL:\n" + protocol
                + "\nDiagnostics:\n" + diagnostics);
    }

    private static String failure(String message, StringBuilder bridgeJson, StringBuilder driverJson,
            StringBuilder bridgeDiagnostics, StringBuilder driverDiagnostics, StringBuilder pipeErrors) {
        return message + "\nBridge JSONL:\n" + bridgeJson + "\nDriver JSONL:\n" + driverJson
                + "\nBridge diagnostics:\n" + bridgeDiagnostics + "\nDriver diagnostics:\n"
                + driverDiagnostics + "\nPipe errors:\n" + pipeErrors;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("forge-bridge"))) {
            return current;
        }
        if (current.getParent() != null && Files.isDirectory(current.getParent().resolve("forge-bridge"))) {
            return current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + current);
    }

    private static Path findBridgeJar(Path root) throws IOException {
        Path target = root.resolve("forge-bridge/target");
        try (var files = Files.list(target)) {
            return files.filter(path -> path.getFileName().toString().endsWith("-jar-with-dependencies.jar"))
                    .max(Comparator.comparingLong(JsonBridgeIntegrationTest::lastModified))
                    .orElseThrow(() -> new IllegalStateException("Package forge-bridge before running this test"));
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }

    private static Path gsonLocation() {
        try {
            return Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate Gson for external driver", e);
        }
    }

    private static void destroy(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread, long timeoutMs) {
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }

    private static final class LoggingLobbyListener implements ILobbyListener {
        @Override public void message(String source, String message, ChatMessage.MessageType type) { }
        @Override public void update(GameLobbyData state, int slot) { }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }
}
