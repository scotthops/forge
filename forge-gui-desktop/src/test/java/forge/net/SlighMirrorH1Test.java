package forge.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.deck.Deck;
import forge.game.GameView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.model.FModel;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SlighMirrorH1Test {
    @BeforeClass
    public static void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test
    public void testExactDeckRecipeForBothPlayers() {
        Deck first = SlighMirrorDeck.create("First Sligh");
        Deck second = SlighMirrorDeck.create("Second Sligh");

        assertExactDeck(first);
        assertExactDeck(second);
    }

    @Test(timeOut = 90000, dependsOnMethods = "testExactDeckRecipeForBothPlayers",
            description = "Standalone bridge receives the real Sligh opening hand and mulligan controls")
    public void testStandaloneBridgeReceivesOpeningHand() throws Exception {
        FServerManager server = FServerManager.getInstance();
        Process bridge = null;
        Thread outputReader = null;
        Thread diagnosticReader = null;
        StringBuilder output = new StringBuilder();
        StringBuilder diagnostics = new StringBuilder();

        try {
            int port = PortAllocator.allocatePort();
            server.startServer(port);
            ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);
            server.setLobbyListener(new NoOpLobbyListener());
            configureLobby(lobby);

            Path root = repositoryRoot();
            bridge = new ProcessBuilder(
                    javaExecutable().toString(), "-jar", findBridgeJar(root).toString(),
                    "--host", "localhost", "--port", Integer.toString(port),
                    "--username", "H1 Godot Bridge", "--assets-dir", root.resolve("forge-gui").toString(),
                    "--exit-after-proof")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start();

            Process activeBridge = bridge;
            outputReader = startReader(activeBridge.getInputStream(), output, "H1-Bridge-Output");
            diagnosticReader = startReader(activeBridge.getErrorStream(), diagnostics, "H1-Bridge-Diagnostics");

            waitFor(() -> lobby.getSlot(1).getType() == LobbySlotType.REMOTE
                            && lobby.getSlot(1).isReady() && server.findClientByIndex(1) != null,
                    30000, "standalone bridge connection", output);
            Runnable startGame = lobby.startGame();
            Assert.assertNotNull(startGame);
            startGame.run();

            Assert.assertTrue(bridge.waitFor(45, TimeUnit.SECONDS), output.toString());
            outputReader.join(5000);
            List<JsonObject> messages = parseJsonLines(output.toString());
            Assert.assertEquals(bridge.exitValue(), 0,
                    output + System.lineSeparator() + diagnostics);

            JsonObject localPlayer = findLocalPlayerWithOpeningHand(messages, "H1 Godot Bridge");
            Assert.assertNotNull(localPlayer, output.toString());
            Assert.assertEquals(localPlayer.getAsJsonArray("handVisible").size(), 7);
            Assert.assertTrue(sawKeepMulliganButtons(messages), output.toString());
            Assert.assertTrue(messages.stream().anyMatch(message -> "proofComplete".equals(
                    string(message, "event"))), output.toString());
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

    @Test(timeOut = 120000,
            description = "Normally shuffled Sligh mirror reaches an ordinary turn with a remote human")
    public void testShuffledRemoteHumanOpeningAndOrdinaryTurn() {
        OpeningTurnProbe probe = new OpeningTurnProbe();
        UnifiedNetworkHarness.GameResult result = new UnifiedNetworkHarness()
                .playerCount(2)
                .remoteClients(1)
                .useAiForRemotePlayers(false)
                .interactionProbe(probe)
                .stopWhenProbeSatisfied(true)
                .decks(SlighMirrorDeck.create("AI Sligh"), SlighMirrorDeck.create("Remote Sligh"))
                .gameTimeout(120000)
                .execute();

        Assert.assertTrue(result.gameStarted, result.toSummary());
        Assert.assertTrue(result.deltaPacketsReceived > 0, result.toSummary());
        Assert.assertTrue(probe.sawOpeningHand(), probe.getLog());
        Assert.assertEquals(probe.openingHandSize(), 7, probe.getLog());
        Assert.assertTrue(probe.sawKeepPrompt(), probe.getLog());
        Assert.assertTrue(probe.sawOrdinaryTurn(), probe.getLog());
    }

    private static void assertExactDeck(Deck deck) {
        Assert.assertEquals(deck.getMain().countAll(), SlighMirrorDeck.CARD_COUNT);
        for (Map.Entry<String, Integer> entry : SlighMirrorDeck.cardCounts().entrySet()) {
            Assert.assertNotNull(FModel.getMagicDb().getCommonCards().getCard(entry.getKey()),
                    entry.getKey());
            Assert.assertEquals(deck.getMain().countByName(entry.getKey()),
                    entry.getValue().intValue(), entry.getKey());
        }
        Assert.assertEquals(SlighMirrorDeck.cardCounts().values().stream()
                .mapToInt(Integer::intValue).sum(), SlighMirrorDeck.CARD_COUNT);
    }

    private static void configureLobby(ServerGameLobby lobby) {
        LobbySlot ai = lobby.getSlot(0);
        ai.setType(LobbySlotType.AI);
        ai.setName("H1 Sligh AI");
        ai.setDeck(SlighMirrorDeck.create("H1 AI Sligh"));
        ai.setIsReady(true);

        LobbySlot remote = lobby.getSlot(1);
        remote.setType(LobbySlotType.OPEN);
        remote.setDeck(SlighMirrorDeck.create("H1 Godot Sligh"));
        remote.setIsReady(false);
    }

    private static JsonObject findLocalPlayerWithOpeningHand(List<JsonObject> messages, String name) {
        for (JsonObject message : messages) {
            if (!"state".equals(string(message, "type"))) {
                continue;
            }
            for (JsonElement element : message.getAsJsonArray("players")) {
                JsonObject player = element.getAsJsonObject();
                if (name.equals(string(player, "name"))
                        && player.getAsJsonArray("handVisible").size() == 7) {
                    return player;
                }
            }
        }
        return null;
    }

    private static boolean sawKeepMulliganButtons(List<JsonObject> messages) {
        for (JsonObject message : messages) {
            if (!"interaction".equals(string(message, "type"))) {
                continue;
            }
            JsonObject buttons = message.getAsJsonObject("buttons");
            if (buttons != null && "Keep".equals(string(buttons, "okLabel"))
                    && "Mulligan".equals(string(buttons, "cancelLabel"))) {
                return true;
            }
        }
        return false;
    }

    private static List<JsonObject> parseJsonLines(String value) {
        List<JsonObject> messages = new ArrayList<>();
        value.lines().filter(line -> !line.isBlank())
                .forEach(line -> messages.add(JsonParser.parseString(line).getAsJsonObject()));
        return messages;
    }

    private static Thread startReader(InputStream stream, StringBuilder output, String name) {
        Thread thread = new Thread(() -> {
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
                    output.append("H1_READER_ERROR ").append(e.getMessage()).append(System.lineSeparator());
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
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
        try (var files = Files.list(root.resolve("forge-bridge/target"))) {
            return files.filter(path -> path.getFileName().toString()
                            .endsWith("-jar-with-dependencies.jar"))
                    .max(Comparator.comparingLong(SlighMirrorH1Test::lastModified))
                    .orElseThrow(() -> new IllegalStateException("Package forge-bridge before this test"));
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

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
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
        Assert.fail("Timed out waiting for " + description + System.lineSeparator() + output);
    }

    private static final class OpeningTurnProbe extends NetworkInteractionProbe {
        private volatile int openingHandSize = -1;
        private volatile boolean keepPrompt;
        private volatile boolean ordinaryTurn;

        @Override
        public void onGameState(String source, GameView gameView, Collection<PlayerView> localPlayers) {
            super.onGameState(source, gameView, localPlayers);
            if (gameView == null) {
                return;
            }
            for (PlayerView player : localPlayers) {
                if (openingHandSize < 0 && player.getHand() != null && player.getHand().size() == 7) {
                    openingHandSize = player.getHand().size();
                }
            }
            PhaseType phase = gameView.getPhase();
            ordinaryTurn |= gameView.getTurn() >= 1 && phase != null
                    && phase != PhaseType.UNTAP && phase != PhaseType.UPKEEP;
        }

        @Override
        public void onButtons(PlayerView owner, String label1, String label2,
                boolean enable1, boolean enable2, boolean focus1) {
            super.onButtons(owner, label1, label2, enable1, enable2, focus1);
            keepPrompt |= "Keep".equals(label1) && "Mulligan".equals(label2)
                    && enable1 && enable2;
        }

        @Override
        public boolean isStopConditionSatisfied() {
            return sawOpeningHand() && keepPrompt && ordinaryTurn;
        }

        private boolean sawOpeningHand() {
            return openingHandSize == 7;
        }

        private int openingHandSize() {
            return openingHandSize;
        }

        private boolean sawKeepPrompt() {
            return keepPrompt;
        }

        private boolean sawOrdinaryTurn() {
            return ordinaryTurn;
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }

    private static final class NoOpLobbyListener implements ILobbyListener {
        @Override public void message(String source, String message, ChatMessage.MessageType type) { }
        @Override public void update(GameLobbyData state, int slot) { }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }
}
