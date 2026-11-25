#!/usr/bin/env python3
"""Copy your purchased game data into the app so it is embedded in the APK.
usage: import_data.py <folder-with-your-.mkh/.mkp-files>
Copies every *.mkh / *.mkp (and loose *.snd/*.ogg/*.resogg) into app/src/main/assets/data/."""
import os, shutil, sys
src = sys.argv[1]; dst = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'data')
os.makedirs(dst, exist_ok=True); n = 0
for root, _, fs in os.walk(src):
    for f in fs:
        if f.lower().endswith(('.mkh', '.mkp', '.snd', '.ogg', '.resogg')):
            shutil.copy2(os.path.join(root, f), os.path.join(dst, f)); n += 1
print(n, 'files copied to', os.path.abspath(dst))
