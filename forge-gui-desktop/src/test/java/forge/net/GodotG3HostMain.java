package forge.net;

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

/** Development-only deterministic Forge host for manually exercising the Godot G3 client. */
public final class GodotG3HostMain {
    private GodotG3HostMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 36743 : Integer.parseInt(args[0]);
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);

        var cardDb = FModel.getMagicDb().getCommonCards();
        var lightningBolt = cardDb.getCard("Lightning Bolt");
        var llanowarElves = cardDb.getCard("Llanowar Elves");
        if (lightningBolt == null || llanowarElves == null) {
            throw new IllegalStateException("G3 fixture requires Lightning Bolt and Llanowar Elves");
        }

        Deck aliceDeck = TestDeckLoader.createMinimalDeck("Forest", 4);
        Deck bobDeck = TestDeckLoader.createMinimalDeck("Mountain", 7);
        for (int i = 0; i < 4; i++) {
            aliceDeck.getMain().add(llanowarElves);
        }
        bobDeck.getMain().add(lightningBolt);

        FServerManager server = FServerManager.getInstance();
        try {
            server.startServer(port);
            ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);
            server.setLobbyListener(new NoOpLobbyListener());
            configureLobby(lobby, aliceDeck, bobDeck);

            LobbySlot remote = lobby.getSlot(1);
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline
                    && (remote.getType() != LobbySlotType.REMOTE || !remote.isReady())) {
                Thread.sleep(100);
            }
            if (remote.getType() != LobbySlotType.REMOTE || !remote.isReady()) {
                throw new IllegalStateException("Godot bridge did not join and become ready within 60 seconds");
            }

            Runnable startGame = lobby.startGame();
            if (startGame == null) {
                throw new IllegalStateException("G3 lobby was not ready to start");
            }
            startGame.run();
            System.out.println("G3_HOST_STARTED port=" + port);
            System.out.println("Use the Godot UI to play Mountain, cast Lightning Bolt, and pass priority.");
            if (args.length > 1) {
                Thread.sleep(Long.parseLong(args[1]) * 1_000L);
            } else {
                System.out.println("Press Enter after verification to stop the host.");
                System.in.read();
            }
        } finally {
            try {
                server.clearPlayerGuis();
            } finally {
                if (server.isHosting()) {
                    server.stopServer();
                }
            }
        }
    }

    private static void configureLobby(ServerGameLobby lobby, Deck aliceDeck, Deck bobDeck) {
        LobbySlot alice = lobby.getSlot(0);
        alice.setType(LobbySlotType.AI);
        alice.setName("Alice (G3 Host AI)");
        alice.setDeck(aliceDeck);
        alice.setIsReady(true);

        LobbySlot bob = lobby.getSlot(1);
        bob.setType(LobbySlotType.OPEN);
        bob.setDeck(bobDeck);
        bob.setIsReady(false);
    }

    private static final class NoOpLobbyListener implements ILobbyListener {
        @Override
        public void message(String source, String message, ChatMessage.MessageType type) {
        }

        @Override
        public void update(GameLobbyData state, int slot) {
        }

        @Override
        public void close() {
        }

        @Override
        public ClientGameLobby getLobby() {
            return null;
        }
    }
}
