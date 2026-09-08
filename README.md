# Elijah Pirate — Minecraft 1.20.1

A pirate Origin built for Forge 47.4.4+ with Fabric Origins 1.10.x through Connector.
The JAR includes the Origin data, powder-pouch screen and flintlock renderer.

## Install

1. Download the `Elijah-Pirate-1.20.1` artifact from the latest successful GitHub Actions build and unzip it.
2. Put `elijah-pirate-0.1.0.jar` in your Minecraft instance's `mods` folder.
3. On multiplayer, install that same JAR on the server and every player's client.
4. Keep your existing Forge / Connector / Fabric Origins setup installed. Restart Minecraft and the server.
5. Select **Elijah — The Powder Corsair**. An operator can select it for a player with:
   `/origin set <player> origins:origin elijah:pirate`

There is no additional datapack ZIP or resource pack to install for this version.

## Abilities

- **Secondary Active Power — Powder Pouch:** opens one separate slot. Only gunpowder fits, with a hard limit of **9**. Drag, right-click, number-key swap and shift-click use normal inventory controls. Excess powder stays in the player's inventory/cursor.
- **Primary Active Power — Flintlock Kick:** fires a small black ball and consumes **all** powder in the pouch. No powder means no shot, recoil or cooldown. The shot works without holding a weapon.
- More powder gives more recoil in the direction opposite your aim. Shoot downward to propel yourself upward; normal collision and fall damage still apply.
- Damage is secondary: **2 + 1 per gunpowder**, from **3 damage at 1** to **11 damage at 9** (before armor). Two damage points equal one heart.
- Successful hits apply **Darkness, Blindness and Slowness I for 20 ticks / 1 second**, regardless of powder count. Darkness and Blindness affect player vision; they do not alter mob AI. Normal shields, invulnerability and damage-cancellation rules apply.
- The firing cooldown is **20 ticks / 1 second**. Projectile speed is 3.5 blocks/tick with slight gravity; it disappears after 30 ticks or its first collision. It does not explode or destroy blocks.

Bind **Primary Active Power** and **Secondary Active Power** in Minecraft's Controls menu. These use the normal Origins bindings and coexist with Even More Origins Keybinds; no extra binding is needed for these first two powers.

## Inventory persistence

The pouch belongs to each individual player and saves across logouts and dimension changes. On death it follows `keepInventory`: powder drops when false and stays when true. Changing away from this Origin returns the powder to the player's normal inventory, or drops it at their feet if that inventory is full.

## Tuning

After the world starts, edit `serverconfig/elijah-server.toml` inside that world's folder. On a dedicated server this is usually `world/serverconfig/elijah-server.toml`.

Defaults:

| Setting | Value |
|---|---:|
| `baseDamage` | 2.0 |
| `damagePerGunpowder` | 1.0 |
| `baseRecoil` | 0.25 |
| `recoilPerGunpowder` | 0.22 |
| `cooldownTicks` | 20 |
| `effectDurationTicks` | 20 |

The nine-item capacity is fixed. Damage and recoil are calculated on the server. The cooldown also persists through relogs and respawns.

## Building and development

GitHub Actions installs Java 17 and Gradle 8.8, uses Gradle caching, runs data validation, compiles and reobfuscates the Forge JAR, then uploads the installable artifact. Push related changes together to avoid unnecessary builds.

To build locally with Java 17 and Gradle 8.8 installed: `gradle build`. The installable JAR is in `build/libs/`.

Operator-only diagnostic commands: `/elijah pouch`, `/elijah fire`, `/elijah unload`. Origins executes these internally through its power actions, so ordinary players do not need operator permissions to use their Origin. As in the other command-based Origins, Apoli's `executeCommand` permission level must remain at its default of 2 or higher.

Compile success verifies the Forge API integration, not in-game behavior in the complete modpack. The first gameplay check should cover the slot limit, firing at 1 and 9 powder, recoil, effects, and survival respawn.
