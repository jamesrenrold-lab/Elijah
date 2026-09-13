"""Check resource syntax, Origin references and packaging before the expensive Forge build."""
from pathlib import Path
import json
import tomllib

root = Path(__file__).resolve().parents[1]
resources = root / 'src/main/resources'
parsed = {str(p.relative_to(resources)): json.loads(p.read_text())
          for p in resources.rglob('*.json')}
json.loads((resources / 'pack.mcmeta').read_text())
tomllib.loads((resources / 'META-INF/mods.toml').read_text())
origin = parsed['data/elijah/origins/pirate.json']
for power in origin['powers']:
    namespace, path = power.split(':')
    assert f'data/{namespace}/powers/{path}.json' in parsed, f'Missing power: {power}'
for path, data in parsed.items():
    if '/powers/' in path and data['type'] == 'origins:active_self':
        assert data['entity_action']['type'] in ('origins:execute_command', 'origins:if_else', 'origins:and')
assert 'elijah:pirate' in parsed['data/origins/origin_layers/origin.json']['origins']
assert 'elijah:flintlock' in parsed['data/minecraft/tags/damage_type/is_projectile.json']['values']
assert parsed['data/elijah/damage_type/flintlock.json']['message_id'] == 'elijah.flintlock'
fall_resistance = parsed['data/elijah/powers/flintlock_fall_resistance.json']
assert fall_resistance['type'] == 'origins:modify_damage_taken'
assert fall_resistance['damage_condition']['type'] == 'origins:from_falling'
assert fall_resistance['modifier']['operation'] == 'multiply_base'
assert fall_resistance['modifier']['value'] == -0.85
print(f'Validated {len(parsed)} JSON resources, pack metadata, mod metadata and power references.')

# Fail before compilation if a power command or key is not actually wired up.
command_source = (root / 'src/main/java/com/jamesrenrold/elijah/ElijahPirate.java').read_text()
def walk(value):
    if isinstance(value, dict):
        if value.get('type') == 'origins:execute_command':
            command = value['command'].split()
            if command[0] == 'elijah':
                assert len(command) > 1 and f'Commands.literal("{command[1]}")' in command_source, value
        for child in value.values():
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)
for path, data in parsed.items():
    if '/powers/' in path:
        walk(data)
keys = [data['key']['key'] for path, data in parsed.items()
        if '/powers/' in path and data.get('type') == 'origins:active_self']
assert len(keys) == len(set(keys)), 'Active abilities share a key unexpectedly'
assert set(keys) == {
    'key.origins.primary_active', 'key.origins.secondary_active',
    'key.origins.tertiary_active', 'key.origins.quaternary_active',
    'key.origins.quinary_active', 'key.origins.senary_active',
    'key.origins.septenary_active', 'key.origins.octonary_active'
}
# Resource mutations that depend on Java-side acceptance must live behind the
# guarded server commands. Otherwise an Origins `and` action can spend/grant a
# resource even when the Java command rejects the ability.
assert parsed['data/elijah/powers/blood_rush.json']['entity_action'] == {
    'type': 'origins:execute_command', 'command': 'elijah blood_rush'
}
assert parsed['data/elijah/powers/blood_rush.json']['name'] == 'Cursed Form'
assert parsed['data/elijah/powers/blood_rush.json']['cooldown'] == 1
assert parsed['data/elijah/powers/blood_active_window.json']['max'] == 1
assert parsed['data/elijah/powers/blood_charge_gain.json']['entity_action']['type'] == 'origins:if_else'
assert parsed['data/elijah/powers/blood_overflow.json']['entity_action'] == {
    'type': 'origins:execute_command', 'command': 'elijah blood_overdrive'
}
crew_action = parsed['data/elijah/powers/undead_crew.json']['entity_action']['if_action']
assert crew_action == {'type': 'origins:execute_command', 'command': 'elijah crew'}
domain = parsed['data/elijah/powers/drowned_domain.json']
assert domain.get('cooldown') == 0 and domain.get('hud_render') == {'should_render': False}, \
    'Origins must never retain a cross-dimension cooldown; Java owns the visible cooldown'
assert 'elijah:domain_cooldown' not in origin['powers']
assert 'elijah:domain_cooldown_recharge' not in origin['powers']
assert 'data/elijah/powers/domain_cooldown.json' not in parsed
assert 'data/elijah/powers/domain_cooldown_recharge.json' not in parsed
domain_source = (root / 'src/main/java/com/jamesrenrold/elijah/DomainAbilities.java').read_text()
assert 'COOLDOWN_TICKS = 5 * 20' in domain_source and 'COOLDOWNS' in domain_source, \
    'Domain must use the rebuilt five-second Java cooldown'
assert 'COOLDOWNS.put(session.ownerId, now + COOLDOWN_TICKS)' in domain_source, \
    'Cooldown must begin only when session cleanup starts'
assert 'CANNONBALLS_PER_VOLLEY = 25' in domain_source, \
    'Domain must launch twenty-five shells per half-second volley'
assert 'volleyShot < CANNONBALLS_PER_VOLLEY' in domain_source
assert 'fireCannonBarrage(session, owner, target)' in domain_source
assert 'cannonball.shoot(direction.x, direction.y, direction.z, 9.0F, 0.0F)' in domain_source, \
    'Sky barrage speed regressed'
assert 'POWER_RESETS.put(session.ownerId, now + 10L)' in domain_source, \
    'Domain power must be refreshed after the return teleport'
assert '"power remove " + playerId + " elijah:drowned_domain"' in domain_source
assert '"power grant " + playerId + " elijah:drowned_domain elijah:pirate"' in domain_source
assert 'WATER_SPAWN_Y = 63.2D' in domain_source, 'Lagoon spawn height regressed'
assert 'setPersistenceRequired()' in domain_source and 'setLastHurtByMob(owner)' in domain_source
assert 'changeDimension(destination, directTeleporter)' in domain_source, \
    'Domain targets must use Forge dimension transfer'
for removed_echo_path in ('saveAsPassenger', 'loadEntityRecursive', 'target.discard()', 'originalTargetSnapshot'):
    assert removed_echo_path not in domain_source, f'Echo transfer path remains: {removed_echo_path}'
assert '* 0.55D' in domain_source, 'Cannon damage must use 55% current attack damage'
assert 'BEACH_SPAWN_Y = 67.0D' in domain_source, 'Raised crescent spawn height regressed'
assert 'BEACH_SPAWN_X = -34.0D' in domain_source, 'Target must spawn deep on the beach'
assert 'moveEntity(target, domain, WATER_SPAWN_X, WATER_SPAWN_Y' in domain_source, \
    'Target must begin in the lagoon'
assert 'player.teleportTo(domain, BEACH_SPAWN_X, BEACH_SPAWN_Y' in domain_source, \
    'Caster must begin on the raised sand arena'
assert 'shouldSuppressLifecycleUnload' in domain_source, 'Connector dimension-change guard is missing'
assert 'restoreTarget(session, server)' in domain_source, 'Guaranteed target restoration is missing'
assert 'buildLagoonStairs(level)' in domain_source, 'Lagoon access ramp is missing'
assert 'buildMirroredLagoonStairs(level)' in domain_source, 'Circular arena needs its mirrored ramp'
assert 'targetMissingTicks <= 20' in domain_source, 'Transient target lookup tolerance is missing'
assert 'ownerMismatchTicks <= 40' in domain_source, 'Dimension transition tolerance is missing'
assert 'recoverFinishedSessions(server)' in domain_source, 'Finished sessions can block later casts'
assert 'PENDING_ACTIVATIONS.add(player.getUUID())' in domain_source, \
    'Domain activation must finish its Origins callback before teleporting'
assert 'activateNow(player)' in domain_source, 'Deferred domain activation is not processed'
assert 'Drowned Domain closed (" + reason' in domain_source, 'Early-close diagnostics are missing'
assert 'DomainAbilities.shouldSuppressLifecycleUnload(player)' in command_source, \
    'Origin-loss callback is not guarded during Connector dimension transitions'
assert 'onServerStarted(ServerStartedEvent event)' in domain_source, 'Arena prebuild is missing'
assert 'ARENA_MARKER' in domain_source and 'if (arenaReady) return;' in domain_source, \
    'Arena must not be rebuilt on every cast'
assert 'age % 20L == 0L' in domain_source, 'Domain effect refresh is not throttled'
assert 'age % 10L == 0L' in domain_source, 'Target pathfinding refresh is not throttled'
assert 'buildOceanFoundation(level)' in domain_source, 'Two-layer ocean foundation migration is missing'
assert 'clearLegacyCannons(level' in domain_source, 'Old high-cost CBC cannon layout is not removed'
assert 'clearDomainProjectiles(session.domain)' in domain_source, 'Expired cannonballs must be purged at cleanup'
assert 'clearUninvitedMobs(session.domain, session.target)' in domain_source, \
    'Special-spawner mobs must be removed from active domains'
dimension = parsed['data/elijah/dimension/drowned_domain.json']['generator']['settings']['layers']
assert dimension == [
    {'height': 1, 'block': 'minecraft:bedrock'},
    {'height': 62, 'block': 'minecraft:sandstone'},
    {'height': 2, 'block': 'minecraft:water'},
], 'Domain generator must be sandstone topped by exactly two water blocks'
assert parsed['data/elijah/dimension/drowned_domain.json']['generator']['settings']['biome'] == 'minecraft:the_void', \
    'Domain biome must have no natural spawn table'
assert 'buildDistantIslands(level)' in domain_source, 'Distant dune islands are missing'
assert 'buildBillowedSail' in domain_source, 'Volumetric sails are missing'
assert 'cannonball.setNoGravity(true)' in domain_source, 'Reliable straight cannon trajectory regressed'
assert 'cannonball.setGuaranteedImpact(aim)' in domain_source, 'Cannon impact guarantee is missing'
assert 'target.getBoundingBox().getCenter()' in domain_source, 'Cannon aim must snapshot the target hitbox'
assert 'damage, 4.5F' in domain_source, 'Cannon blast radius must be 4.5 blocks'
assert 'scatterRadius' not in domain_source, 'Cannonballs must not scatter or home after firing'
assert 'Blocks.DIAMOND_BLOCK' in domain_source, 'Circular arena migration marker is missing'
assert 'boolean sandyRing' in domain_source, 'Complete circular sand arena is missing'
assert 'innerRadiusSquared' in domain_source, 'Circular barrier shell is missing'
projectile_source = (root / 'src/main/java/com/jamesrenrold/elijah/FlintlockBall.java').read_text()
assert 'Server-driven tracer particles' in projectile_source, 'Cannonball tracer visibility regressed'
assert 'closest.distanceToSqr(impact) <= 0.64D' in projectile_source, 'Cannon crossing check is missing'
assert 'isCannonball() && hit.getType() == HitResult.Type.BLOCK' in projectile_source, \
    'Domain cannonballs must phase through blocks to their recorded target point'
assert 'setBarrageEffects(boolean visualTracer, boolean explosionSound)' in projectile_source, \
    'High-volume domain barrage must throttle cosmetic packets'
assert 'ParticleTypes.LARGE_SMOKE' in projectile_source and 'ParticleTypes.POOF' in projectile_source, \
    'Batched blast cloud regressed'
assert '.updateInterval(2)' in command_source, 'Projectile network synchronization is not throttled'
blood_source = (root / 'src/main/java/com/jamesrenrold/elijah/BloodAbilities.java').read_text()
assert 'state.cursedFormActive = true' in blood_source
assert 'state.cursedFormActive = false' in blood_source
assert 'BLOOD_COOLDOWN_TICKS = 20 * 20' in blood_source
assert 'server.overworld().getGameTime()' in blood_source, 'Blood timers need a cross-dimension clock'
assert 'state.bloodHuntUntil > now' in blood_source and \
       'changeOriginResource(player, "elijah:blood_resource", 1)' in blood_source, \
    'Blood Hunt must add the second Curse point each second'
print('Validated all power commands and the eight distinct active keybinds.')
