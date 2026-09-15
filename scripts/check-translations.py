#!/usr/bin/env python3
"""Validate Android XML, uniqueness, Italian coverage and printf/plural contracts."""
import re
import sys
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/res'
errors = []

def read(folder):
    result = {}
    for path in sorted(folder.glob('*.xml')):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            errors.append(f'{path}: invalid XML: {exc}')
            continue
        for item in root:
            if item.tag not in {'string', 'plurals', 'string-array'}:
                continue
            key = item.get('name')
            if key in result:
                errors.append(f'{path}: duplicate resource {key}')
            result[key] = item
    return result

def placeholders(text):
    # Android Formatter: position, flags, width, precision, date/time, conversion.
    matches = re.finditer(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT])?([a-zA-Z%])', text)
    sequential = 0
    tokens = []
    for m in matches:
        if m[3] in ('%', 'n'):
            continue
        sequential += 1
        tokens.append((int(m[1]) if m[1] else sequential, (m[2] or '') + m[3]))
    return Counter(tokens)

all_resources = {folder.name: read(folder) for folder in ROOT.glob('values*') if folder.is_dir()}
for folder in (ROOT.parents[1] / 'upstreamLocales/res').glob('values*'):
    all_resources['upstream/' + folder.name] = read(folder)
base, italian = all_resources['values'], all_resources['values-it']
required = {k: v for k, v in base.items() if v.get('translatable') != 'false'}
for key, original in required.items():
    translated = italian.get(key)
    if translated is None:
        errors.append(f'Italian missing: {key}')
        continue
    if original.tag != translated.tag:
        errors.append(f'Italian type mismatch: {key}')
        continue
    pairs = [(original, translated)]
    if original.tag == 'plurals':
        en = {x.get('quantity'): x for x in original}
        it = {x.get('quantity'): x for x in translated}
        if not {'one', 'other'} <= it.keys():
            errors.append(f'Italian plural forms missing: {key}')
        pairs = [(en.get(q, en['other']), value) for q, value in it.items()]
    elif original.tag == 'string-array':
        if len(original) != len(translated):
            errors.append(f'Italian array length mismatch: {key}')
        pairs = list(zip(original, translated))
    for a, b in pairs:
        atext, btext = ''.join(a.itertext()), ''.join(b.itertext())
        if not btext.strip():
            errors.append(f'Italian empty: {key}')
        if placeholders(atext) != placeholders(btext):
            errors.append(f'Italian placeholder mismatch: {key}: {placeholders(atext)} != {placeholders(btext)}')
for key in italian.keys() - base.keys():
    errors.append(f'Italian unknown resource: {key}')
coverage = len(required.keys() & italian.keys()) / len(required) * 100
print(f'Italian: {len(required.keys() & italian.keys())}/{len(required)} resources ({coverage:.1f}%). Checked {len(all_resources)} resource folders.')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print('PASS: XML, duplicate keys, coverage, plural forms and placeholders')
