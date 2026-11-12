import os
import zipfile

src = os.path.join('smart-gateway', 'src', 'main', 'resources', 'static')
out = 'smartmes-static-demo.zip'

skip = {'.styles.backup-before-cleanup.css'}
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(src):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src).replace(os.sep, '/')
            if rel in skip:
                continue
            z.write(full, rel)
            print(rel)

print('zip:', out, os.path.getsize(out), 'bytes')
