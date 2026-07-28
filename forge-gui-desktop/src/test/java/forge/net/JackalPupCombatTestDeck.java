package forge.net;

import forge.card.CardDb;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Development-only creature-heavy deck recipe for attacker/blocker UX testing. */
public final class JackalPupCombatTestDeck {
    public static final int CARD_COUNT = 60;
    public static final int MOUNTAIN_COUNT = 22;
    public static final int JACKAL_PUP_COUNT = 38;

    private static final Map<String, Integer> CARD_COUNTS = createCardCounts();

    private JackalPupCombatTestDeck() {
    }

    public static Deck create(String name) {
        CardDb cardDb = FModel.getMagicDb().getCommonCards();
        Deck deck = new Deck(name);
        for (Map.Entry<String, Integer> entry : CARD_COUNTS.entrySet()) {
            PaperCard card = cardDb.getCard(entry.getKey());
            if (card == null) {
                throw new IllegalStateException("Jackal Pup combat test card not found: "
                        + entry.getKey());
            }
            deck.getMain().add(card, entry.getValue());
        }
        if (deck.getMain().countAll() != CARD_COUNT) {
            throw new IllegalStateException("Jackal Pup combat test deck must contain exactly "
                    + CARD_COUNT + " cards, found " + deck.getMain().countAll());
        }
        return deck;
    }

    public static Map<String, Integer> cardCounts() {
        return CARD_COUNTS;
    }

    private static Map<String, Integer> createCardCounts() {
        Map<String, Integer> cards = new LinkedHashMap<>();
        cards.put("Mountain", MOUNTAIN_COUNT);
        cards.put("Jackal Pup", JACKAL_PUP_COUNT);
        return Map.copyOf(cards);
    }
}
