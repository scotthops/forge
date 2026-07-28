package forge.net;

import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.ServerGameLobby;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class JackalPupCombatHostTest {
    @BeforeClass
    public static void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test
    public void testExactCreatureHeavyDeckRecipe() {
        assertExactDeck(JackalPupCombatTestDeck.create("Combat Test Deck"));
    }

    @Test
    public void testConstructedValidationRejectsIntentionalCopyLimitViolation() {
        Deck deck = JackalPupCombatTestDeck.create("Intentionally Illegal Combat Test Deck");

        String problem = DeckFormat.Constructed.getDeckConformanceProblem(deck);

        Assert.assertNotNull(problem);
        Assert.assertTrue(problem.contains("4"), problem);
        Assert.assertTrue(problem.contains("Jackal Pup"), problem);
    }

    @Test
    public void testHostAssignsCreatureHeavyFixtureToBothSeats() {
        Deck aiDeck = JackalPupCombatTestDeck.create("Combat Test AI");
        Deck godotDeck = JackalPupCombatTestDeck.create("Combat Test Godot");
        ServerGameLobby lobby = new ServerGameLobby();

        GodotJackalPupCombatHostMain.configureLobby(lobby, aiDeck, godotDeck);

        LobbySlot ai = lobby.getSlot(0);
        Assert.assertEquals(ai.getType(), LobbySlotType.AI);
        Assert.assertTrue(ai.isReady());
        Assert.assertSame(ai.getDeck(), aiDeck);
        assertExactDeck(ai.getDeck());

        LobbySlot godot = lobby.getSlot(1);
        Assert.assertEquals(godot.getType(), LobbySlotType.OPEN);
        Assert.assertFalse(godot.isReady());
        Assert.assertSame(godot.getDeck(), godotDeck);
        assertExactDeck(godot.getDeck());
    }

    private static void assertExactDeck(Deck deck) {
        Assert.assertEquals(deck.getMain().countAll(), JackalPupCombatTestDeck.CARD_COUNT);
        Assert.assertEquals(deck.getMain().countByName("Mountain"),
                JackalPupCombatTestDeck.MOUNTAIN_COUNT);
        Assert.assertEquals(deck.getMain().countByName("Jackal Pup"),
                JackalPupCombatTestDeck.JACKAL_PUP_COUNT);
        Assert.assertEquals(JackalPupCombatTestDeck.cardCounts().size(), 2);
    }
}
