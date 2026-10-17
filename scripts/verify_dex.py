#!/usr/bin/env python3
"""Fail if a DEX has unsorted string_ids, or lacks the expected C2 domain.

Why this exists: an APK was once byte-patched (shadow-v2 -> cfg-relay) without
re-sorting string_ids. The DEX spec requires the table sorted, ART's verifier
rejects an unsorted one, and the process dies instantly on class load.
A clean build from source always produces a sorted table; this guard makes
shipping a hand-patched DEX impossible.

Ordering is by UTF-16 code units, NOT by raw bytes and NOT by decoded str.
Decoded-str comparison false-positives on lone surrogates; raw-byte comparison
false-positives on MUTF-8 strings whose byte order differs from code-unit order.
"""
import struct
import sys
import zipfile

DOMAIN = b"cfg-relay.marwannaili-23-07.workers.dev"


def uleb(dex, off):
    result = 0
    shift = 0
    while True:
        b = dex[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            break
        shift += 7
    return result, off


def mutf8_to_utf16(data):
    """Decode modified UTF-8 to a tuple of UTF-16 code units."""
    units = []
    i = 0
    n = len(data)
    while i < n:
        c = data[i]
        if c < 0x80:
            units.append(c)
            i += 1
        elif (c & 0xE0) == 0xC0:
            units.append(((c & 0x1F) << 6) | (data[i + 1] & 0x3F))
            i += 2
        elif (c & 0xF0) == 0xE0:
            units.append(
                ((c & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)
            )
            i += 3
        else:
            units.append(c)
            i += 1
    return tuple(units)


def main():
    path = sys.argv[1]
    dex = zipfile.ZipFile(path).read("classes.dex")
    count, off = struct.unpack("<II", dex[56:64])
    print("string_ids: count=%d off=%d" % (count, off))

    keys = []
    for i in range(count):
        data_off = struct.unpack("<I", dex[off + 4 * i: off + 4 * i + 4])[0]
        utf16_len, p = uleb(dex, data_off)
        end = dex.index(b"\x00", p)
        units = mutf8_to_utf16(dex[p:end])
        if len(units) != utf16_len:
            print("WARN: idx %d declared %d units, decoded %d" % (i, utf16_len, len(units)))
        keys.append(units)

    bad = [(i - 1, i) for i in range(1, count) if keys[i] < keys[i - 1]]
    print("out-of-order pairs:", len(bad))
    for a, b in bad[:10]:
        print("   idx %d > idx %d" % (a, b))

    has_domain = DOMAIN in dex
    print("C2 domain present:", has_domain)

    failures = []
    if bad:
        failures.append("string_ids unsorted -> ART verifier will reject this APK")
    if not has_domain:
        failures.append("C2 domain missing from DEX")
    if failures:
        for f in failures:
            print("FAIL: " + f)
        return 1
    print("PASS: DEX valid, domain baked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
