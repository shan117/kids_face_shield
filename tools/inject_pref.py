"""Append a single boolean preference to a DataStore Preferences .pb (valid repeated MapEntry → last wins).
Usage: inject_pref.py IN.pb OUT.pb key true|false
"""
import sys

def main():
    inp, outp, key, val = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    kb = key.encode()
    value = b'\x08' + (b'\x01' if val == 'true' else b'\x00')          # Value.boolean (field1 varint)
    mapentry = b'\x0a' + bytes([len(kb)]) + kb + b'\x12' + bytes([len(value)]) + value
    top = b'\x0a' + bytes([len(mapentry)]) + mapentry                  # PreferenceMap.preferences (field1)
    data = open(inp, 'rb').read() + top
    open(outp, 'wb').write(data)
    print(f"appended {key}={val}, {len(top)} bytes; total {len(data)}")

main()
