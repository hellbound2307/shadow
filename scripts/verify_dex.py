#!/usr/bin/env python3
"""Verify a SHADOW APK's DEX: string_ids sortedness + baked C2 domain.

Correctness note: the DEX spec orders string_ids by UTF-16 code unit order.
Modified UTF-8 (what DEX string data uses) is monotonic with UTF-16 code unit
order at the byte level, so comparing the RAW bytes is the right test.
Decoding to a Python str first gives FALSE POSITIVES on non-ASCII strings
(CESU-8 surrogate encoding) -- that bug flagged a known-good APK.
"""
import struct
import sys
import zipfile

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
dex = z.read('classes.dex')
n, off = struct.unpack('<II', dex[56:64])
print('apk: %s' % apk)
print('string_ids: count=%d off=%d' % (n, off))


def uleb(o):
    r = 0
    s = 0
    while True:
        b = dex[o]
        o += 1
        r |= (b & 0x7f) << s
        if not b & 0x80:
            break
        s += 7
    return r, o


def raw_at(i):
    """Return the raw modified-UTF-8 bytes of string_ids[i]."""
    o = struct.unpack('<I', dex[off + 4 * i:off + 4 * i + 4])[0]
    ln, o2 = uleb(o)
    return dex[o2:o2 + ln]


prev = None
bad = []
for i in range(n):
    s = raw_at(i)
    if prev is not None and s < prev:
        bad.append((i - 1, prev, i, s))
    prev = s

print('out-of-order pairs:', len(bad))
for b in bad:
    print('   idx %d=%r > idx %d=%r' % b)

dom = b'cfg-relay.marwannaili-23-07.workers.dev' in dex
print('C2 domain present:', dom)

ok = not bad and dom
print('RESULT:', 'PASS' if ok else 'FAIL')
sys.exit(0 if ok else 1)
