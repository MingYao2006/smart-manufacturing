# 待办：更新服务器静态文件

> **状态：待办（未执行）**
> **触发时机：所有缺陷解决完之后，把文件一起替换**
> 记录于 2026-11-12。当前服务器上的静态文件仍是旧版，本次前端改动只落在仓库与本机，尚未上传。

## 要传的文件

`smartmes-static-demo.zip`（700 KB，位于 `deploy/` 下，由 `deploy/make-static-zip.py` 生成，已含本次改动）

包内 8 个文件，其中改动的是 `app.js` 与 `styles.css`：

```
index.html  app.js  api.js  data.js  styles.css  favicon.svg  assets/app-bg.png  assets/run-card.jpg
```

已校验包内 `app.js` 含 `switchTravelerOrder`、`styles.css` 含 `traveler-tabs`，确认是新版。

## 更新步骤（1Panel 文件管理器）

1. 登录 1Panel → **文件管理**，进入站点根目录：

   ```
   /opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index
   ```

   （若当初按文档用了替代路径 `/opt/1panel/apps/openresty/openresty/root/`，就进那个目录。）

2. 上传 `smartmes-static-demo.zip`，**解压到当前目录**，覆盖同名文件
   （重点是 `app.js`、`styles.css`；其它文件覆盖也无影响）。

3. 确认目录里 `app.js` 的修改时间已变成刚才，或直接用下面命令核对（SSH 里执行）：

   ```bash
   ROOT=/opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index
   ls -la "$ROOT"
   grep -c "switchTravelerOrder" "$ROOT/app.js"     # 应输出 >= 1
   grep -c "traveler-tabs" "$ROOT/styles.css"        # 应输出 >= 1
   ```

4. **不用重启 OpenResty、不用 reload** —— 静态文件即时生效。
   用户端按 `Ctrl+F5` 强制刷新一次即可（避免浏览器缓存旧 `app.js`）。

5. 本机后端和 frp 隧道**都不用动**：这次只改了前端静态资源，没有动任何 jar 或 API。

## 两点提醒

- 若刷新后仍是旧界面，多半是浏览器缓存：开无痕窗口验证一次，
  或在 1Panel 里给静态资源加 `Cache-Control: no-cache`（只影响开发期，可选）。
- 之前同步过的 `target/classes/static` 是**本机直连 9000 调试**用的那份，
  跟服务器上的静态文件是两套；服务器只看上传的 zip。

## 前置条件

- [ ] 所有缺陷已解决
- [ ] 前端改动已重新跑 `make-static-zip.py`，zip 为最终版
- [ ] 已确认服务器上站点根目录的实际路径
- [ ] 执行后按上面第 3 步核对，并强制刷新一次页面
