package forge.net;

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
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class StandaloneBridgeIntegrationTest {
    @BeforeClass
    public static void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(timeOut = 90000, description = "Standalone forge-bridge process receives state and interaction callbacks")
    public void testStandaloneBridgeProcess() throws Exception {
        FServerManager server = FServerManager.getInstance();
        ServerGameLobby lobby = null;
        Process bridge = null;
        Thread outputReader = null;
        Thread diagnosticReader = null;
        StringBuilder output = new StringBuilder();
        StringBuilder diagnostics = new StringBuilder();

        try {
            int port = PortAllocator.allocatePort();
            server.startServer(port);

            lobby = new ServerGameLobby();
            server.setLobby(lobby);
            server.setLobbyListener(new LoggingLobbyListener());
            configureLobby(lobby);

            Path root = repositoryRoot();
            Path bridgeJar = findBridgeJar(root);
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            bridge = new ProcessBuilder(
                    java.toString(),
                    "-jar", bridgeJar.toString(),
                    "--host", "localhost",
                    "--port", Integer.toString(port),
                    "--username", "Bob (Standalone Bridge)",
                    "--assets-dir", root.resolve("forge-gui").toString(),
                    "--exit-after-proof")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();

            Process launchedBridge = bridge;
            outputReader = new Thread(() -> readOutput(launchedBridge.getInputStream(), output),
                    "BridgeProcess-Output");
            outputReader.setDaemon(true);
            outputReader.start();
            diagnosticReader = new Thread(() -> readOutput(launchedBridge.getErrorStream(), diagnostics),
                    "BridgeProcess-Diagnostics");
            diagnosticReader.setDaemon(true);
            diagnosticReader.start();

            ServerGameLobby activeLobby = lobby;
            waitFor(() -> activeLobby.getSlot(1).getType() == LobbySlotType.REMOTE
                            && activeLobby.getSlot(1).isReady()
                            && server.findClientByIndex(1) != null,
                    30000, "standalone bridge to connect and become ready", output);

            Runnable startGame = lobby.startGame();
            Assert.assertNotNull(startGame, "Host lobby should be ready to start the bridge game");
            startGame.run();

            Assert.assertTrue(bridge.waitFor(45, TimeUnit.SECONDS),
                    "Bridge process did not complete its observation proof. Output:\n" + output);
            outputReader.join(5000);

            String captured = output.toString();
            List<JsonObject> messages = parseJsonLines(captured);
            Assert.assertEquals(bridge.exitValue(), 0,
                    "Bridge process failed. JSONL:\n" + captured + "\nDiagnostics:\n" + diagnostics);
            Assert.assertTrue(hasType(messages, "controller"), captured);
            Assert.assertTrue(captured.contains("\"implementation\":\"forge.gamemodes.net.client.NetGameController\""), captured);
            Assert.assertTrue(hasType(messages, "state"), captured);
            Assert.assertTrue(captured.contains("\"source\":\"full\"")
                    || captured.contains("\"source\":\"delta\""), captured);
            Assert.assertTrue(hasType(messages, "interaction"), captured);
            Assert.assertTrue(captured.contains("\"event\":\"proofComplete\""), captured);
            assertOpponentHandsHidden(messages, captured);
        } finally {
            try {
                server.clearPlayerGuis();
            } catch (Exception ignored) {
            }
            if (bridge != null && bridge.isAlive()) {
                bridge.destroy();
                if (!bridge.waitFor(5, TimeUnit.SECONDS)) {
                    bridge.destroyForcibly();
                    bridge.waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (outputReader != null) {
                outputReader.join(1000);
            }
            if (diagnosticReader != null) {
                diagnosticReader.join(1000);
            }
            if (server.isHosting()) {
                server.stopServer();
            }
            HeadlessGuiDesktop.clearLastMatch();
        }
    }

    private static void configureLobby(ServerGameLobby lobby) {
        Deck hostDeck = TestDeckLoader.createMinimalDeck("Mountain", 10);
        Deck bridgeDeck = TestDeckLoader.createMinimalDeck("Forest", 10);

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
        Path target = root.resolve("forge-bridge").resolve("target");
        try (var files = Files.list(target)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith("-jar-with-dependencies.jar"))
                    .max(Comparator.comparingLong(StandaloneBridgeIntegrationTest::lastModified))
                    .orElseThrow(() -> new IllegalStateException(
                            "Standalone bridge JAR not found in " + target + "; package forge-bridge first"));
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void readOutput(InputStream stream, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append(System.lineSeparator());
                }
            }
        } catch (IOException e) {
            synchronized (output) {
                output.append("BRIDGE_TEST_READER_ERROR ").append(e.getMessage()).append(System.lineSeparator());
            }
        }
    }

    private static List<JsonObject> parseJsonLines(String output) {
        return output.lines().filter(line -> !line.isBlank()).map(line -> {
            JsonElement parsed = JsonParser.parseString(line);
            Assert.assertTrue(parsed.isJsonObject(), "Protocol line is not a JSON object: " + line);
            return parsed.getAsJsonObject();
        }).toList();
    }

    private static boolean hasType(List<JsonObject> messages, String type) {
        return messages.stream().anyMatch(message -> message.has("type")
                && type.equals(message.get("type").getAsString()));
    }

    private static void assertOpponentHandsHidden(List<JsonObject> messages, String output) {
        boolean aliceHandObserved = false;
        for (JsonObject message : messages) {
            if (!message.has("type") || !"state".equals(message.get("type").getAsString())) {
                continue;
            }
            for (JsonElement element : message.getAsJsonArray("players")) {
                JsonObject player = element.getAsJsonObject();
                if ("Alice (Host AI)".equals(player.get("name").getAsString())) {
                    aliceHandObserved = true;
                    Assert.assertEquals(player.getAsJsonArray("handVisible").size(), 0,
                            "Opponent hidden hand identity leaked in bridge output:\n" + output);
                }
            }
        }
        Assert.assertTrue(aliceHandObserved, "No Alice hand snapshot was observed:\n" + output);
    }

    private static void waitFor(Condition condition, long timeoutMs, String description,
            StringBuilder output) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            Thread.sleep(100);
        }
        Assert.fail("Timed out waiting for " + description + ". Output:\n" + output);
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
