# SmartMES 服务器部署包

把整个 `deploy` 目录上传到阿里云 ECS（VNC 里用 1Panel 文件管理上传 zip 再解压即可），
然后进入目录执行：

```bash
chmod +x deploy.sh
./deploy.sh
```

脚本会：拉基础镜像 → 起 MySQL 并自动执行 sql/ 建库 → 起 5 个业务服务 → 起网关。

## 目录说明

```
deploy/
├── docker-compose.yml      # 6 个应用 + MySQL，共 7 个容器
├── .env                    # 密码等配置（部署前请改）
├── deploy.sh               # 一键启动 + 健康检查
├── app/                    # 6 个已构建好的 jar（由 scripts 打包脚本生成）
│   ├── smart-gateway-1.0.0.jar
│   ├── smart-system-1.0.0.jar
│   ├── smart-product-1.0.0.jar
│   ├── smart-production-1.0.0.jar
│   ├── smart-inventory-1.0.0.jar
│   └── smart-quality-1.0.0.jar
├── sql/                    # 建库、建表、初始化数据（顺序文件名）
├── Dockerfile.jre          # 极简运行镜像：JRE + jar，无 Maven 构建阶段
└── openresty-mes.conf      # 可选：1Panel 反代配置参考
```

## 访问

- 工作台：http://服务器公网IP/ （走 OpenResty 反代到网关 9000）
- 或直连：http://服务器公网IP:9000/ （需在安全组放行 9000，仅调试用）

演示账号：`admin / 123456`（ADMIN，全部权限）。

## 验证数据真的落库

```bash
# 进 MySQL 容器
docker exec -it mes-mysql mysql -uroot -p123456

# 在数据库里看工单（用工作台新建一张工单前后各查一次）
USE smart_production;
SELECT id, order_no, status, plan_quantity FROM production_order ORDER BY id DESC LIMIT 5;
```

工作台里「新建工单 → 审核 → 下达」后，上面这条 SQL 会多出一行，即为真实落库。

## 常用运维

```bash
docker compose ps                       # 看 7 个容器状态
docker compose logs -f smart-gateway    # 看网关日志
docker compose restart smart-production # 重启某服务
docker compose down                     # 停止（保留 MySQL 数据卷）
docker compose down -v                  # 停止并清空数据（重跑初始化脚本用）
```

## 重要提醒

1. `00_create_databases.sql` 会 DROP 全部 5 个库。**仅首次部署执行一次**；
   MySQL 数据卷已存在时不会重跑初始化脚本。改了 SQL 想重来才用 `down -v`。
2. 业务服务 9001–9005 **不要对公网开放**（README 明确说明：直接暴露可伪造
   X-User-Id 等请求头，绕过鉴权）。只放行 80/443（和调试用的 9000）。
3. 生产环境务必修改 `.env` 里的 `MYSQL_PASSWORD` / `JWT_SECRET`，
   并轮换 `06_init_data.sql` 里的种子账号密码。
4. 全套约需 2–3GB 内存。若服务器内存小，可给每个 JVM 加
   `-Xmx512m`（改 compose 的 JAVA_OPTS）。
