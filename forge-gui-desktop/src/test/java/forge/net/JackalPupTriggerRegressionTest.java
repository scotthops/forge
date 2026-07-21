package forge.net;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.card.Card;
import forge.game.card.CardDamageTable;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityView;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class JackalPupTriggerRegressionTest extends AITest {
    @Test
    public void testHumanControlledJackalPupDamageTriggerResolves() {
        Game game = initAndCreateGame();
        Player controller = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        RecordingGui gui = installHumanController(game, controller);
        Card pup = addCard("Jackal Pup", controller);
        game.getTriggerHandler().registerActiveTrigger(pup, false);
        Card damageSource = addCard("Mogg Fanatic", opponent);

        CardDamageTable damage = new CardDamageTable();
        damage.put(damageSource, pup, 1);
        game.getAction().dealDamage(false, damage, new CardDamageTable(),
                new GameEntityCounterTable(), null);
        Assert.assertTrue(game.getStack().hasSimultaneousStackEntries(),
                "Jackal Pup's Forge trigger should be waiting to enter the stack");
        Assert.assertTrue(game.getStack().addAllTriggeredAbilitiesToStack());
        Assert.assertEquals(gui.abilitySelections, 1);
        Assert.assertFalse(gui.lastOfferedCanPlay,
                "The mandatory trigger is not a normally activatable ability");
        Assert.assertFalse(game.getStack().isEmpty());
        game.getAction().checkStateEffects(true);

        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);

        Assert.assertEquals(controller.getLife(), 19);
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertFalse(game.isGameOver());
    }

    private static RecordingGui installHumanController(Game game, Player player) {
        LobbyPlayerHuman lobbyPlayer = new LobbyPlayerHuman("Godot Human");
        PlayerControllerHuman human = new PlayerControllerHuman(game, player, lobbyPlayer);
        RecordingGui gui = new RecordingGui();
        human.setGui(gui);
        player.dangerouslySetController(human);
        return gui;
    }

    private static final class RecordingGui extends HeadlessNetworkGuiGame {
        private int abilitySelections;
        private boolean lastOfferedCanPlay;

        @Override
        public SpellAbilityView getAbilityToPlay(CardView hostCard,
                List<SpellAbilityView> abilities, forge.util.ITriggerEvent triggerEvent) {
            abilitySelections++;
            lastOfferedCanPlay = abilities.get(0).canPlay();
            return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
        }
    }
}
