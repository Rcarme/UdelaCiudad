from pathlib import Path
import ast, json, types, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'docs/entrega_2026/evidencias'

def isolated_function(path, name, scope):
    tree = ast.parse((ROOT/path).read_text(encoding='utf-8-sig'))
    node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == name)
    exec(compile(ast.Module(body=[node], type_ignores=[]), path, 'exec'), scope)
    return scope[name]

# Reproduce the Linux root normalization path without touching disks or Docker.
usage = types.SimpleNamespace(total=100*1024**3, used=40*1024**3, free=60*1024**3, percent=40)
psutil = types.SimpleNamespace(disk_partitions=lambda **kw: [types.SimpleNamespace(device='/dev/sda1', mountpoint='/', fstype='ext4')], disk_usage=lambda path: usage)
posix = types.SimpleNamespace(name='posix', getenv=lambda key, default=None: default, path=types.SimpleNamespace(isdir=lambda p: False, basename=lambda p:p))
disks = isolated_function('server/main.py','get_disks_data',{'psutil':psutil,'os':posix})()
assert disks == [], disks

source = (ROOT/'server/test_api.py').read_text(encoding='utf-8')
backend_tests = sum(isinstance(n,ast.FunctionDef) and n.name.startswith('test_') for n in ast.parse(source).body)
results=[]
for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    e=ET.parse(p).getroot()
    results.append({k:e.attrib.get(k) for k in ['name','tests','failures','errors','skipped','time']})
payload={'linux_root_reproduction':{'expected_root_entries':1,'actual_entries':len(disks),'result':'BUG REPRODUCED using isolated production function and simulated psutil'},'backend_test_functions':backend_tests,'android_junit':results}
(OUT/'verificaciones.json').write_text(json.dumps(payload,indent=2),encoding='utf-8')
print(json.dumps(payload,indent=2))
