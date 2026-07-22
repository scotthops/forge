package forge.game.mulligan;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class LondonMulligan extends AbstractMulligan {
    public LondonMulligan(Player p, boolean firstMullFree) {
        super(p, firstMullFree);
    }

    @Override
    public boolean canMulligan() {
        return !kept && tuckCardsDuringMulligan() <= player.getMaxHandSize();
    }

    @Override
    public int handSizeAfterNextMulligan() {
        return player.getMaxHandSize();
    }

    @Override
    public void mulliganDraw() {
        player.drawCards(handSizeAfterNextMulligan());
    }

    @Override
    public void afterMulligan() {
        int tuckingCards = tuckCardsDuringMulligan();
        if (tuckingCards > 0) {
            CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
            for (final Card c : player.getController().tuckCardsViaMulligan(hand, tuckingCards)) {
                player.getGame().getAction().moveToLibrary(c, -1, null);
            }
        }
        super.afterMulligan();
    }

    @Override
    public int tuckCardsDuringMulligan() {
        if (timesMulliganed == 0) {
            return 0;
        }

        int extraCard = firstMulliganFree ? 1 : 0;
        return timesMulliganed - extraCard;
    }
}
