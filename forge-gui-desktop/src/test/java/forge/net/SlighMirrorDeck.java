package forge.net;

import forge.card.CardDb;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact development deck recipe for the Premodern H1 Sligh mirror. */
public final class SlighMirrorDeck {
    public static final int CARD_COUNT = 60;

    private static final Map<String, Integer> CARD_COUNTS = createCardCounts();

    private SlighMirrorDeck() {
    }

    public static Deck create(String name) {
        CardDb cardDb = FModel.getMagicDb().getCommonCards();
        Deck deck = new Deck(name);
        for (Map.Entry<String, Integer> entry : CARD_COUNTS.entrySet()) {
            PaperCard card = cardDb.getCard(entry.getKey());
            if (card == null) {
                throw new IllegalStateException("H1 Sligh card not found: " + entry.getKey());
            }
            deck.getMain().add(card, entry.getValue());
        }
        if (deck.getMain().countAll() != CARD_COUNT) {
            throw new IllegalStateException("H1 Sligh deck must contain exactly " + CARD_COUNT
                    + " cards, found " + deck.getMain().countAll());
        }
        return deck;
    }

    public static Map<String, Integer> cardCounts() {
        return CARD_COUNTS;
    }

    private static Map<String, Integer> createCardCounts() {
        Map<String, Integer> cards = new LinkedHashMap<>();
        cards.put("Ball Lightning", 2);
        cards.put("Cursed Scroll", 3);
        cards.put("Fireblast", 4);
        cards.put("Flame Rift", 1);
        cards.put("Goblin Patrol", 2);
        cards.put("Grim Lavamancer", 4);
        cards.put("Incinerate", 4);
        cards.put("Jackal Pup", 4);
        cards.put("Lightning Bolt", 4);
        cards.put("Mogg Fanatic", 4);
        cards.put("Price of Progress", 2);
        cards.put("Earthquake", 1);
        cards.put("Seal of Fire", 4);
        cards.put("Mountain", 21);
        return Map.copyOf(cards);
    }
}
