package forge.game.mulligan;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LondonMulliganTest extends AITest {
    @Test
    public void keepOpeningSevenDoesNotBottomCards() {
        LondonMulligan mulligan = createMulliganWithSevenCardHand();

        mulligan.keep();
        mulligan.afterMulligan();

        Assert.assertEquals(mulligan.getPlayer().getCardsIn(ZoneType.Hand).size(), 7);
    }

    @Test
    public void firstMulliganDrawsSevenThenBottomsOneAfterKeep() {
        LondonMulligan mulligan = createMulliganWithSevenCardHand();

        mulligan.mulligan();
        Assert.assertEquals(mulligan.getPlayer().getCardsIn(ZoneType.Hand).size(), 7);
        mulligan.keep();
        mulligan.afterMulligan();

        Assert.assertEquals(mulligan.getPlayer().getCardsIn(ZoneType.Hand).size(), 6);
    }

    private LondonMulligan createMulliganWithSevenCardHand() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        for (int i = 0; i < 7; i++) {
            addCardToZone("Mountain", player, ZoneType.Hand);
        }
        for (int i = 0; i < 14; i++) {
            addCardToZone("Mountain", player, ZoneType.Library);
        }
        return new LondonMulligan(player, false);
    }
}
