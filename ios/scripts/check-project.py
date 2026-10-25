"""Portable structural checks; actual Swift compilation and tests require Xcode."""
import json
import plistlib
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
info = plistlib.loads((ROOT / 'DictaLab/Info.plist').read_bytes())
server = urlparse(info['DictaLabServerURL'])
assert server.scheme == 'https' and server.hostname and not server.username and not server.password
assert not info.get('NSAppTransportSecurity', {}).get('NSAllowsArbitraryLoads')
assert not info.get('UIBackgroundModes'), 'Do not claim background audio in the personal MVP'
project = (ROOT / 'DictaLab.xcodeproj/project.pbxproj').read_text(encoding='utf-8')
assert 'IPHONEOS_DEPLOYMENT_TARGET = 16.0' in project
assert 'com.apple.product-type.application' in project
assert 'com.apple.product-type.bundle.unit-test' in project
for file in (ROOT / 'DictaLab').glob('*.swift'):
    assert f'path = {file.name};' in project, f'{file.name} is missing from the Xcode project'
    source = file.read_text(encoding='utf-8')
    assert 'WKWebView' not in source and 'import WebKit' not in source
    assert '\ufffd' not in source
assert 'DictaLabTests.swift' in project
ids = re.findall(r'^\s*([A-F0-9]{24}) = \{isa =', project, re.M)
assert len(ids) == len(set(ids)), 'Duplicate Xcode object IDs'
scheme = ET.parse(ROOT / 'DictaLab.xcodeproj/xcshareddata/xcschemes/DictaLab.xcscheme')
assert len(scheme.findall('.//TestableReference')) == 1
for ref in scheme.findall('.//BuildableReference'):
    assert ref.attrib['BlueprintIdentifier'] in ids

assets = ROOT / 'DictaLab/Assets.xcassets'
for manifest in assets.rglob('Contents.json'):
    content = json.loads(manifest.read_text())
    for image in content.get('images', []):
        if 'filename' in image:
            assert (manifest.parent / image['filename']).is_file()
icon = (assets / 'AppIcon.appiconset/AppIcon.png').read_bytes()
assert icon[:8] == b'\x89PNG\r\n\x1a\n'
width, height, depth, color = struct.unpack('>IIBB', icon[16:26])
assert (width, height) == (1024, 1024) and color == 2, 'App icon must be 1024px RGB without alpha'

if len(sys.argv) > 1:
    with zipfile.ZipFile(sys.argv[1]) as archive:
        assert archive.testzip() is None
        packaged = plistlib.loads(archive.read('Payload/DictaLab.app/Info.plist'))
        assert packaged['CFBundleIdentifier'] == 'com.dictalab.personal'
        assert packaged['DictaLabServerURL'] == info['DictaLabServerURL']
        assert packaged['CFBundleSupportedPlatforms'] == ['iPhoneOS']
        binary = archive.read('Payload/DictaLab.app/DictaLab')
        assert binary[:4] == b'\xcf\xfa\xed\xfe', 'Expected a 64-bit Mach-O device executable'
        assert struct.unpack('<I', binary[4:8])[0] == 0x0100000C, 'Expected arm64'
        assert not any(name.endswith(('.mobileprovision', '.p12', '.pem', '.key')) for name in archive.namelist())
    print('Verified arm64 iPhone IPA structure and HTTPS configuration.')
print('Project, scheme, icon, native UI and HTTPS configuration checks passed.')
