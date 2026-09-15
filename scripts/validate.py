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
assert "consumeClick()" in client
assert "case 7 -> DomainAbilities.activate(player)" in network
for field in ("pirateOrigin", "crewResource", "bloodResource", "bloodActiveWindow"):
    assert field in pouch
for method in ("activateBloodRush(ServerPlayer player)", "activateHunt(ServerPlayer player)",
               "activateWings(ServerPlayer player)"):
    assert method in blood
assert "state.bloodResource" in blood
assert "pouch.crewResource" in java
assert "FRAILTY_ARMOR_ID" in pirate and "onFallDamage" in pirate

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

print(f"Validated {len(parsed)} JSON resources and the Java-owned ability/domain contract.")
