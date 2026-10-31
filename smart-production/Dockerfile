# =====================================================================
# smart-production 镜像（多阶段构建）
#
# 构建上下文必须是【仓库根目录】（需要父 pom + smart-common）：
#   docker build -f smart-production/Dockerfile -t mes/smart-production:1.0.0 .
# =====================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只拷 pom，利用 Docker 层缓存：pom 没变就不重复下载依赖
# 注意：父 pom 的 <modules> 声明了全部 7 个模块，Maven 解析聚合工程时
# 要求所有被声明模块的 pom.xml 必须存在（即使 -pl 只构建其中两个），
# 所以必须把 7 个 pom 全部拷入，否则报 "Child module does not exist"。
COPY pom.xml .
COPY smart-common/pom.xml smart-common/
COPY smart-production/pom.xml smart-production/
COPY smart-gateway/pom.xml smart-gateway/
COPY smart-system/pom.xml smart-system/
COPY smart-product/pom.xml smart-product/
COPY smart-inventory/pom.xml smart-inventory/
COPY smart-quality/pom.xml smart-quality/

# 预下载依赖（失败不阻断，后续 build 还会再解析一次）
RUN mvn -B -q -pl smart-common,smart-production -am dependency:go-offline || true

# 再拷源码并构建
COPY smart-common/src smart-common/src
COPY smart-production/src smart-production/src
RUN mvn -B -pl smart-common,smart-production -am clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-jammy

# 安装 curl 供 HEALTHCHECK 使用
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

# 时区与字符集（中文日志与报表依赖）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 非 root 运行（安全基线）
RUN groupadd -r mes && useradd -r -g mes -m mes
WORKDIR /app
COPY --from=builder /build/smart-production/target/smart-production-1.0.0.jar app.jar
RUN chown -R mes:mes /app
USER mes

EXPOSE 9003

# 健康检查：Spring Boot Actuator
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://localhost:9003/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
