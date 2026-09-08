import com.jamesrenrold.elijah.ExperienceBonus;

/** Regression checks: splitting XP into tiny orbs must not erase the 30 percent bonus. */
public final class ExperienceBonusTest {
    public static void main(String[] args) {
        int total = 0, remainder = 0;
        for (int i = 0; i < 100; i++) {
            var result = ExperienceBonus.apply(1, remainder);
            total += result.amount();
            remainder = result.remainder();
        }
        check(total == 130 && remainder == 0, "One-point pickups lost the bonus");
        int[] split = {1, 3, 7, 13, 19, 37, 20};
        total = 0;
        for (int amount : split) {
            var result = ExperienceBonus.apply(amount, remainder);
            total += result.amount();
            remainder = result.remainder();
        }
        check(total == ExperienceBonus.apply(100, 0).amount() && remainder == 0,
                "The bonus depends on how XP is split");
        var removal = ExperienceBonus.apply(-5, 7);
        check(removal.amount() == -5 && removal.remainder() == 7, "XP loss was modified");
        var first = ExperienceBonus.apply(3, 0);
        var next = ExperienceBonus.apply(1, first.remainder());
        check(first.amount() + next.amount() == 5 && next.remainder() == 2,
                "Fractional progress did not carry forward");
        check(ExperienceBonus.apply(Integer.MAX_VALUE, 9).amount() == Integer.MAX_VALUE,
                "Large XP awards overflowed");
        System.out.println("XP bonus checks passed: small pickups, split awards, carry, loss, overflow.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
