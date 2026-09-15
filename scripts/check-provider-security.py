#!/usr/bin/env python3
"""Check credential plumbing and scan tracked sources/APKs without printing any suspected secret."""
import pathlib
import re
import subprocess
import sys
import zipfile
root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/com/grinch/rivo4/controller/identification'
secrets = (source / 'ProviderSecrets.kt').read_text()
assert 'AndroidKeyStore' in secrets and 'AES/GCM/NoPadding' in secrets
assert 'noBackupFilesDir' in secrets and 'getSharedPreferences' not in secrets
for file in source.glob('*.kt'):
    text = file.read_text()
    assert not re.search(r'\bLog\.|println\(|printStackTrace\(', text), f'Unexpected logging in {file.name}'
transport = (source / 'DirectProviders.kt').read_text()
assert 'value("proxy")' not in transport and 'Bearer' not in transport
assert 'ipqualityscore.com' not in transport and 'instanceFollowRedirects = false' in transport
build = (root / 'app/build.gradle').read_text()
assert not re.search(r"buildConfigField.*(?:API_KEY|IPQS_KEY|GOOGLE_KEY)", build)
# Production-style Google keys; no match text is ever emitted.
pattern = re.compile(rb'AIza[0-9A-Za-z_-]{35}')
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).split(b'\0')
for name in files:
    if name:
        path = root / name.decode()
        if path.is_file():
            assert not pattern.search(path.read_bytes()), 'Potential credential in tracked source (redacted)'
for apk in sys.argv[1:]:
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            assert not pattern.search(archive.read(entry)), 'Potential credential in APK (redacted)'
print('PASS: Keystore/no-backup plumbing, no transport logging, no embedded Google key patterns.')
print('Personal keys were not supplied; runtime encryption is covered by the device instrumentation suite.')
