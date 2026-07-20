package forge.bridge;

import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.interfaces.ILobbyListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BridgeSession implements AutoCloseable, BridgeGuiGame.Listener {
    private static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final String host;
    private final int port;
    private final String username;
    private final boolean exitAfterProof;
    private final BridgeProtocol protocol;
    private final BridgePrinter printer;
    private final BridgeGuiGame guiGame;
    private final ClientGameLobby lobby = new ClientGameLobby();
    private final CountDownLatch assignedLatch = new CountDownLatch(1);
    private final CountDownLatch stoppedLatch = new CountDownLatch(1);
    private final AtomicInteger assignedSlot = new AtomicInteger(-1);
    private final AtomicBoolean readySent = new AtomicBoolean();
    private final AtomicBoolean controllerSeen = new AtomicBoolean();
    private final AtomicBoolean stateSeen = new AtomicBoolean();
    private final AtomicBoolean interactionSeen = new AtomicBoolean();
    private final AtomicBoolean proofReported = new AtomicBoolean();
    private final AtomicBoolean unsupportedQuerySeen = new AtomicBoolean();

    private FGameClient client;

    public BridgeSession(String host, int port, String username, boolean exitAfterProof,
            BridgeGuiBase guiBase, JsonLineTransport transport) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.exitAfterProof = exitAfterProof;
        protocol = new BridgeProtocol(transport, guiBase);
        printer = new BridgePrinter(protocol);
        guiGame = new BridgeGuiGame(printer, protocol, this);
    }

    public void modelInitialized() {
        printer.lifecycle("modelInitialized", "network PaperCard deserialization is ready");
    }

    public int run() throws InterruptedException {
        client = new FGameClient(username, guiGame, host, port);
        guiGame.setClientLobby(lobby);
        client.addLobbyListener(new LobbyListener());
        protocol.start(guiGame::handleCommand, this::inputClosed);

        printer.lifecycle("connecting", "host=" + host + " port=" + port + " username=" + username);
        client.connect();
        if (!assignedLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            printer.error("connectionTimeout", "seconds=" + CONNECT_TIMEOUT_SECONDS);
            return 1;
        }
        sendReady();
        stoppedLatch.await();
        return proofComplete() && !unsupportedQuerySeen.get() ? 0 : 1;
    }

    private void sendReady() {
        if (!readySent.compareAndSet(false, true)) {
            return;
        }
        UpdateLobbyPlayerEvent ready = UpdateLobbyPlayerEvent.isReadyUpdate(true);
        lobby.applyToSlot(assignedSlot.get(), ready);
        client.send(ready);
        printer.lifecycle("ready", "slot=" + assignedSlot.get());
    }

    private boolean proofComplete() {
        return controllerSeen.get() && stateSeen.get() && interactionSeen.get();
    }

    private void checkProof() {
        if (exitAfterProof && proofComplete() && proofReported.compareAndSet(false, true)) {
            printer.lifecycle("proofComplete", "controller, state, and interaction observed");
            stoppedLatch.countDown();
        }
    }

    @Override
    public void controllerCreated() {
        controllerSeen.set(true);
        checkProof();
    }

    @Override
    public void stateObserved() {
        stateSeen.set(true);
        checkProof();
    }

    @Override
    public void interactionObserved() {
        interactionSeen.set(true);
        checkProof();
    }

    @Override
    public void unsupportedRequiredQuery() {
        unsupportedQuerySeen.set(true);
        stoppedLatch.countDown();
    }

    private void inputClosed() {
        printer.lifecycle("inputClosed", "external input reached EOF");
        stoppedLatch.countDown();
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
        protocol.close();
        stoppedLatch.countDown();
    }

    private final class LobbyListener implements ILobbyListener {
        @Override
        public void update(GameLobbyData state, int slot) {
            lobby.setData(state);
            if (slot >= 0) {
                assignedSlot.set(slot);
                lobby.setLocalPlayer(slot);
                printer.lifecycle("connected", "slot=" + slot);
                assignedLatch.countDown();
            }
        }

        @Override
        public void message(String source, String message, ChatMessage.MessageType type) {
            printer.lifecycle("message", "source=" + source + " type=" + type + " text=" + message);
        }

        @Override
        public void close() {
            printer.lifecycle("disconnected", "network channel closed");
            stoppedLatch.countDown();
        }

        @Override
        public ClientGameLobby getLobby() {
            return lobby;
        }
    }
}
