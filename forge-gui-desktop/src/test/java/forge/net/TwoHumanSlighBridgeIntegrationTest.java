package forge.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Proves two independent bridge processes can join one Sligh game without sharing hands. */
public class TwoHumanSlighBridgeIntegrationTest {
    private static final String FIRST_NAME = "Two-Human Seat 0";
    private static final String SECOND_NAME = "Two-Human Seat 1";

    @BeforeClass
    public static void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(timeOut = 120000)
    public void testBothRemoteBridgesReceiveSeatSpecificOpeningState() throws Exception {
        FServerManager server = FServerManager.getInstance();
        Capture first = null;
        Capture second = null;
        try {
            int port = PortAllocator.allocatePort();
            server.startServer(port);
            ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);
            ReadyLobbyListener listener = new ReadyLobbyListener(lobby, server);
            server.setLobbyListener(listener);
            GodotTwoHumanSlighHostMain.configureLobby(lobby,
                    SlighMirrorDeck.create("First Sligh"), SlighMirrorDeck.create("Second Sligh"));

            Path root = repositoryRoot();
            Path bridgeJar = root.resolve("forge-bridge/target/forge-bridge-2.0.14-SNAPSHOT-jar-with-dependencies.jar");
            Assert.assertTrue(Files.isRegularFile(bridgeJar), "Package forge-bridge before this test: " + bridgeJar);
            first = Capture.start(root, bridgeJar, port, FIRST_NAME);
            second = Capture.start(root, bridgeJar, port, SECOND_NAME);

            Assert.assertTrue(listener.awaitReady(30), "Both remote seats must become ready before timeout");
            Assert.assertTrue(bothReady(lobby, server), "Both remote seats must connect and become ready");
            Assert.assertEquals(lobby.getSlot(0).getName(), FIRST_NAME);
            Assert.assertEquals(lobby.getSlot(1).getName(), SECOND_NAME);

            Runnable startGame = lobby.startGame();
            Assert.assertNotNull(startGame, "A lobby with two remote humans and decks should start");
            startGame.run();

            JsonObject firstController = first.await(message -> typeIs(message, "controller"), 60);
            JsonObject secondController = second.await(message -> typeIs(message, "controller"), 60);
            int firstId = firstController.get("playerId").getAsInt();
            int secondId = secondController.get("playerId").getAsInt();
            Assert.assertNotEquals(firstId, secondId, "Each bridge must control a different player");

            JsonObject firstState = first.await(message -> openingState(message, firstId, secondId), 60);
            JsonObject secondState = second.await(message -> openingState(message, secondId, firstId), 60);
            assertPrivateHands(firstState, firstId, secondId);
            assertPrivateHands(secondState, secondId, firstId);
            Assert.assertTrue(first.startingChoiceSent || second.startingChoiceSent,
                    "The coin-toss winner must make the offered Play/Draw choice");
            Assert.assertTrue(first.hasLifecycleSlot(0), first.snapshot());
            Assert.assertTrue(second.hasLifecycleSlot(1), second.snapshot());
        } finally {
            close(first);
            close(second);
            server.clearPlayerGuis();
            if (server.isHosting()) {
                server.stopServer();
            }
            HeadlessGuiDesktop.clearLastMatch();
        }
    }

    private static boolean bothReady(ServerGameLobby lobby, FServerManager server) {
        return lobby.getSlot(0).getType() == LobbySlotType.REMOTE && lobby.getSlot(0).isReady()
                && lobby.getSlot(1).getType() == LobbySlotType.REMOTE && lobby.getSlot(1).isReady()
                && server.findClientByIndex(0) != null && server.findClientByIndex(1) != null
                && server.findClientByIndex(0) != server.findClientByIndex(1);
    }

    private static boolean openingState(JsonObject message, int localId, int opponentId) {
        if (!typeIs(message, "state")) {
            return false;
        }
        JsonObject local = player(message, localId);
        JsonObject opponent = player(message, opponentId);
        return local != null && opponent != null
                && local.getAsJsonArray("handVisible").size() == 7
                && opponent.get("handCount").getAsInt() == 7;
    }

    private static void assertPrivateHands(JsonObject state, int localId, int opponentId) {
        JsonObject local = player(state, localId);
        JsonObject opponent = player(state, opponentId);
        Assert.assertNotNull(local);
        Assert.assertNotNull(opponent);
        Assert.assertEquals(local.getAsJsonArray("handVisible").size(), 7);
        Assert.assertEquals(opponent.get("handCount").getAsInt(), 7);
        Assert.assertEquals(opponent.getAsJsonArray("handVisible").size(), 0,
                "Opponent hand identities must not appear in this bridge's JSONL state");
    }

    private static JsonObject player(JsonObject state, int id) {
        for (JsonElement element : state.getAsJsonArray("players")) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("id").getAsInt() == id) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean typeIs(JsonObject message, String type) {
        return message.has("type") && type.equals(message.get("type").getAsString());
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("forge-bridge"))) {
            return current;
        }
        return current.getParent();
    }

    private static void close(Capture capture) throws InterruptedException, IOException {
        if (capture == null) {
            return;
        }
        capture.process.getOutputStream().close();
        if (!capture.process.waitFor(5, TimeUnit.SECONDS)) {
            capture.process.destroy();
            if (!capture.process.waitFor(5, TimeUnit.SECONDS)) {
                capture.process.destroyForcibly();
                capture.process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class ReadyLobbyListener implements ILobbyListener {
        private final ServerGameLobby lobby;
        private final FServerManager server;
        private final CountDownLatch ready = new CountDownLatch(1);

        private ReadyLobbyListener(ServerGameLobby lobby, FServerManager server) {
            this.lobby = lobby;
            this.server = server;
        }

        private void signalIfReady() {
            if (bothReady(lobby, server)) {
                ready.countDown();
            }
        }

        private boolean awaitReady(int seconds) throws InterruptedException {
            return ready.await(seconds, TimeUnit.SECONDS);
        }

        @Override public void message(String source, String message, ChatMessage.MessageType type) {
            signalIfReady();
        }
        @Override public void update(GameLobbyData state, int slot) {
            signalIfReady();
        }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }

    private static final class Capture {
        private final Process process;
        private final List<JsonObject> messages = new ArrayList<>();
        private final StringBuilder diagnostics = new StringBuilder();
        private String readerError;
        private volatile boolean startingChoiceSent;

        private Capture(Process process) {
            this.process = process;
            Thread stdout = new Thread(() -> readProtocol(process.getInputStream()), "Bridge-Protocol-Capture");
            Thread stderr = new Thread(() -> readDiagnostics(process.getErrorStream()), "Bridge-Diagnostics-Capture");
            stdout.setDaemon(true);
            stderr.setDaemon(true);
            stdout.start();
            stderr.start();
        }

        private static Capture start(Path root, Path bridgeJar, int port, String username) throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            Process process = new ProcessBuilder(java.toString(), "-jar", bridgeJar.toString(),
                    "--host", "localhost", "--port", Integer.toString(port),
                    "--username", username, "--assets-dir", root.resolve("forge-gui").toString())
                    .directory(root.toFile()).redirectErrorStream(false).start();
            return new Capture(process);
        }

        private void readProtocol(InputStream input) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    synchronized (messages) {
                        messages.add(message);
                        messages.notifyAll();
                    }
                    answerStartingChoice(message);
                }
            } catch (Exception exception) {
                synchronized (messages) {
                    readerError = exception.toString();
                    messages.notifyAll();
                }
            }
        }

        private void answerStartingChoice(JsonObject message) throws IOException {
            if (startingChoiceSent || !typeIs(message, "interaction")
                    || !"prompt".equals(message.get("reason").getAsString())) {
                return;
            }
            JsonObject buttons = message.getAsJsonObject("buttons");
            if (buttons == null || !buttons.has("okLabel") || !buttons.has("cancelLabel")
                    || !"Play".equals(buttons.get("okLabel").getAsString())
                    || !"Draw".equals(buttons.get("cancelLabel").getAsString())
                    || !buttons.get("okEnabled").getAsBoolean()) {
                return;
            }
            startingChoiceSent = true;
            String command = "{\"schemaVersion\":1,\"type\":\"button\",\"button\":\"ok\","
                    + "\"interactionSequence\":" + message.get("interactionSequence").getAsLong() + "}\n";
            process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        private void readDiagnostics(InputStream input) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (diagnostics) {
                        diagnostics.append(line).append(System.lineSeparator());
                    }
                }
            } catch (IOException exception) {
                synchronized (diagnostics) {
                    diagnostics.append(exception).append(System.lineSeparator());
                }
            }
        }

        private JsonObject await(Predicate<JsonObject> predicate, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            synchronized (messages) {
                while (true) {
                    for (JsonObject message : messages) {
                        if (predicate.test(message)) {
                            return message;
                        }
                    }
                    if (readerError != null || !process.isAlive()) {
                        Assert.fail("Bridge stopped before expected message: " + snapshot());
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        Assert.fail("Timed out waiting for bridge message: " + snapshot());
                    }
                    TimeUnit.NANOSECONDS.timedWait(messages, remaining);
                }
            }
        }

        private boolean hasLifecycleSlot(int slot) {
            synchronized (messages) {
                return messages.stream().anyMatch(message -> typeIs(message, "lifecycle")
                        && "connected".equals(message.get("event").getAsString())
                        && ("slot=" + slot).equals(message.get("detail").getAsString()));
            }
        }

        private String snapshot() {
            synchronized (messages) {
                synchronized (diagnostics) {
                    return "messages=" + messages + " readerError=" + readerError
                            + " diagnostics=" + diagnostics;
                }
            }
        }
    }
}
