#!/usr/bin/env python3
"""Qualifies an APK for 16 KB page-size devices: every arm64-v8a/x86_64 ELF must have PT_LOAD alignment >= 0x4000,
and uncompressed .so entries must be stored on 16 KB zip offsets. Usage: check-16kb.py app.apk"""
import struct, sys, zipfile

def load_aligns(data):
    if data[:4] != b'\x7fELF' or data[4] != 2:  # 64-bit only
        return None
    phoff, = struct.unpack_from('<Q', data, 0x20)
    phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        ptype, = struct.unpack_from('<I', data, off)
        if ptype == 1:
            align, = struct.unpack_from('<Q', data, off + 0x30)
            out.append(align)
    return out

def main(path):
    bad, checked = [], 0
    with zipfile.ZipFile(path) as z, open(path, 'rb') as raw:
        for info in z.infolist():
            if not info.filename.endswith('.so'):
                continue
            abi = info.filename.split('/')[1] if info.filename.startswith('lib/') else '?'
            data = z.read(info)
            aligns = load_aligns(data)
            if aligns is None:
                print(f"SKIP  {info.filename} (not ELF64: {abi})"); continue
            checked += 1
            ok_elf = all(a >= 0x4000 for a in aligns)
            ok_zip = True
            if info.compress_type == 0:
                raw.seek(info.header_offset + 26)
                n, m = struct.unpack('<HH', raw.read(4))
                ok_zip = (info.header_offset + 30 + n + m) % 0x4000 == 0
            print(f"{'OK   ' if ok_elf and ok_zip else 'FAIL '} {info.filename} align={[hex(a) for a in aligns]} stored={'yes' if info.compress_type == 0 else 'no'} zip16k={ok_zip}")
            if not (ok_elf and ok_zip): bad.append(info.filename)
    print(f"checked {checked} native libraries")
    if bad:
        print("16 KB qualification FAILED:", ", ".join(bad)); sys.exit(1)
    print("16 KB qualification passed")

main(sys.argv[1])
