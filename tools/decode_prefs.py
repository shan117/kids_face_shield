"""Minimal androidx DataStore Preferences proto decoder (read-only, for test verification).

PreferenceMap{ map<string,Value> preferences=1 }  MapEntry{ key=1 string, value=2 Value }
Value oneof: 1 bool, 2 float, 3 int32, 4 int64, 5 string, 6 StringSet{strings=1 repeated}, 7 double, 8 bytes
"""
import sys
import struct


def read_varint(b, i):
    shift = 0
    val = 0
    while True:
        byte = b[i]
        i += 1
        val |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return val, i
        shift += 7


def read_field(b, i):
    tag, i = read_varint(b, i)
    fnum, wtype = tag >> 3, tag & 7
    if wtype == 0:
        v, i = read_varint(b, i)
    elif wtype == 2:
        ln, i = read_varint(b, i)
        v = b[i:i + ln]; i += ln
    elif wtype == 5:
        v = struct.unpack('<f', b[i:i + 4])[0]; i += 4
    elif wtype == 1:
        v = struct.unpack('<d', b[i:i + 8])[0]; i += 8
    else:
        raise ValueError(f"wtype {wtype}")
    return fnum, wtype, v, i


def parse_value(b):
    out = {}
    i = 0
    while i < len(b):
        fnum, wtype, v, i = read_field(b, i)
        if fnum == 1:
            out['type'], out['val'] = 'bool', bool(v)
        elif fnum == 3:
            out['type'], out['val'] = 'int', v
        elif fnum == 4:
            out['type'], out['val'] = 'long', v
        elif fnum == 5:
            out['type'], out['val'] = 'string', v.decode('utf-8', 'replace')
        elif fnum == 6:
            strs = []
            j = 0
            while j < len(v):
                fn, wt, vv, j = read_field(v, j)
                if fn == 1:
                    strs.append(vv.decode('utf-8', 'replace'))
            out['type'], out['val'] = 'set', strs
        elif fnum == 7:
            out['type'], out['val'] = 'double', v
        else:
            out['type'], out['val'] = f'f{fnum}', v
    return out


def parse_prefs(b):
    res = {}
    i = 0
    while i < len(b):
        fnum, wtype, v, i = read_field(b, i)
        if fnum == 1 and wtype == 2:  # a MapEntry
            j = 0
            key = None
            valmsg = None
            while j < len(v):
                fn, wt, vv, j = read_field(v, j)
                if fn == 1:
                    key = vv.decode('utf-8', 'replace')
                elif fn == 2:
                    valmsg = vv
            if key is not None:
                res[key] = parse_value(valmsg) if valmsg else {}
    return res


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else ".adb_shots/child_prefs.pb"
    keys = sys.argv[2:] if len(sys.argv) > 2 else None
    data = open(path, "rb").read()
    prefs = parse_prefs(data)
    for k in sorted(prefs):
        if keys and not any(s in k for s in keys):
            continue
        print(f"{k} = {prefs[k].get('val')!r} ({prefs[k].get('type')})")
