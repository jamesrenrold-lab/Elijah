package com.jamesrenrold.elijah.client;

/** Client-side mirror used only for drawing the HUD. It never gates an ability. */
public final class ClientHudState {
    private static boolean received;
    private static boolean pirate;
    private static int bloodResource;
    private static int crewResource;
    private static boolean cursedFormActive;
    private static boolean huntActive;
    private static boolean flightActive;
    private static boolean overdriveActive;
    private static boolean exhaustedActive;
    private static boolean dirtyTacticsArmed;
    private static int dirtyCooldown;
    private static int shotCooldown;
    private static int cursedCooldown;
    private static int huntRemaining;
    private static int huntCooldown;
    private static int flightRemaining;
    private static int flightCooldown;
    private static int overdriveRemaining;
    private static int exhaustedRemaining;
    private static int domainRemaining;
    private static int domainCooldown;
    private static int crewRecharge;

    public static void apply(boolean newPirate, int newBloodResource, int newCrewResource,
                             boolean newCursedFormActive, boolean newHuntActive,
                             boolean newFlightActive, boolean newOverdriveActive,
                             boolean newExhaustedActive, boolean newDirtyTacticsArmed,
                             int newDirtyCooldown, int newShotCooldown, int newCursedCooldown,
                             int newHuntRemaining, int newHuntCooldown, int newFlightRemaining,
                             int newFlightCooldown, int newOverdriveRemaining,
                             int newExhaustedRemaining, int newDomainRemaining,
                             int newDomainCooldown, int newCrewRecharge) {
        received = true;
        pirate = newPirate;
        bloodResource = clamp(newBloodResource, 0, 100);
        crewResource = clamp(newCrewResource, 0, 4);
        cursedFormActive = newCursedFormActive;
        huntActive = newHuntActive;
        flightActive = newFlightActive;
        overdriveActive = newOverdriveActive;
        exhaustedActive = newExhaustedActive;
        dirtyTacticsArmed = newDirtyTacticsArmed;
        dirtyCooldown = clamp(newDirtyCooldown);
        shotCooldown = clamp(newShotCooldown);
        cursedCooldown = clamp(newCursedCooldown);
        huntRemaining = clamp(newHuntRemaining);
        huntCooldown = clamp(newHuntCooldown);
        flightRemaining = clamp(newFlightRemaining);
        flightCooldown = clamp(newFlightCooldown);
        overdriveRemaining = clamp(newOverdriveRemaining);
        exhaustedRemaining = clamp(newExhaustedRemaining);
        domainRemaining = clamp(newDomainRemaining);
        domainCooldown = clamp(newDomainCooldown);
        crewRecharge = clamp(newCrewRecharge);
    }

    /** Advances the display between authoritative server updates. */
    public static void tick() {
        if (!received) return;
        dirtyCooldown = decrement(dirtyCooldown);
        shotCooldown = decrement(shotCooldown);
        cursedCooldown = decrement(cursedCooldown);
        huntRemaining = decrement(huntRemaining);
        huntCooldown = decrement(huntCooldown);
        flightRemaining = decrement(flightRemaining);
        flightCooldown = decrement(flightCooldown);
        overdriveRemaining = decrement(overdriveRemaining);
        exhaustedRemaining = decrement(exhaustedRemaining);
        domainRemaining = decrement(domainRemaining);
        domainCooldown = decrement(domainCooldown);
        crewRecharge = decrement(crewRecharge);
    }

    public static void reset() {
        received = false;
        pirate = false;
    }

    public static boolean shouldRender() { return received && pirate; }
    public static int bloodResource() { return bloodResource; }
    public static int crewResource() { return crewResource; }
    public static boolean cursedFormActive() { return cursedFormActive; }
    public static boolean huntActive() { return huntActive; }
    public static boolean flightActive() { return flightActive; }
    public static boolean overdriveActive() { return overdriveActive; }
    public static boolean exhaustedActive() { return exhaustedActive; }
    public static boolean dirtyTacticsArmed() { return dirtyTacticsArmed; }
    public static int dirtyCooldown() { return dirtyCooldown; }
    public static int shotCooldown() { return shotCooldown; }
    public static int cursedCooldown() { return cursedCooldown; }
    public static int huntRemaining() { return huntRemaining; }
    public static int huntCooldown() { return huntCooldown; }
    public static int flightRemaining() { return flightRemaining; }
    public static int flightCooldown() { return flightCooldown; }
    public static int overdriveRemaining() { return overdriveRemaining; }
    public static int exhaustedRemaining() { return exhaustedRemaining; }
    public static int domainRemaining() { return domainRemaining; }
    public static int domainCooldown() { return domainCooldown; }
    public static int crewRecharge() { return crewRecharge; }

    private static int decrement(int value) { return Math.max(0, value - 1); }
    private static int clamp(int value) { return Math.max(0, value); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private ClientHudState() {}
}
