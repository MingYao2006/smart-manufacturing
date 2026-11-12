#!/usr/bin/env bash
# SmartMES 一键部署（在 deploy/ 目录下执行）
set -euo pipefail
cd "$(dirname "$0")"

echo "=== 0. 检查文件 ==="
for f in Dockerfile.jre docker-compose.yml .env; do
  [ -f "$f" ] || { echo "缺少 $f"; exit 1; }
done
for j in gateway system product production inventory quality; do
  [ -f "app/smart-$j-1.0.0.jar" ] || { echo "缺少 app/smart-$j-1.0.0.jar"; exit 1; }
done
[ -f sql/00_create_databases.sql ] || { echo "缺少 sql/ 初始化脚本"; exit 1; }
echo "文件齐全"

echo "=== 1. 检查 Docker ==="
sudo docker info >/dev/null 2>&1 || { echo "Docker 不可用，请先启动 Docker"; exit 1; }
echo "Docker OK"

echo "=== 2. 预拉基础镜像（失败可重试，不会无限卡住）==="
for img in eclipse-temurin:21-jre-jammy mysql:8.0; do
  echo "--- pull $img"
  for i in 1 2 3; do
    if sudo timeout 300 docker pull "$img"; then break; fi
    echo "第 $i 次失败，5 秒后重试"; sleep 5
  done
done

echo "=== 3. 启动全部容器 ==="
sudo docker compose up -d --build

echo "=== 4. 等待服务就绪（最多 180 秒）==="
for i in $(seq 1 36); do
  ready=0
  for p in 9000 9001 9002 9003 9004 9005; do
    curl -fsS --connect-timeout 2 "http://127.0.0.1:$p/" >/dev/null 2>&1 && ready=$((ready+1)) || true
  done
  echo "  第 ${i}/36 次探测：${ready}/6 个服务已响应"
  [ "$ready" -eq 6 ] && break
  sleep 5
done

echo "=== 5. 容器状态 ==="
sudo docker compose ps

echo "=== 6. 网关健康检查 ==="
curl -fsS http://127.0.0.1:9000/actuator/health || true
echo
echo "=== 7. 数据库检查 ==="
sudo docker exec mes-mysql mysql -uroot -p"${MYSQL_PASSWORD:-123456}" \
  -e "SHOW DATABASES LIKE 'smart_%';" 2>/dev/null || true

echo
echo "=== 完成 ==="
echo "工作台（直连调试）：http://服务器公网IP:9000/"
echo "正式访问：在 1Panel 里把站点反向代理到 http://127.0.0.1:9000，走 80/443"
echo "演示账号：admin / 123456"
