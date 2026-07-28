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

/**
 * Development-only, normally shuffled Jackal Pup mirror host for repeatedly testing
 * attacker and blocker UX in the Godot client.
 */
public final class GodotJackalPupCombatHostMain {
    private GodotJackalPupCombatHostMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 36743 : Integer.parseInt(args[0]);
        // The shared test-host bootstrap disables lobby legality enforcement only in this
        // development JVM. This fixture's intentional Constructed violation is 38 Pups.
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);

        Deck aiDeck = JackalPupCombatTestDeck.create("Combat Test - Forge AI");
        Deck godotDeck = JackalPupCombatTestDeck.create("Combat Test - Godot Human");
        System.out.println("JACKAL_PUP_COMBAT_DECK_VERIFIED bothPlayers=true cardsPerDeck="
                + JackalPupCombatTestDeck.CARD_COUNT);

        FServerManager server = FServerManager.getInstance();
        try {
            server.startServer(port);
            ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);
            server.setLobbyListener(new NoOpLobbyListener());
            configureLobby(lobby, aiDeck, godotDeck);

            LobbySlot remote = lobby.getSlot(1);
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline
                    && (remote.getType() != LobbySlotType.REMOTE || !remote.isReady())) {
                Thread.sleep(100);
            }
            if (remote.getType() != LobbySlotType.REMOTE || !remote.isReady()) {
                throw new IllegalStateException(
                        "Godot bridge did not join and become ready within 60 seconds");
            }

            Runnable startGame = lobby.startGame();
            if (startGame == null) {
                throw new IllegalStateException("Jackal Pup combat test lobby was not ready to start");
            }
            startGame.run();
            System.out.println("JACKAL_PUP_COMBAT_MATCH_STARTED port=" + port
                    + " shuffle=normal remote=Godot opponent=ForgeAI");
            if (args.length > 1) {
                Thread.sleep(Long.parseLong(args[1]) * 1_000L);
            } else {
                System.out.println("Press Enter after the playtest to stop the host.");
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

    static void configureLobby(ServerGameLobby lobby, Deck aiDeck, Deck godotDeck) {
        LobbySlot ai = lobby.getSlot(0);
        ai.setType(LobbySlotType.AI);
        ai.setName("Jackal Pup Combat Test (Forge AI)");
        ai.setDeck(aiDeck);
        ai.setIsReady(true);

        LobbySlot godot = lobby.getSlot(1);
        godot.setType(LobbySlotType.OPEN);
        godot.setDeck(godotDeck);
        godot.setIsReady(false);
    }

    private static final class NoOpLobbyListener implements ILobbyListener {
        @Override public void message(String source, String message, ChatMessage.MessageType type) { }
        @Override public void update(GameLobbyData state, int slot) { }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }
}
