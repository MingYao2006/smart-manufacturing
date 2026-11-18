# 发布工作台前端静态文件（务必按这个流程）

> 公网 `8.148.212.158` 的页面由**云端 1Panel / OpenResty 的静态目录**直接托管，
> 本机 jar 只提供 API（经 frp 隧道反代）。因此**改完前端后，重新打包 / 重启本机
> 后端都不会让公网页面变化** —— 必须把静态包覆盖到云端目录。
> 这条是反复出现「代码改了却看不到」的根因，务必按本文件执行。

## 一键打包（自动破缓存）

在项目根目录执行：

```powershell
python deploy/make-static-zip.py
```

脚本会：
1. 把 `smart-gateway/src/main/resources/static/` 复制到临时目录；
2. 按**文件内容**算 hash，把 `index.html` 里 `data.js / api.js / app.js / styles.css`
   的 `?v=` 自动刷新（CSS 也带版本号，否则旧 CSS 会让「无权限侧栏依然显示」）；
3. 把左下角版本标记改成 `v<hash> · 工作台`；
4. 产出 **`smartmes-static-upload.zip`**（项目根目录）。

> 不要再手工改 `?v=2026xxxx` —— 容易漏改 CSS 或某个 JS，浏览器就会吃旧缓存。

## 上传到云端

1. 登录 1Panel → **文件管理**，进入站点根目录：

   ```
   /opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index
   ```

   （若当初按文档用了替代路径 `/opt/1panel/apps/openresty/openresty/root/`，就进那个目录。）

2. 上传 `smartmes-static-upload.zip`，**解压到当前目录**，覆盖同名文件。

3. 核对（SSH）：

   ```bash
   ROOT=/opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index
   ls -la "$ROOT"
   grep -o "app.js?v=[a-f0-9]*" "$ROOT/index.html"       # 版本号应为本次 hash
   grep -c "travelerState" "$ROOT/app.js"                 # >=1（工作台组件）
   grep -c "rail-nav a\[hidden\]" "$ROOT/styles.css"      # >=1（侧栏隐藏兜底）
   ```

4. **不用重启 OpenResty、不用 reload**，静态文件即时生效。
   浏览器 **Ctrl+F5** 强刷；看左下角版本标记是否变成新 hash。

5. 本机后端 / frp 隧道都不用动（除非本次也改了后端接口）。

## 为什么要带版本号（缓存坑）

OpenResty 对静态文件用 ETag / Last-Modified 协商缓存，浏览器会缓存 JS/CSS。
`index.html` 刷新到新版、但 `app.js` 仍是旧缓存时，会出现：
- 侧栏「进货审批」入口在（新 index），但菜单不裁剪、点了打不开（旧 app.js 无 purchase 路由）；
- 旧 CSS 缺 `[hidden]` 规则时，`.rail-nav a { display:flex }` 会盖掉 JS 设的 `hidden`，
  导致所有角色都看到全部菜单。

内容 hash 版本号 + 前端 `updateNavigation` 内联 `style.display` 双保险解决这两点。

## 本机直连调试（可选）

`http://127.0.0.1:9000/` 会加载**本机 jar 内**的静态资源。
若要在本机也看到最新前端，需 `mvn -o -s .mvn/settings.xml clean package -DskipTests`
重新打 jar 并重启网关 —— 但这**不影响公网页面**，公网只认云端目录。

## 检查清单

- [ ] 前端改动已完成
- [ ] 执行 `python deploy/make-static-zip.py`，zip 为最终版
- [ ] 已确认云端站点根目录的实际路径
- [ ] 上传解压覆盖，按上面第 3 步核对版本号与关键字
- [ ] Ctrl+F5 强刷，左下角版本标记为新 hash
