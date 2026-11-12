# 混合部署：静态常驻 + 开机自动接管（frp 隧道）

目标：**电脑没开 → 用户看到静态演示页；电脑开了 → 用户操作真实落库到本机 MySQL**。
用户全程访问同一个地址 `http://服务器公网IP/`，不需要手动切换。

## 架构

```
用户浏览器 ──80──> OpenResty (阿里云 ECS 1.8G)
                     │
        ┌────────────┴────────────┐
        │ /  静态文件（永远可用） │
        │ /system/** 等 5 类 API  │──反代──> 127.0.0.1:9000
        │ /actuator/health（探测）│              │
        └─────────────────────────┘        frps (服务器, ~15MB)
                                              ╎ frp 隧道（电脑开机才有）
                                         frpc (你的 Windows)
                                              │
                                         localhost:9000 网关
                                              └→ 5 服务 → 本机 MySQL
```

隧道断时反代返回 502，前端「连接守护」自动切演示模式并**保留登录 token**；
隧道恢复后自动切回 live 并刷新真实数据。

## 目录

```
deploy/
├── smartmes-static-demo.zip   ← 用这个！已含连接守护改动（前端 3 个文件）
├── frp/
│   ├── frps.toml / frps.ini  服务端（ECS）：TOML 给 v0.61，INI 给 0.4x 备用
│   ├── frpc.toml / frpc.ini  客户端（Windows）：同上
│   ├── smart-mes.full.conf   完整 server {}（推荐，OpenResty 配置文件直接用）
│   ├── frps.service          systemd 开机自启
│   ├── setup-frpc.ps1        ★ 第 4 步一键准备 + 安装（Windows）
│   └── install-frpc-task.ps1 Windows 登录自启（由 setup 调用）
├── openresty/mes-site.conf   OpenResty 合并片段（手工合并时用）
└── README-hybrid.md          本文档
```

> ⚠️ 之前那个 352MB 的 `smartmes-deploy.zip`（全套后端上服务器）**本方案不用**，
> 1.8G 内存跑不动。只传静态 zip。

---

## 第 0 步：清理服务器上之前的残留

```bash
# 这些是【宿主机】路径（1Panel 文件管理器 / SSH 里用）
ROOT=/opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index
sudo rm -f "$ROOT"/smartmes-deploy.zip "$ROOT"/app\\*.jar "$ROOT"/sql\\*.sql
sudo rm -rf /opt/smartmes
ls -la "$ROOT"        # 应只剩 404.html / index.html / stop/
```

> 容器内路径写作 `/www/sites/smart-mes/index`（配置文件里的 `root`），
> 由 compose 的 `./www:/www` 映射到上面这个宿主机目录。**宿主机根目录 `/` 下没有 `www`**，
> 别在文件管理器里找 `/www`。官方 compose 见
> [1Panel appstore openresty](https://github.com/1Panel-dev/appstore/tree/dev/apps/openresty/1.21.4.3-3-3-focal)。

若之前配过指向 `127.0.0.1:9000` 的 `mes-gateway` 反代，先删掉（网站 → 设置 → 反向代理）。

## 第 1 步：服务器装 frps

> ⚠️ **先确认是不是已经装过**。若 `sudo systemctl status frps --no-pager` 返回
> `Unit frps.service could not be found`，说明这一步**根本没做过**，7000 不通就是
> 必然的 —— 必须回到这里补做，不能跳到第 4 步。

```bash
# 0) 环境确认（复制粘贴即可）
uname -m                                   # 应为 x86_64
cat /etc/os-release | head -3
sudo systemctl status frps --no-pager      # 若 not found，继续往下装

# 1) 下载 v0.72.0（与本机 Windows 版一致）
cd /opt
sudo wget https://github.com/fatedier/frp/releases/download/v0.72.0/frp_0.72.0_linux_amd64.tar.gz
sudo tar -xzf frp_0.72.0_linux_amd64.tar.gz
sudo mv frp_0.72.0_linux_amd64 /opt/frp
ls -la /opt/frp                            # 应有 frps / frps.toml / LICENSE
```

> GitHub 连不上时：在你自己电脑上下载 `frp_0.72.0_linux_amd64.tar.gz`，
> 用 1Panel 文件管理器上传到 `/opt/`，再 `tar -xzf` 解压。

把仓库 `frp/frps.toml` 传到 `/opt/frp/frps.toml`（**覆盖**掉压缩包自带的示例文件）。
仓库里已经填好了随机值，直接传即可：

| 项 | 值 | 说明 |
|---|---|---|
| `auth.token` | `7bHQX4qqK5Rb-yB8pphA8Qv813blSnAnfaLf2wZiJ8c` | **必须与 `frpc.toml` 完全一致** |
| `webServer.password` | `YDYuPhkWXUsTyWUWSyDR1A4p` | frps 控制台密码，与 token 分开 |

> 这两个是本次部署生成的随机值，建议上线后自己再换一遍：
> ```bash
> openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
> ```
> 换完记得**服务器和 Windows 两边同时改**，只改一边会报 `authentication failed`。

> **版本匹配**：服务器与 Windows 客户端都用 **v0.72.0**（本机实际解压的就是它）。
> 0.5x+ 一律只认 **TOML**；仓库 `frps.toml` / `frpc.toml` 与 `frps.service`
> 里的 `-c /opt/frp/frps.toml` 已经配套。同目录 `.ini` 是 0.4x 老版本备用件，
> **不要混用**。

```bash
# 2) 安装 systemd 服务（把仓库 frp/frps.service 也传到 /opt/frp/ 后执行）
sudo cp /opt/frp/frps.service /etc/systemd/system/frps.service
sudo systemctl daemon-reload
sudo systemctl enable --now frps

# 3) 立刻验证
sudo systemctl status frps --no-pager      # 期望 active (running)
ss -tlnp | grep 7000                       # 期望 0.0.0.0:7000
journalctl -u frps -n 20 --no-pager        # 有报错就看这里
```

若 `status` 不是 `active`，最常见三个原因：
`/opt/frp/frps.toml` 没传（还是压缩包自带示例）、`frps` 没有执行权限
（`sudo chmod +x /opt/frp/frps`）、或配置文件语法错（`journalctl` 里会有
`parse config error`）。

⚠️ **安全组（不做这一步，后面全白做）**：
- 放行入方向 **TCP 7000**（frp 通信；建议授权对象填你自己的出口 IP，不要 `0.0.0.0/0`）
- 放行 **TCP 80**（用户访问）
- **9000 千万不要放行到公网** —— `proxyBindAddr=127.0.0.1` 已保证它只监听回环，安全组也别开

配置路径：阿里云控制台 → ECS 实例 → **安全组** → 配置规则 → **入方向** → 手动添加
（TCP / `7000/7000` / 授权对象填你家出口 IP，在你电脑上 `curl -s ifconfig.me` 查）。
**不需要重启 frps**，放行立即生效。

> 实测排查链（照这个顺序能一次定位）：
> `systemctl status frps` → `active (running)` ✔
> `ss -tlnp | grep 7000` → `LISTEN *:7000` ✔（服务在听所有地址）
> `firewall-cmd --list-all` → `FirewallD is not running` ✔（系统防火墙没拦）
> Windows `Test-NetConnection ... -Port 7000` → **False**
> ⇒ 服务侧三层全通，唯一剩下的就是 **ECS 安全组入方向没放行 7000**。
> 这是最常见的漏项，因为安全组在宿主机外层，服务器本机自测永远测不到它。

## 第 2 步：服务器配 OpenResty

1Panel → 网站 → 你的站点 → 设置 → **配置文件**，推荐直接用 `openresty/smart-mes.full.conf` 整段替换（它是完整 `server {}`，`root` / `index` / `location /` 各只有一条）。

若要手工合并，只用 `openresty/mes-site.conf` 里的 **location 段**插进已有 `server {}`，**不要**把里面的 `root` / `index` 一起复制进去。报 `"root" directive is duplicate` 就是因为整段粘了（详见 §排障）。

或（不推荐，容易生成重复 location）在「反向代理」页面逐条添加，目标统一 `http://127.0.0.1:9000`：

| 代理路径 | 用途 |
|---|---|
| `/actuator/health` | 前端连接守护探测 |
| `/system` | 登录、用户 |
| `/product-service` | 基础资料 |
| `/production-service` | 工单、报工 |
| `/inventory-service` | 库存 |
| `/quality-service` | 质检、追溯 |

静态页仍由 `location /` 本地托管，**不要**把 `/` 也反代掉。

保存后 OpenResty 会 reload。此时 `curl -I http://127.0.0.1/` 应返回静态页 200，
`curl -I http://127.0.0.1/actuator/health` 应返回 502（隧道还没建，符合预期）。

> 1Panel 官方 OpenResty 容器是 `network_mode: host`，与宿主机共用网络栈，
> 所以配置里的 `127.0.0.1:9000` 就是宿主机上 frps 监听的地址，直接可达，
> 不需要改成 `172.17.0.1` 之类的网桥网关。若你手动改过 compose 的网络模式，
> `docker inspect 1Panel-openresty-* | grep NetworkMode` 确认一下。

## 第 3 步：上传静态页

把 `smartmes-static-demo.zip` 传到**宿主机**的站点根目录：

```
/opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index/
```

在 1Panel 文件管理器里依次点：`opt` → `1panel` → `apps` → `openresty` → `openresty` → `www` → `sites` → `smart-mes` → `index`，上传后**解压到当前目录**，`index.html` 覆盖 1Panel 自带的欢迎页。

> 配置文件 `root /www/sites/smart-mes/index;` 是**容器内**路径，由 compose 的
> `./www:/www` 映射到上面这个宿主机目录。两者字符串不同，是正常的。

确认：

```bash
ROOT=/opt/1panel/apps/openresty/openresty/www/sites/smart-mes/index
ls -la "$ROOT"
# 应有 index.html app.js api.js data.js styles.css favicon.svg assets/
```

**更省事的替代方案**：1Panel 默认站点根目录 `/opt/1panel/apps/openresty/openresty/root/` 一定存在（对应容器内 `/usr/share/nginx/html`），也可以把静态文件放这里，然后把配置文件里**唯一那条** `root` 改成 `/usr/share/nginx/html`。注意 `/opt/1panel/.../root` 是宿主机路径，**不能**写进容器内的 `root` 指令。

> zip 内路径是正斜杠（`assets/app-bg.png`），不会再出现文件名带 `\` 的问题。

## 第 4 步：你电脑装 frpc + 开机自启

> **版本**：本机实际解压的是 `frp_0.72.0_windows_amd64`，第 1 步服务器也统一装
> **v0.72.0**（两边一致，避免混用）。两者都是 0.5x+，只认 TOML 配置。

1. Windows 版 frp 解压到 **`D:\frp\`**。官方 zip 会解出一层子目录，例如
   `D:\frp\frp_0.72.0_windows_amd64\` —— **不用摊平**，脚本会自动找到这层子目录。
2. **一条命令搞定准备 + 安装**（⚠️ 在**你自己 Windows 电脑**的普通 PowerShell 里跑，
   **不是**服务器 SSH 的 bash）：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\Java\smart-manfancturing\deploy\frp\setup-frpc.ps1" -Install
```

`setup-frpc.ps1` 会：自动找到子目录里的 `frpc.exe` → 用仓库里**已填好值**的
`frpc.toml` 覆盖掉 `CHANGE_ME_...` 占位符 → 把 `install-frpc-task.ps1` 复制到
`D:\frp\` 顶层 → 校验 frps/frpc 两边 token 一致 → 建 ONLOGON 计划任务并立即启动一次。
输出里会打印实际找到的路径和 token，**核对一眼**。

只想准备文件、不建任务，去掉 `-Install`；放别的盘用 `-FrpDir "C:\frp"`。

| 项 | 值 | 说明 |
|---|---|---|
| `serverAddr` | `8.148.212.158` | 服务器公网 IP |
| `auth.token` | `7bHQX4qqK5Rb-yB8pphA8Qv813blSnAnfaLf2wZiJ8c` | **必须与 `frps.toml` 完全一致** |

3. 若上面没带 `-Install`，再单独跑安装（脚本在 `D:\frp\` 顶层，会自动找到子目录里的 `frpc.exe`）：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\frp\install-frpc-task.ps1"
```

这会创建登录时自动启动的计划任务（用 VBS 隐藏黑框），并立即启动一次测试。
脚本输出会打印它实际找到的 `frpc.exe` 和配置文件路径，**先核对一眼是不是你解压的那个目录**。

> `frpc.log` 写在 `frpc.exe` 同目录（`./frpc.log`）。因为黑框被隐藏，连不上时
> 先看这个日志，再看服务器 `/var/log/frps.log`。

**放别的盘 / 别的目录**：脚本默认 `D:\frp`，用 `-FrpDir` 指定即可，脚本本身放哪都行：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\frp\install-frpc-task.ps1" -FrpDir "C:\frp"
powershell -ExecutionPolicy Bypass -File "D:\frp\install-frpc-task.ps1" -FrpDir "E:\tools\frp"
```

`frpc.exe`、`frpc.toml` 必须在**同一个目录**里；若它们位于 `D:\frp` 的一层子目录
（zip 解压出来的那种），脚本会自动识别，无需摊平。脚本会在该目录生成 `start-frpc.vbs`。
（同时支持 `frpc.toml` / `frpc.ini`，两者都在时优先 TOML；用 0.4x 老版本就只放 `frpc.ini`。）

> ⚠️ 路径请用**纯英文、无空格**（如 `D:\frp`）。原因有两个：脚本生成的 VBS 是
> ASCII 编码，路径含中文/空格会让计划任务静默启动失败（黑框又被隐藏，很难排查）；
> 且 Windows PowerShell 5.1 会按系统 ANSI 代码页读取无 BOM 的 `.ps1`，
> 脚本里的中文注释会被读成乱码导致语法错误——所以脚本已全部改成 ASCII 字符，
> 你的路径也别带中文。
>
> 脚本会自动删除同名旧任务再重建，所以从 C 盘换到 D 盘、或改了 `-FrpDir` 后
> 直接重跑一次即可，不用手动去任务计划程序里清理。

确认隧道建立：

```bash
# 服务器上执行
sudo systemctl status frps --no-pager
ss -tlnp | grep 7000          # frps 控制端口，应看到 0.0.0.0:7000
ss -tlnp | grep 9000          # frpc 连上后才有，应看到 127.0.0.1:9000
sudo tail -30 /var/log/frps.log
curl -s http://127.0.0.1/actuator/health
```

最后一条返回 `{"status":"UP",...}` 才说明整条链路通了。

> **怎么从 frps.log 判断 frpc 有没有来连**：frps 启动只会写 4 行
> （`uses config file` / `tcp listen on 0.0.0.0:7000` / `started successfully` /
> `dashboard listen on 127.0.0.1:7500`），之后**保持安静是正常行为**，不会主动报错。
> 只有 frpc 连上来才会追加 `client login info: ... version [0.72.0]`、
> `new proxy [mes-gateway] type [tcp] success` 等行。
> 所以「日志只有启动 4 行 + `ss -tlnp | grep 9000` 无输出」= **frpc 还没连**，
> 说明第 4 步的 Windows 脚本还没跑（或跑了但没成功），不是 frps 的问题。
>
> ⚠️ `setup-frpc.ps1` / `install-frpc-task.ps1` 都是 **Windows PowerShell** 脚本，
> 必须在**你自己的电脑**上运行；贴到服务器 SSH（Linux bash）里会报
> `command not found`，而且也不会创建 Windows 计划任务。

**先做连通性体检**：`Test-NetConnection` 是 **Windows PowerShell** 的 cmdlet，
**只能在你的 Windows 电脑上跑**（SSH 到服务器的 bash 里没有这个命令，会报
`command not found`）。两端命令不同，别搞混：

```powershell
# ① 在你的 Windows 电脑上跑（普通 PowerShell），测「外部能否连到服务器」
Test-NetConnection 8.148.212.158 -Port 80     # 应为 True（网站通）
Test-NetConnection 8.148.212.158 -Port 7000   # 应为 True（frps 通）
```

```bash
# ② 在服务器 SSH / bash 里跑，测「frps 本机是否在监听」
#    （这是 Linux，没有 Test-NetConnection）
ss -tlnp | grep 7000                         # 应看到 0.0.0.0:7000 或 :::7000
curl -sS --connect-timeout 3 telnet://127.0.0.1:7000 -o /dev/null -w '%{http_code}\n' 2>/dev/null || true
nc -zv 127.0.0.1 7000                        # 有 nc 的话最直观：succeeded
```

> 两个视角缺一不可：② 通但 ① 不通 → 服务正常，**卡在安全组/防火墙**；
> ② 都不通 → frps 根本没起。在服务器上测自己的公网 IP 意义有限（可能
> 被安全组绕开或走内网回环），**判断外部可达性以 ① 为准**。
>
> **实测排查链（本次部署已走完，结果：✔ 已解决）**：
> `systemctl status frps` → `active (running)` ✔
> `ss -tlnp | grep 7000` → `LISTEN *:7000` ✔
> `firewall-cmd --list-all` → `FirewallD is not running` ✔（系统防火墙没拦）
> Windows 侧 `Test-NetConnection ... -Port 7000` → 起初 False、80 为 True
> ⇒ 服务侧三层全通，唯一剩下的是 **阿里云 ECS 安全组入方向没放行 TCP 7000**
> （安全组在宿主机外层，服务器本机自测会绕过它）。在控制台加规则
> TCP `7000/7000`（源 IP 填你家出口 IP，查 `curl -s ifconfig.me`）后，
> 复测变 **True**，无需重启 frps。**这一步不做完，装 frpc 也连不上**，
> 表现为 `frpc.log` 里反复 `dial tcp ...:7000: i/o timeout`。
>
> 若以后再遇到 7000 不通，按同样顺序查：服务状态 → `ss` 监听 → 系统防火墙
> → 安全组。前三项都正常就一定卡在安全组。

**另一个已知状态**：本机 `9000` 目前**未监听**（后端 6 个 Java 服务没起）。
这不影响 frpc 启动与隧道建立（frpc 只是等在那儿转发），但第 4 步末尾那条
`curl .../actuator/health` 会返回 502 —— 那是第 5 步的事，别在这步反复排查。

## 第 5 步：确认本机后端在跑

> ⚠️ 本轮实测本机 `9000` **未监听**，所以这一步是必须做的，不是可选检查。

你的 Windows 上 6 个 Java 服务需要保持运行。jar 已编译好，启动脚本在
`.tmp/startup-logs/start-all.ps1`（首轮启动用的就是它）：

```powershell
cd D:\Java\smart-manfancturing
powershell -ExecutionPolicy Bypass -File ".tmp\startup-logs\start-all.ps1"
```

它会按 system → product → inventory → quality → production → gateway 顺序后台启动，
每个间隔 3 秒，输出 `ALL_STARTED`，日志写到 `.tmp/startup-logs/*.out.log`。
**注意脚本里带了 `--spring.cloud.nacos.discovery.enabled=false` 等参数**（本机
没跑 Nacos/RabbitMQ/Redis），别直接用 `java -jar` 裸起，会连不上基础设施而报错。

确认网关起来（6 个服务约需 20~40 秒）：

```powershell
Invoke-WebRequest http://127.0.0.1:9000/actuator/health
# 期望：StatusCode 200，内容 {"status":"UP",...}

# 服务器侧再看一次（此时 frpc 已连、后端已起）
curl -s http://127.0.0.1/actuator/health
```

若某个服务没起来，看对应 `.tmp/startup-logs/<服务名>.err.log`。

> 顺序上：**先第 1 步让服务器 7000 通 → 第 4 步建隧道 → 第 5 步起后端**。
> 后端没起时隧道也能建，但 `/actuator/health` 必然 502，别误判成 frp 故障。
> 这套服务没有开机自启，电脑重启后要重新跑一次 `start-all.ps1`。

---

## 验证两种状态

| 操作 | 期望 |
|---|---|
| 浏览器打开 `http://服务器公网IP/` | 页面正常，顶部「演示数据」 |
| 点右上角登录 `admin / 123456` | 隧道通 → 「网关已连接」，切 live |
| 新建一个工单 | 本机 MySQL `smart_production.production_order` 多一行 |
| 停掉 frpc（或关电脑） | 10 秒内页面自动切回演示数据，**不白屏、不报错** |
| 重新连上 frpc | 自动切回 live，刷新真实数据，无需重新登录 |

验证落库：

```sql
-- 本机 MySQL
USE smart_production;
SELECT id, order_no, status, plan_quantity FROM production_order ORDER BY id DESC LIMIT 5;
```

---

## 关键设计点（改了什么）

| 文件 | 改动 |
|---|---|
| `api.js` | 新增 `ping()`：同源探测 `/actuator/health`，不带 token、不触发 401 清理 |
| `app.js` | 新增「连接守护」：每 10s 探测；离线切 demo **保留 token**；恢复自动切 live |
| `app.js` | `syncStoredSession` 和登录失败分支**不再调用 `api.logout()`**，避免 token 被清导致无法自动接管 |
| `index.html` | 未改（`api.js` 的 `defaultBase = window.location.origin` 天然同源） |

`disconnect`（用户主动断开）仍会清 token —— 这是刻意的：主动断开后需重新登录，守护不会擅自接回。

## 已知限制

- 首次仍需**手动登录一次**（账号密码不内置前端，安全底线）；之后 token 存 localStorage，开关机自动恢复
- 探测有 10 秒延迟，状态切换不是瞬时的
- 隧道走服务器中转 + 你家上行带宽，适合演示/小范围访问
- 电脑休眠/断网/frpc 进程被关 → 自动退回演示
- 若服务器想上 HTTPS，只在 OpenResty 配证书即可，内网反代仍是 http

## 排障

| 现象 | 检查 |
|---|---|
| 保存配置报服务内部错误 `"root" directive is duplicate` | 把 `mes-site.conf` 整段粘进了 1Panel 已有的 `server {}`。1Panel 顶部本来就有 `root` / `index`，再粘一份必冲突；同理 `index` 和 `location /` 也会重复。改用 `smart-mes.full.conf` 整段替换，或只合并 location 段。改完 `include /www/sites/<站点>/proxy/*.conf` 里若还残留旧的 `mes-gateway` 反代，也要删掉 |
| 报 `open() "/.../proxy_params" failed (2: No such file or directory)` | OpenResty 编译版默认没有 `proxy_params` 文件。删掉 `include proxy_params;`，把请求头统一写在 `server {}` 级（见 `smart-mes.full.conf`） |
| 报 `duplicate location "/"` | 1Panel 已有 `location /`，别新增第二个，把 `try_files $uri $uri/ /index.html;` 补进原来那个即可 |
| frps 已 `active (running)` 但 Windows 测 7000 仍 False | 服务没问题，卡在**阿里云安全组入方向 TCP 7000**（最常见）或系统防火墙。服务器 `ss -tlnp \| grep 7000` 有监听不代表外部可达；ECS 安全组在宿主机外层，本机自测会绕过它。控制台放行后重测 `Test-NetConnection 8.148.212.158 -Port 7000` |
| `frps.log` 只有 4 行启动记录、`ss -tlnp \| grep 9000` 无输出 | frps 正常但**frpc 一次都没连过** —— 第 4 步的 Windows 脚本还没跑/没成功。frps 是被动等待，安静是正常的。去 Windows 上跑 `setup-frpc.ps1 -Install`，再看 `frpc.log` 是否 `login to server success` |
| `Unit frps.service could not be found` / journal 无记录 | **frps 根本没装**，第 1 步被跳过了。回到第 1 步下载 v0.72.0、传 `frps.toml` + `frps.service`、`systemctl enable --now frps`。装完 `ss -tlnp \| grep 7000` 应有监听 |
| `frpc.log` 反复 `dial tcp ...:7000: i/o timeout` | 服务器 7000 没通。Windows 上 `Test-NetConnection 8.148.212.158 -Port 7000` 为 False 时，先在服务器 bash 里 `systemctl status frps`、`ss -tlnp \| grep 7000`，再查阿里云**安全组入方向 TCP 7000**、1Panel/系统防火墙（详见第 4 步「连通性体检」）。注意 `Test-NetConnection` 不能在服务器 bash 里跑 |
| 隧道建了但 `/actuator/health` 恒 502 | 多半是本机 9000 后端没起（第 5 步）。frpc 连上不等于后端在跑；`Invoke-WebRequest http://127.0.0.1:9000/actuator/health` 确认 |
| 一直演示模式，登录也失败 | 服务器 `curl -s http://127.0.0.1/actuator/health` 是不是 502；frps/frpc 日志；token 是否两边一致 |
| 切回 live 后数据没刷新 | 看浏览器 Console；`refreshLiveData` 里哪个接口报错 |
| 登录成功但立刻回演示 | 多半是 token 过期（8h），重新登录一次 |
| 9000 被公网访问 | 检查 `proxyBindAddr=127.0.0.1` 是否生效（`ss -tlnp \| grep 9000` 应是 127.0.0.1） |
| frp 版本不认 toml | 用同目录 `.ini` 版本 |
