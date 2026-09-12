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
print('Validated all power commands and the eight distinct active keybinds.')
