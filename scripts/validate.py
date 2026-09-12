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
assert parsed['data/elijah/powers/blood_overflow.json']['entity_action'] == {
    'type': 'origins:execute_command', 'command': 'elijah blood_overdrive'
}
crew_action = parsed['data/elijah/powers/undead_crew.json']['entity_action']['if_action']
assert crew_action == {'type': 'origins:execute_command', 'command': 'elijah crew'}
domain = parsed['data/elijah/powers/drowned_domain.json']
assert 'cooldown' not in domain and 'hud_render' not in domain, \
    'Testing domain must not have an Origins cooldown'
assert 'elijah:domain_cooldown' not in origin['powers']
assert 'elijah:domain_cooldown_recharge' not in origin['powers']
assert 'data/elijah/powers/domain_cooldown.json' not in parsed
assert 'data/elijah/powers/domain_cooldown_recharge.json' not in parsed
domain_source = (root / 'src/main/java/com/jamesrenrold/elijah/DomainAbilities.java').read_text()
assert 'domain_cooldown' not in domain_source, 'Testing domain must not have a Java cooldown'
assert 'setPersistenceRequired()' in domain_source and 'setLastHurtByMob(owner)' in domain_source
assert 'target.discard();' in domain_source
assert domain_source.index('target.discard();') < domain_source.index('destination.addFreshEntity(recreated)'), \
    'Echo transfer order regressed: source must be removed before destination UUID registration'
assert '* 0.55D' in domain_source, 'Cannon damage must use 55% current attack damage'
assert 'BEACH_SPAWN_Y = 67.0D' in domain_source, 'Raised crescent spawn height regressed'
assert 'BEACH_SPAWN_X = -34.0D' in domain_source, 'Target must spawn deep on the beach'
assert 'shouldSuppressLifecycleUnload' in domain_source, 'Connector dimension-change guard is missing'
assert 'originalTargetSnapshot' in domain_source, 'Fallback target restoration snapshot is missing'
assert 'restoreTarget(session, server)' in domain_source, 'Guaranteed target restoration is missing'
assert 'buildLagoonStairs(level)' in domain_source, 'Lagoon access ramp is missing'
assert 'targetMissingTicks <= 20' in domain_source, 'Transient target lookup tolerance is missing'
assert 'ownerMismatchTicks <= 40' in domain_source, 'Dimension transition tolerance is missing'
assert 'pruneStaleSessions(server)' in domain_source, 'Stale sessions can block later casts'
assert 'Drowned Domain closed (" + reason' in domain_source, 'Early-close diagnostics are missing'
assert 'DomainAbilities.shouldSuppressLifecycleUnload(player)' in command_source, \
    'Origin-loss callback is not guarded during Connector dimension transitions'
assert 'clearLegacyArenaGeometry(level)' in domain_source, 'Legacy ships must be purged before rebuild'
assert 'buildDistantIslands(level)' in domain_source, 'Distant dune islands are missing'
assert 'buildBillowedSail' in domain_source, 'Volumetric sails are missing'
assert 'cannonball.setNoGravity(true)' in domain_source, 'Reliable straight cannon trajectory regressed'
assert 'cannonball.setGuaranteedImpact(aim)' in domain_source, 'Cannon impact guarantee is missing'
projectile_source = (root / 'src/main/java/com/jamesrenrold/elijah/FlintlockBall.java').read_text()
assert 'Server-driven tracer particles' in projectile_source, 'Cannonball tracer visibility regressed'
assert 'closest.distanceToSqr(impact) <= 0.64D' in projectile_source, 'Cannon crossing check is missing'
print('Validated all power commands and the eight distinct active keybinds.')
