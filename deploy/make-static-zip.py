"""
打包工作台前端静态文件，并自动给 index.html 里的脚本 / 样式引用加内容 hash 版本号。

用法（在项目根目录执行）：
    python deploy/make-static-zip.py
输出：
    smartmes-static-upload.zip   —— 上传到云端 OpenResty 静态目录解压覆盖即可

为什么要这个脚本（踩过的坑）：
    公网页面由云端 1Panel/OpenResty 的静态目录托管，本机 jar 只提供 API。
    改完前端若只重打包 jar、或只手动改 index.html 的 ?v=，浏览器会用旧的
    JS/CSS 缓存，表现为「代码明明改了、页面却不变」。
    这里按文件内容算短 hash 作为 ?v=，并把 index.html 里所有
    `./xxx.js?v=...` / `./xxx.css?v=...` 自动刷新，彻底免去手工改版本号。
    CSS 也必须带版本号：侧栏按角色隐藏依赖 CSS 的 [hidden] 规则，
    旧 CSS 缓存会让「无权限菜单依然显示」。
"""
import hashlib
import os
import re
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'smart-gateway', 'src', 'main', 'resources', 'static')
OUT = os.path.join(ROOT, 'smartmes-static-upload.zip')
STAGING = os.path.join(ROOT, '.tmp', 'smartmes-static-upload')
SKIP = {'.styles.backup-before-cleanup.css'}

# 需要带版本号破缓存的资源（相对 static 目录）
VERSIONED = ['index.html']  # index 本身不进 ?v=（它就是入口），由 HTTP 头/强刷控制
ASSETS = ['data.js', 'api.js', 'app.js', 'styles.css']


def short_hash(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()[:10]


def main():
    if not os.path.isdir(SRC):
        print('static dir not found:', SRC, file=sys.stderr)
        return 1

    # 1. 复制到暂存目录
    if os.path.isdir(STAGING):
        shutil.rmtree(STAGING)
    os.makedirs(STAGING, exist_ok=True)
    for root, _dirs, files in os.walk(SRC):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, SRC).replace(os.sep, '/')
            if rel in SKIP:
                continue
            dst = os.path.join(STAGING, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(full, dst)

    # 2. 计算各资源 hash，按内容生成本次版本串
    versions = {}
    for name in ASSETS:
        p = os.path.join(STAGING, name)
        versions[name] = short_hash(p) if os.path.isfile(p) else 'missing'
    bundle = hashlib.sha256(''.join(versions[a] for a in ASSETS).encode()).hexdigest()[:10]

    # 3. 重写 index.html 里的 ?v=...（无论原来带不带版本号都能匹配）
    index_path = os.path.join(STAGING, 'index.html')
    with open(index_path, 'r', encoding='utf-8-sig') as f:
        html = f.read()
    for name in ASSETS:
        # 匹配 ./data.js、./data.js?v=xxx 等
        pattern = re.compile(r'(\./' + re.escape(name) + r')(\?v=[^"\']*)?')
        html = pattern.sub(lambda m, n=name: m.group(1) + '?v=' + versions[n], html)
    # 版本可见标记：左下角 rail-version，便于一眼确认浏览器加载的是不是新包
    html = re.sub(r'(<span class="rail-version">)[^<]*(</span>)',
                  lambda m: m.group(1) + 'v' + bundle + ' · 工作台' + m.group(2), html)
    with open(index_path, 'w', encoding='utf-8') as f:
        f.write(html)

    # 4. 打包
    if os.path.isfile(OUT):
        os.remove(OUT)
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for root, _dirs, files in os.walk(STAGING):
            for f in sorted(files):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, STAGING).replace(os.sep, '/')
                z.write(full, rel)
                print(rel)

    print('bundle   :', bundle)
    for name in ASSETS:
        print('  %-12s %s' % (name, versions[name]))
    print('zip      :', OUT, os.path.getsize(OUT), 'bytes')
    print('')
    print('下一步：把 smartmes-static-upload.zip 上传到云端站点根目录解压覆盖，')
    print('      浏览器 Ctrl+F5 强刷，看左下角版本标记是否为 v' + bundle + '。')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
