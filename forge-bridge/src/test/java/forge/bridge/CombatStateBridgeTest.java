package forge.bridge;

import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.trackable.Tracker;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class CombatStateBridgeTest {
    @Test
    public void testOneAttackerWithNoBlocker() {
        Fixture fixture = new Fixture();
        fixture.combat.addAttackingBand(
                List.of(fixture.card(101, "Attacker")),
                fixture.defender(),
                null,
                List.of());

        List<BridgePrinter.CombatSnapshot> snapshot =
                BridgePrinter.snapshotCombat(fixture.combat);

        assertAssignment(snapshot, 0, 101);
    }

    @Test
    public void testOneAttackerWithOnePlannedBlocker() {
        Fixture fixture = new Fixture();
        fixture.combat.addAttackingBand(
                List.of(fixture.card(101, "Attacker")),
                fixture.defender(),
                null,
                List.of(fixture.card(201, "Blocker")));

        List<BridgePrinter.CombatSnapshot> snapshot =
                BridgePrinter.snapshotCombat(fixture.combat);

        assertAssignment(snapshot, 0, 101, 201);
    }

    @Test
    public void testOneAttackerWithMultiplePlannedBlockers() {
        Fixture fixture = new Fixture();
        fixture.combat.addAttackingBand(
                List.of(fixture.card(101, "Attacker")),
                fixture.defender(),
                null,
                List.of(fixture.card(201, "First Blocker"),
                        fixture.card(202, "Second Blocker")));

        List<BridgePrinter.CombatSnapshot> snapshot =
                BridgePrinter.snapshotCombat(fixture.combat);

        assertAssignment(snapshot, 0, 101, 201, 202);
    }

    @Test
    public void testMultipleAttackersKeepSeparatePlannedBlockers() {
        Fixture fixture = new Fixture();
        fixture.combat.addAttackingBand(
                List.of(fixture.card(102, "Second Attacker")),
                fixture.defender(),
                null,
                List.of(fixture.card(202, "Second Blocker")));
        fixture.combat.addAttackingBand(
                List.of(fixture.card(101, "First Attacker")),
                fixture.defender(),
                null,
                List.of(fixture.card(201, "First Blocker")));

        List<BridgePrinter.CombatSnapshot> snapshot =
                BridgePrinter.snapshotCombat(fixture.combat);

        assertAssignment(snapshot, 0, 101, 201);
        assertAssignment(snapshot, 1, 102, 202);
    }

    private static void assertAssignment(List<BridgePrinter.CombatSnapshot> snapshot,
            int index, int attackerId, Integer... blockerIds) {
        Assert.assertTrue(snapshot.size() > index);
        Assert.assertEquals(snapshot.get(index).attackerCardId(), attackerId);
        Assert.assertEquals(snapshot.get(index).blockerCardIds(), List.of(blockerIds));
    }

    private static final class Fixture {
        private final Tracker tracker = new Tracker();
        private final CombatView combat = new CombatView(tracker);

        private CardView card(int id, String name) {
            return new CardView(id, tracker, name);
        }

        private CardView defender() {
            return card(900, "Defender");
        }
    }
}
