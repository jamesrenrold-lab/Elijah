package com.jamesrenrold.elijah;

import net.minecraftforge.common.ForgeConfigSpec;

public final class PirateConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue BASE_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue DAMAGE_PER_POWDER;
    public static final ForgeConfigSpec.DoubleValue SPELL_POWER_DAMAGE_SCALE;
    public static final ForgeConfigSpec.DoubleValue PHYSICAL_DAMAGE_SCALE;
    public static final ForgeConfigSpec.DoubleValue BASE_RECOIL;
    public static final ForgeConfigSpec.DoubleValue RECOIL_PER_POWDER;
    public static final ForgeConfigSpec.IntValue COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue EFFECT_TICKS;
    public static final ForgeConfigSpec.IntValue CREW_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue CREW_LIFETIME_TICKS;
    public static final ForgeConfigSpec.IntValue CREW_MAX_COUNT;
    public static final ForgeConfigSpec.DoubleValue CREW_ATTACK_RANGE;
    public static final ForgeConfigSpec.DoubleValue CREW_MOVE_SPEED;
    public static final ForgeConfigSpec.ConfigValue<String> GOLDEN_HONSHU_ITEM;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Flintlock: mobility and disruption first; damage is secondary.",
                "Damage uses health points (2 points = 1 heart). Recoil uses velocity per tick.")
                .push("flintlock");
        BASE_DAMAGE = b.defineInRange("baseDamage", 2.0, 0.0, 1000.0);
        DAMAGE_PER_POWDER = b.defineInRange("damagePerGunpowder", 1.0, 0.0, 1000.0);
        SPELL_POWER_DAMAGE_SCALE = b.defineInRange("spellPowerDamageScale", 0.20, 0.0, 10.0);
        PHYSICAL_DAMAGE_SCALE = b.defineInRange("physicalDamageScale", 0.30, 0.0, 10.0);
        BASE_RECOIL = b.defineInRange("baseRecoil", 0.25, 0.0, 4.0);
        RECOIL_PER_POWDER = b.defineInRange("recoilPerGunpowder", 0.22, 0.0, 2.0);
        COOLDOWN_TICKS = b.defineInRange("cooldownTicks", 20, 1, 12000);
        EFFECT_TICKS = b.comment("Duration of Darkness, Blindness and Slowness I on a successful hit.")
                .defineInRange("effectDurationTicks", 20, 1, 12000);
        b.pop();
        b.comment("Undead crew: summoned helpers follow the player's most recent combat target.",
                "The configured item is resolved from the item registry when the crew is summoned.")
                .push("undeadCrew");
        // The summon action is resource-gated by Origins. This legacy value is
        // retained for config compatibility; crew lifetime is 30 seconds.
        CREW_COOLDOWN_TICKS = b.defineInRange("summonCooldownTicks", 5, 1, 12000);
        CREW_LIFETIME_TICKS = b.defineInRange("lifetimeTicks", 600, 20, 24000);
        CREW_MAX_COUNT = b.defineInRange("maximumCrewmates", 4, 1, 16);
        CREW_ATTACK_RANGE = b.defineInRange("attackRange", 0.5, 0.1, 4.0);
        CREW_MOVE_SPEED = b.defineInRange("moveSpeed", 1.15, 0.1, 4.0);
        GOLDEN_HONSHU_ITEM = b.define("goldenHonshuItem", "dungeons_and_combat:golden_honshu");
        b.pop();
        SPEC = b.build();
    }

    private PirateConfig() {}
}
