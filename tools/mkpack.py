#!/usr/bin/env python3
"""Unpack / list / pack the game's .mkh/.mkp archives (same format the app reads).
  mkpack.py list   <name>            (expects <name>.mkh and <name>.mkp)
  mkpack.py unpack <name> <outdir>
  mkpack.py pack   <indir> <name>    (writes <name>.mkh/.mkp)
Script files (*.src) are XOR 0xA7 inside the pack; use `decode`/`encode` for text editing:
  mkpack.py decode <file.src> <out.txt>     (-> UTF-8 text, handles Shift-JIS)
  mkpack.py encode <in.txt> <out.src>       (-> ';non encode' plain UTF-8, loadable by the app)
"""
import struct, sys, os
K = 0xA7A7A7A7
def xor(b): return bytes(x ^ 0xA7 for x in b)

def read_index(name):
    h = open(name + '.mkh', 'rb').read()
    pid, ver, hs, fs, cnt = struct.unpack('>I4sIII', h[:20])
    assert pid == 0x6d6b7000, 'not a pack'
    out = []; p = 32
    for _ in range(cnt):
        off, size = struct.unpack('>II', h[p:p+8]); off ^= K; size ^= K
        ln = h[p+8] ^ 0xA7; nm = xor(h[p+9:p+32])[:ln].decode('utf-8', 'replace')
        out.append((nm, off, size)); p += 32
    return out

def cmd_list(n):
    for nm, o, s in read_index(n): print(f'{nm}\t{o}\t{s}')

def cmd_unpack(n, d):
    os.makedirs(d, exist_ok=True)
    with open(n + '.mkp', 'rb') as f:
        for nm, o, s in read_index(n):
            f.seek(o); open(os.path.join(d, nm), 'wb').write(xor(f.read(s)))

def cmd_pack(d, n):
    files = sorted(os.listdir(d)); body = b''; ents = []
    base = 32 + 32 * len(files)
    for fn in files:
        data = xor(open(os.path.join(d, fn), 'rb').read())
        ents.append((fn, len(body), len(data))); body += data
    hdr = struct.pack('>I4sIII', 0x6d6b7000, b'1.00', 32 + 32 * len(files), len(body), len(files)) + b'\0' * 12
    for fn, o, s in ents:
        nb = fn.encode()[:23]
        hdr += struct.pack('>II', o ^ K, s ^ K) + bytes([len(nb) ^ 0xA7]) + xor(nb.ljust(23, b'\0'))
    open(n + '.mkh', 'wb').write(hdr)
    open(n + '.mkp', 'wb').write(body)

def cmd_decode(i, o):
    b = open(i, 'rb').read()
    if not b.startswith(b';non encode'): b = xor(b)
    try: t = b.decode('utf-8')
    except UnicodeDecodeError: t = b.decode('cp932')
    open(o, 'w', encoding='utf-8', newline='\r\n').write(t)

def cmd_encode(i, o):
    t = open(i, encoding='utf-8').read()
    if not t.startswith(';non encode'): t = ';non encode\r\n' + t
    open(o, 'wb').write(t.encode('utf-8'))

if __name__ == '__main__':
    a = sys.argv[1:]
    {'list': cmd_list, 'unpack': cmd_unpack, 'pack': cmd_pack, 'decode': cmd_decode, 'encode': cmd_encode}[a[0]](*a[1:])
