"""Validate resource syntax and the Java-owned ability contract."""
from pathlib import Path
import json
import tomllib

root = Path(__file__).resolve().parents[1]
resources = root / "src/main/resources"
parsed = {str(p.relative_to(resources)): json.loads(p.read_text())
          for p in resources.rglob("*.json")}
json.loads((resources / "pack.mcmeta").read_text())
tomllib.loads((resources / "META-INF/mods.toml").read_text())

origin = parsed["data/elijah/origins/pirate.json"]
assert origin["powers"] == ["elijah:pirate_marker"]
assert "elijah:pirate_marker" in origin["powers"]
assert "elijah:pirate" in parsed["data/origins/origin_layers/origin.json"]["origins"]

domain = parsed["data/elijah/dimension/drowned_domain.json"]
assert domain["generator"]["settings"]["layers"] == [
    {"height": 1, "block": "minecraft:bedrock"},
    {"height": 62, "block": "minecraft:sandstone"},
    {"height": 2, "block": "minecraft:water"},
]
assert domain["generator"]["settings"]["biome"] == "minecraft:the_void"
sounds = parsed["assets/elijah/sounds.json"]
assert sounds["requiem"]["sounds"] == [{"name": "elijah:requiem", "stream": True}]
assert (resources / "assets/elijah/sounds/requiem.ogg").exists()

java = (root / "src/main/java/com/jamesrenrold/elijah/ElijahPirate.java").read_text()
network = (root / "src/main/java/com/jamesrenrold/elijah/AbilityNetwork.java").read_text()
client = (root / "src/main/java/com/jamesrenrold/elijah/client/ClientSetup.java").read_text()
domain_java = (root / "src/main/java/com/jamesrenrold/elijah/DomainAbilities.java").read_text()
blood = (root / "src/main/java/com/jamesrenrold/elijah/BloodAbilities.java").read_text()
pouch = (root / "src/main/java/com/jamesrenrold/elijah/PowderPouch.java").read_text()
pirate = (root / "src/main/java/com/jamesrenrold/elijah/PirateAbilities.java").read_text()
projectile = (root / "src/main/java/com/jamesrenrold/elijah/FlintlockBall.java").read_text()

assert "AbilityNetwork.register()" in java
assert "isPirate(ServerPlayer player)" in java
assert "AbilityNetwork.send(ability)" in client
assert "mapping.isDown()" in client
assert "KEY_WAS_DOWN" in client
assert "DOMAIN_MUSIC_DELAY_TICKS = 5 * 20" in client
assert "DOMAIN_TICKS = 48 * 20" in domain_java
assert "SunbeamEntity" in domain_java
assert 'getMethod("setTarget", LivingEntity.class)' in domain_java
assert "sunbeam.moveTo(aim.x, aim.y, aim.z" in domain_java
assert 'new ResourceLocation("irons_spellbooks", "sunbeam")' in domain_java
assert "getConstructor(EntityType.class, Level.class)" in domain_java
assert "SUNBEAM_START_TICKS = 12 * 20" in domain_java
assert "SUNBEAM_DAMAGE_SCALE = 0.75F" in domain_java
assert "WATER_CANNONBALLS_PER_VOLLEY = 4" in domain_java
assert "Native CBC ammunition is restricted to the sand ring" in domain_java
assert "case 7 -> DomainAbilities.activate(player)" in network
assert not (resources / "data/elijah/powers/pouch_lifecycle.json").exists()
assert "unload_later" not in java and "clearTransient" not in domain_java and "clearTransient" not in blood
for field in ("pirateOrigin", "crewResource", "bloodResource", "bloodActiveWindow"):
    assert field in pouch
for method in ("activateBloodRush(ServerPlayer player)", "activateHunt(ServerPlayer player)",
               "activateWings(ServerPlayer player)"):
    assert method in blood
assert "state.bloodResource" in blood
assert "pouch.crewResource" in java
assert "ElijahPouchState" in java
assert "ElijahPirateOwner" in java
assert "PLAYER_STATES" in java and "public static PowderPouch state(ServerPlayer player)" in java
assert "public static void saveState(ServerPlayer player, PowderPouch state)" in java
assert "FRAILTY_ARMOR_ID" in pirate and "onFallDamage" in pirate

# Active ability resources keep their required Apoli entity-action bridge, while
# the actual gameplay remains authoritative in the Java command handlers.
for active_name in (
        "blood_hunt.json", "blood_rush.json", "blood_wings.json",
        "drowned_domain.json", "dirty_tactics.json", "flintlock.json",
        "powder_pouch.json", "undead_crew.json"):
    active = parsed[f"data/elijah/powers/{active_name}"]
    assert active.get("entity_action", {}).get("type") == "origins:execute_command"

# The domain is an entity transfer only. It must not edit, grant, revoke, or
# reset the player's Origin/power component.
for forbidden in ("power grant @s", "power remove @s", "power revoke @s",
                  "origin set @s", "stripPiratePowers", "restorePiratePowers",
                  "SavedResources", "PowerRepair"):
    assert forbidden not in domain_java
assert "player.teleportTo(domain, BEACH_SPAWN_X, BEACH_SPAWN_Y" in domain_java
assert "changeDimension(destination, directTeleporter)" in domain_java
assert "onDomainBlockBreak" in domain_java and "onDomainBlockPlace" in domain_java
assert "onDomainFluidPlace" in domain_java and "getAffectedBlocks().clear()" in domain_java
assert "CANNONBALLS_PER_VOLLEY = 25" in domain_java
assert "CBC_BARRAGE_VOLLEY_PERIOD = 1" in domain_java
assert "elijahDamage * 2.0F" in domain_java
assert "delayed_impact_fuze" in domain_java and "setExplosionCountdown" in domain_java
assert "smoke_shell" not in domain_java
assert "FIREWORK_ROCKET_BLAST" not in domain_java
assert "ParticleTypes.SMOKE" not in projectile
assert "double strength = 0.08D" in projectile

# No ability code may depend on the Forge capability that Connector can
# invalidate while moving a player between dimensions.
for java_file in (root / "src/main/java/com/jamesrenrold/elijah").glob("*.java"):
    text = java_file.read_text()
    assert "PowderPouch.CAPABILITY" not in text, java_file
assert "AttachCapabilitiesEvent" not in java

print(f"Validated {len(parsed)} JSON resources and the Java-owned ability/domain contract.")
