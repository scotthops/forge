package forge.net;

import forge.deck.Deck;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.Objects;

/** Development-only Sligh host with two remote-human seats and no host player. */
public final class GodotTwoHumanSlighHostMain {
    private static final long JOIN_TIMEOUT_MILLIS = 120_000;

    private GodotTwoHumanSlighHostMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 36743 : Integer.parseInt(args[0]);
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);

        Deck firstDeck = SlighMirrorDeck.create("Two-Human Sligh - First");
        Deck secondDeck = SlighMirrorDeck.create("Two-Human Sligh - Second");

        FServerManager server = FServerManager.getInstance();
        try {
            server.startServer(port);
            ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);
            server.setLobbyListener(new NoOpLobbyListener());
            configureLobby(lobby, firstDeck, secondDeck);

            System.out.println("TWO_HUMAN_SLIGH_WAITING port=" + port + " remoteSeats=0,1");
            long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline && !bothRemoteClientsReady(lobby, server)) {
                Thread.sleep(100);
            }
            if (!bothRemoteClientsReady(lobby, server)) {
                throw new IllegalStateException("Two distinct remote bridges did not join and become ready"
                        + " within " + JOIN_TIMEOUT_MILLIS / 1_000 + " seconds");
            }
            if (Objects.equals(lobby.getSlot(0).getName(), lobby.getSlot(1).getName())) {
                throw new IllegalStateException("Use distinct usernames for the two bridge clients");
            }

            Runnable startGame = lobby.startGame();
            if (startGame == null) {
                throw new IllegalStateException("Two-human Sligh lobby was not ready to start");
            }
            startGame.run();
            System.out.println("TWO_HUMAN_SLIGH_STARTED port=" + port + " shuffle=normal"
                    + " seat0=" + lobby.getSlot(0).getName()
                    + " seat1=" + lobby.getSlot(1).getName());
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

    static void configureLobby(ServerGameLobby lobby, Deck firstDeck, Deck secondDeck) {
        for (int index = 0; index < 2; index++) {
            LobbySlot slot = lobby.getSlot(index);
            slot.setType(LobbySlotType.OPEN);
            slot.setName(null);
            slot.setDeck(index == 0 ? firstDeck : secondDeck);
            slot.setIsReady(false);
        }
    }

    private static boolean bothRemoteClientsReady(ServerGameLobby lobby, FServerManager server) {
        RemoteClient first = server.findClientByIndex(0);
        RemoteClient second = server.findClientByIndex(1);
        return first != null && second != null && first != second
                && lobby.getSlot(0).getType() == LobbySlotType.REMOTE
                && lobby.getSlot(1).getType() == LobbySlotType.REMOTE
                && lobby.getSlot(0).isReady() && lobby.getSlot(1).isReady();
    }

    private static final class NoOpLobbyListener implements ILobbyListener {
        @Override public void message(String source, String message, ChatMessage.MessageType type) { }
        @Override public void update(GameLobbyData state, int slot) { }
        @Override public void close() { }
        @Override public ClientGameLobby getLobby() { return null; }
    }
}
