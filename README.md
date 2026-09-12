# Elijah Pirate — Minecraft 1.20.1

A pirate Origin built for Forge 47.4.4+ with Fabric Origins 1.10.x through Connector.
The JAR includes the Origin data, powder-pouch screen, flintlock renderer, Dirty Tactics, undead crewmates and pirate passives.

**Updating from an earlier release:** remove the old Elijah JAR and install 0.3.1 on the client and server. Do not keep two Elijah versions installed. Existing pouch contents are preserved.

## Install

1. Download the `Elijah-Pirate-1.20.1` artifact from the latest successful GitHub Actions build and unzip it.
2. Put `elijah-pirate-0.3.1.jar` in your Minecraft instance's `mods` folder.
3. On multiplayer, install that same JAR on the server and every player's client.
4. Keep your existing Forge / Connector / Fabric Origins / Even More Origins Keybinds setup installed. Restart Minecraft and the server.
5. Select **Elijah — The Powder Corsair**. An operator can select it for a player with:
   `/origin set <player> origins:origin elijah:pirate`

There is no additional datapack ZIP or resource pack to install for this version. HUD bars use the standard Origins/Apoli resource-bar texture supplied by Origins.

## Abilities

| Keybind | Ability |
|---|---|
| Primary Active Power | Dirty Tactics |
| Secondary Active Power | Flintlock Kick |
| Tertiary Active Power | Powder Pouch |
| Quaternary Active Power | Call of the Drowned Crew |

Passives activate automatically and have no keybind.

- **Tertiary Active Power — Powder Pouch:** opens a separate powder-pouch screen with three compartments. The **Load** chamber holds up to **9** gunpowder and is the ammunition used by the flintlock. The **Reserve 2x2** grid has four gunpowder slots holding up to **64 each** (**256** total), and the **Generator** output holds up to **5** gunpowder. The generator adds one powder every **30 seconds** while below its five-powder cap and shows its countdown in the pouch screen. Drag, right-click, number-key swap and shift-click use normal inventory controls; shift-click prioritizes the load chamber and preserves partial stacks. Generator powder is output-only and can be moved into the chamber or reserve. Excess powder stays in the player's inventory/cursor.
- **Secondary Active Power — Flintlock Kick:** fires a small black ball and consumes **all** powder in the pouch. No powder means no shot, recoil or cooldown. The shot works without holding a weapon.
- More powder gives more recoil in the direction opposite your aim. Shoot downward to propel yourself upward. The Origin grants **85% fall-damage resistance**, so only 15% of normal fall damage remains.
- Damage is secondary: the powder component is **2 + 1 per gunpowder**, from **3 damage at 1** to **11 damage at 9** before armor. The shot then adds **20% of the player's current `irons_spellbooks:spell_power` attribute** and **30% of current `generic.attack_damage`**. Two damage points equal one heart.
- Successful hits apply **Darkness, Blindness and Slowness I for 20 ticks / 1 second**, regardless of powder count. Darkness and Blindness affect player vision; they do not alter mob AI. Normal shields, invulnerability and damage-cancellation rules apply.
- The firing cooldown is **20 ticks / 1 second**. Projectile speed is 3.5 blocks/tick with slight gravity; it disappears after 30 ticks or its first collision. It does not explode or destroy blocks.

- **Primary Active Power — Dirty Tactics:** arms your next successful melee hit. That target takes **3 flat bonus damage**, gets **Slowness III for 2 seconds**, and emits the **skeleton death** sound. The **18-second server cooldown starts when the hit lands**. Missing, shooting the flintlock, or hitting a shield does not consume the charge. One target per activation; no charge stacking. Dying or changing Origin clears a primed charge; relogs and respawns do not reset an active cooldown.

- **Blood Rush:** each activation adds **20** charge immediately and starts a **10-second active window**. During that window the meter rises by **1** per second; while inactive it drains by **1** every **4 seconds** (0.25 per second). At 100 charge it automatically consumes the meter and triggers a **25-second overfill** with Blindness, Darkness, Strength, Weakness II, Slowness I, **50% lifesteal**, and health degeneration that is clamped so it cannot kill the player. Blood abilities are locked during the overfill. Afterwards, **Exsanguinated** lasts 30 seconds with no natural regeneration, -25% movement speed, -30% attack speed and -20% attack damage.

- **Quaternary Active Power — Call of the Drowned Crew:** starts with **four** resource charges. Each press spends **one** charge and summons **one** undead crewmate, never a whole group. Each crewmate wears the exact supplied 64×64 golden-and-red [Undead Pirate Captain skin](https://www.minecraftskins.com/skin/21355492/undead-pirate-captain/), follows the nearest valid mob you most recently attacked or that most recently attacked you, and keeps moving forward while attempting swings inside **1 block**. Damage still requires the intentionally close **0.5-block** range. It lasts **30 seconds** and one charge returns every **60 seconds**, with the bone/sailor resource bar showing the current charges. Each crewmate snapshots **80% of the summoner's current max health and attack damage** and uses **2.5 attack speed** (about an 8-tick attack interval). Epic Fight's optional biped mob patch supplies sword/tachi animations; without Epic Fight, a vanilla combat goal provides the same pressure-and-hit behavior. Crewmates are summon-only entities and drop **no loot or equipment** when they die.

Bind **Primary Active Power**, **Secondary Active Power**, **Tertiary Active Power** and **Quaternary Active Power** in Minecraft's Controls menu. The Powder Pouch uses `key.origins.tertiary_active`, and the crew uses `key.origins.quaternary_active`; both are the first added bindings from your existing [Even More Origins Keybinds](https://www.curseforge.com/minecraft/mc-mods/even-more-origins-keybinds) mod.

- **Octonary Active Power — Drowned Domain:** pulls the hostile mob you are directly looking at (up to 40 blocks away) into a separate, always-day dimension for **40 seconds**. The transferred mob is made persistent, permanently targets the caster, and is actively navigated toward them for the entire session; its original persistence state is restored on return. The working Echo transfer order removes the source before registering its same-UUID destination copy and restores the source if reconstruction fails. The caster begins inside a large two-block-deep lagoon while the target starts deep on the raised sandy crescent at X=-34. The beach has layered dunes and a curved, eight-frond palm; twelve additional dune islands ring the far ocean outside the barrier. Four flagship-scale three-mast galleons sit broadside-on beyond the arena walls. Each has a deep seven-layer tapered hull, raised forecastle, two-storey sterncastle, separate upper/lower **three-block-billowed sails**, rigging, cabin windows, a gilded prow, and **ten sideways CBC cannons per broadside** firing through an enclosed lower gun deck when Create Big Cannons is installed. The first activation after a server restart purges every legacy arena and ship footprint before rebuilding. After five seconds, visible server-traced cannonballs alternate between the four ship-facing walls every half-second and scatter around the target. They detonate only when they touch a hitbox or block, outline their exact four-block blast volume with a short-lived smoke shell, and deal **55% of the caster's current attack damage + 1.5 damage per completed 10% Curse**. The caster gains **+50% movement speed**, Dolphin's Grace III, Regeneration II, and **+50% lifesteal**. The testing build has **no cooldown at all**; only the currently active 40-second session prevents overlapping casts and reports its remaining time.

## Passives

| Passive | Effect |
|---|---|
| Wisdom of the Sea | **+30% experience points**, with fractional progress carried between pickups; **+25% swim-speed attribute** at all times. |
| Land Legs | **-5% movement speed outside water**, removed as soon as the water condition updates (once per tick). |
| Pirate Frailty | **-15% total armor** and **-4 maximum health points (2 hearts)** at all times. |

With otherwise vanilla stats, maximum health is 16 points / 8 hearts. Armor and speed penalties multiply the total value, so equipment bonuses are included. Attribute modifiers belong to the Origin and are removed when it is lost. The XP bonus applies to positive XP-point gains, not direct level adjustments or XP removal; XP spent repairing equipment is not player XP gained. Ten separate 1-point gains still award 13 points in total.

The 11-damage value is the maximum powder component; spell-power and physical-attack scaling are added on top of it.

## Inventory persistence

The pouch belongs to each individual player and saves across logouts and dimension changes. On death it follows `keepInventory`: powder drops when false and stays when true. Changing away from this Origin returns the powder to the player's normal inventory, or drops it at their feet if that inventory is full.

## Tuning

After the world starts, edit `serverconfig/elijah-server.toml` inside that world's folder. On a dedicated server this is usually `world/serverconfig/elijah-server.toml`.

Defaults:

| Setting | Value |
|---|---:|
| `baseDamage` | 2.0 |
| `damagePerGunpowder` | 1.0 |
| `spellPowerDamageScale` | 0.20 |
| `physicalDamageScale` | 0.30 |
| `baseRecoil` | 0.25 |
| `recoilPerGunpowder` | 0.22 |
| `cooldownTicks` | 20 |
| `effectDurationTicks` | 20 |
| `lifetimeTicks` | 600 (30 seconds) |
| `maximumCrewmates` | 4 |
| `attackRange` | 0.5 blocks |
| `pressureRange` | 1.0 block |
| `attackSpeed` | 2.5 |
| `goldenHonshuItem` | `dungeons_and_combat:golden_honshu` |

The nine-item firing-chamber capacity, four-slot reserve with 64 per slot, and five-item generator cap are fixed. Damage, recoil and crewmate stats are calculated on the server. Crew resources are an Origins resource, so they sync to the client and render with the standard Origins/Apoli resource-bar sheet. The flintlock cooldown and combat state persist through relogs and respawns.

## Building and development

GitHub Actions installs Java 17 and Gradle 8.8, uses Gradle caching, runs data validation and fractional-XP regression checks, compiles and reobfuscates the Forge JAR, then uploads the installable artifact. Push related changes together to avoid unnecessary builds.

To build locally with Java 17 and Gradle 8.8 installed: `gradle build`. The installable JAR is in `build/libs/`.

Operator-only diagnostic commands: `/elijah pouch`, `/elijah fire`, `/elijah dirty_tactics`, `/elijah crew`, `/elijah unload`. The hidden `/elijah sea_on` and `/elijah sea_off` commands are managed by the Wisdom of the Sea lifecycle callbacks. Origins executes these internally through its power actions, so ordinary players do not need operator permissions to use their Origin. As in the other command-based Origins, Apoli's `executeCommand` permission level must remain at its default of 2 or higher.

Compile success verifies the Forge API integration, not in-game behavior in the complete modpack. The first gameplay check should cover the slot limit, firing at 1 and 9 powder, recoil, effects, and survival respawn, Dirty Tactics on a melee hit, and passive stats after switching Origin.
