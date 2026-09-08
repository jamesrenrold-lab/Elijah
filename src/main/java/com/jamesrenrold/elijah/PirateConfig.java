package com.jamesrenrold.elijah;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PirateConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue BASE_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue DAMAGE_PER_POWDER;
    public static final ForgeConfigSpec.DoubleValue BASE_RECOIL;
    public static final ForgeConfigSpec.DoubleValue RECOIL_PER_POWDER;
    public static final ForgeConfigSpec.IntValue COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EFFECT_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Flintlock: mobility and disruption first; damage is secondary.",
                "Damage uses health points (2 points = 1 heart). Recoil uses velocity per tick.")
                .push("flintlock");
        BASE_DAMAGE = b.defineInRange("baseDamage", 2.0, 0.0, 1000.0);
        DAMAGE_PER_POWDER = b.defineInRange("damagePerGunpowder", 1.0, 0.0, 1000.0);
        BASE_RECOIL = b.defineInRange("baseRecoil", 0.25, 0.0, 4.0);
        RECOIL_PER_POWDER = b.defineInRange("recoilPerGunpowder", 0.22, 0.0, 2.0);
        COOLDOWN_TICKS = b.defineInRange("cooldownTicks", 20, 1, 12000);
        EFFECT_TICKS = b.comment("Duration of Darkness, Blindness and Slowness I on a successful hit.")
                .defineInRange("effectDurationTicks", 20, 1, 12000);
        b.pop();
        SPEC = b.build();
    }

    private PirateConfig() {}
}
