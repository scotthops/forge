package forge.net;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BlockingCombatRegressionTest extends AITest {
    @Test
    public void testOneAttackerWithNoBlockersAvailable() {
        Scenario scenario = scenario("Hill Giant", null);

        resolveCombat(scenario);

        Assert.assertEquals(scenario.defender.getLife(), 17);
    }

    @Test
    public void testOneAttackerWithOneLegalBlocker() {
        Scenario scenario = scenario("Grizzly Bears", "Ajani's Sunstriker");
        declareBlock(scenario);

        resolveCombat(scenario);

        Assert.assertTrue(scenario.combat.isBlocked(scenario.attacker));
        Assert.assertEquals(scenario.defender.getLife(), 20);
    }

    @Test
    public void testDefenderMayDeclineLegalBlock() {
        Scenario scenario = scenario("Hill Giant", "Grizzly Bears");
        Assert.assertTrue(CombatUtil.canBlock(
                scenario.attacker, scenario.blocker, scenario.combat));

        resolveCombat(scenario);

        Assert.assertEquals(scenario.defender.getLife(), 17);
        Assert.assertEquals(scenario.blocker.getZone().getZoneType(), ZoneType.Battlefield);
    }

    @Test
    public void testBallLightningBlockedCombatResolvesInForgeEngine() {
        Scenario scenario = scenario("Ball Lightning", "Jackal Pup");
        declareBlock(scenario);

        resolveCombat(scenario);

        Assert.assertEquals(scenario.defender.getLife(), 15);
        Assert.assertTrue(scenario.combat.isBlocked(scenario.attacker));
    }

    private Scenario scenario(String attackerName, String blockerName) {
        Game game = initAndCreateGame();
        Player attackerController = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);
        Card attacker = addCard(attackerName, attackerController);
        Card blocker = blockerName == null ? null : addCard(blockerName, defender);
        Combat combat = new Combat(attackerController);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, defender);
        return new Scenario(game, defender, attacker, blocker, combat);
    }

    private static void declareBlock(Scenario scenario) {
        Assert.assertTrue(CombatUtil.canBlock(
                scenario.attacker, scenario.blocker, scenario.combat));
        scenario.combat.addBlocker(scenario.attacker, scenario.blocker);
        Assert.assertNull(CombatUtil.validateBlocks(scenario.combat, scenario.defender));
    }

    private static void resolveCombat(Scenario scenario) {
        scenario.combat.orderBlockersForDamageAssignment();
        scenario.combat.fireTriggersForUnblockedAttackers(scenario.game);
        Assert.assertTrue(scenario.combat.assignCombatDamage(false));
        scenario.combat.dealAssignedDamage();
        scenario.game.getAction().checkStateEffects(true);
    }

    private record Scenario(Game game, Player defender, Card attacker,
                            Card blocker, Combat combat) { }
}
