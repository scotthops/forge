package forge.game.combat;

import forge.game.GameEntityView;
import forge.game.card.CardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forge-owned description and validation of one human combat-damage assignment.
 * GUI implementations may present the recipients however they like, but should
 * return only assignments accepted by this model.
 */
public final class CombatDamageAssignment {
    public enum RecipientRole {
        BLOCKER,
        DEFENDER
    }

    public record Recipient(String key, GameEntityView entity, RecipientRole role,
                            int order, int minimumDamage) { }

    public record Validation(boolean valid, String message) {
        private static Validation ok() {
            return new Validation(true, "");
        }

        private static Validation error(String message) {
            return new Validation(false, message);
        }
    }

    private final CardView source;
    private final int totalDamage;
    private final List<Recipient> recipients;
    private final boolean orderedAssignment;
    private final boolean defenderRequiresLethalBlockers;
    private final boolean freeAssignment;
    private final boolean maySkip;

    private CombatDamageAssignment(CardView source, int totalDamage,
            List<Recipient> recipients, boolean orderedAssignment,
            boolean defenderRequiresLethalBlockers, boolean freeAssignment,
            boolean maySkip) {
        this.source = source;
        this.totalDamage = totalDamage;
        this.recipients = List.copyOf(recipients);
        this.orderedAssignment = orderedAssignment;
        this.defenderRequiresLethalBlockers = defenderRequiresLethalBlockers;
        this.freeAssignment = freeAssignment;
        this.maySkip = maySkip;
    }

    public static CombatDamageAssignment create(CardView source, List<CardView> blockers,
            int totalDamage, GameEntityView defender, boolean overrideOrder,
            boolean maySkip) {
        List<CardView> orderedBlockers = blockers == null ? Collections.emptyList() : blockers;
        boolean hasDeathtouch = source != null && source.getCurrentState().hasDeathtouch();
        boolean hasTrample = source != null && source.getCurrentState().hasTrample();
        boolean freeAssignment = source != null && overrideOrder
                && source.getCurrentState().hasDivideDamage();

        List<Recipient> recipients = new ArrayList<>();
        for (int i = 0; i < orderedBlockers.size(); i++) {
            CardView blocker = orderedBlockers.get(i);
            recipients.add(new Recipient("blocker:" + i, blocker, RecipientRole.BLOCKER,
                    i, lethalDamage(blocker, hasDeathtouch)));
        }
        if (defender != null && (hasTrample || freeAssignment)) {
            recipients.add(new Recipient("defender", defender, RecipientRole.DEFENDER,
                    recipients.size(), 0));
        }

        return new CombatDamageAssignment(source, totalDamage, recipients,
                !overrideOrder && !freeAssignment,
                !freeAssignment && recipients.stream()
                        .anyMatch(recipient -> recipient.role() == RecipientRole.DEFENDER),
                freeAssignment, maySkip);
    }

    public CardView source() {
        return source;
    }

    public int totalDamage() {
        return totalDamage;
    }

    public List<Recipient> recipients() {
        return recipients;
    }

    public boolean orderedAssignment() {
        return orderedAssignment;
    }

    public boolean defenderRequiresLethalBlockers() {
        return defenderRequiresLethalBlockers;
    }

    public boolean freeAssignment() {
        return freeAssignment;
    }

    public boolean maySkip() {
        return maySkip;
    }

    public Validation validate(Map<String, Integer> amounts) {
        if (amounts == null) {
            return Validation.error("Combat damage assignments are required");
        }

        Set<String> expectedKeys = new LinkedHashSet<>();
        long assigned = 0;
        for (Recipient recipient : recipients) {
            expectedKeys.add(recipient.key());
            Integer amount = amounts.get(recipient.key());
            if (amount == null) {
                return Validation.error("Missing damage amount for " + recipient.key());
            }
            if (amount < 0) {
                return Validation.error("Damage amounts cannot be negative");
            }
            assigned += amount;
        }
        if (!amounts.keySet().equals(expectedKeys)) {
            return Validation.error("Assignment contains an unknown or duplicate recipient");
        }
        if (assigned != totalDamage) {
            return Validation.error("Assigned damage must total " + totalDamage);
        }
        if (freeAssignment) {
            return Validation.ok();
        }

        boolean allEarlierBlockersHaveLethal = true;
        for (Recipient recipient : recipients) {
            int amount = amounts.get(recipient.key());
            if (recipient.role() == RecipientRole.DEFENDER) {
                if (defenderRequiresLethalBlockers && amount > 0
                        && !allEarlierBlockersHaveLethal) {
                    return Validation.error(
                            "Lethal damage must be assigned to every blocker before the defender");
                }
                continue;
            }

            if (orderedAssignment && amount > 0 && !allEarlierBlockersHaveLethal) {
                return Validation.error(
                        "Lethal damage must be assigned to each earlier blocker first");
            }
            allEarlierBlockersHaveLethal &= amount >= recipient.minimumDamage();
        }
        return Validation.ok();
    }

    public Map<CardView, Integer> toForgeResult(Map<String, Integer> amounts) {
        Validation validation = validate(amounts);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }
        Map<CardView, Integer> result = new LinkedHashMap<>();
        for (Recipient recipient : recipients) {
            CardView key = recipient.role() == RecipientRole.DEFENDER
                    ? null : (CardView) recipient.entity();
            result.put(key, amounts.get(recipient.key()));
        }
        return result;
    }

    private static int lethalDamage(CardView card, boolean sourceHasDeathtouch) {
        int lethal = Math.max(0, card.getLethalDamage());
        if (card.getCurrentState().isPlaneswalker()) {
            lethal = parseNonNegative(card.getCurrentState().getLoyalty());
        } else if (card.getCurrentState().isBattle()) {
            lethal = parseNonNegative(card.getCurrentState().getDefense());
        } else if (sourceHasDeathtouch) {
            lethal = Math.min(lethal, 1);
        }
        return lethal;
    }

    private static int parseNonNegative(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
