package com.jamesrenrold.elijah;

/** Integer arithmetic carries fractional XP across pickups instead of losing small bonuses. */
public final class ExperienceBonus {
    public record Result(int amount, int remainder) {}

    public static Result apply(int amount, int remainder) {
        if (amount <= 0) return new Result(amount, remainder);
        long tenths = (long) amount * 3 + remainder;
        long total = (long) amount + tenths / 10;
        return new Result((int) Math.min(Integer.MAX_VALUE, total), (int) (tenths % 10));
    }

    private ExperienceBonus() {}
}
