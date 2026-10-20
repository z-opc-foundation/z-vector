# z-vector Dockerfile — 多阶段构建 (Eclipse Temurin 8 JDK + JRE)
# 独立 server 镜像：REST(6333) + gRPC，运行 VectorServerApplication。
#
# 2026-10-18 修正三处（原先本文件自述「在干净机器上构建不出来」，两条原因已各有一半失效）：
#   1) 原构建上下文里没有 parent，是真问题；但 pom 的 parent 早已迁到 Central 上的
#      io.github.yuku123:z-boot-parent:1.1.0 且 relativePath 为空（见 pom.xml 头部注释），
#      空 ~/.m2 的 builder 也能从 repo1 解析到 ⇒ 「只在自己机器上能构建」的前提已不存在，
#      原来的 `mvn -N install` 那步（装一个 404 的 com.zifang:z-opc:1.0.0-SNAPSHOT）一并去掉。
#   2) 原先拷的是 z-vector-grpc-server 的 jar —— 全仓唯一的 main() 在 z-vector-server
#      模块里，grpc-server 那份 MANIFEST 里没有 Main-Class，`java -jar` 必报
#      "no main manifest attribute"。现在多阶段直接产出 z-vector-server 的 shade fat jar，
#      它依赖 grpc-server，Qdrant 兼容面（含 /points/scroll 与 /points/delete）都打在同一个 jar 里。
#   3) 原 EXPOSE 9090 是空头广告：独立 server 不监听 gRPC 端口，只监听 6333。
#
# 构建（上下文 = 本仓根目录）：
#   docker build -t z-vector:local .
# 跨架构（Mac arm64 → 集群 amd64）：
#   docker buildx build --platform linux/amd64 -t z-vector:local --load .

# ============================================================
# Stage 1: build (maven:3.9.9 + Eclipse Temurin 8 JDK)
# ============================================================
# 基础镜像走 build-arg：默认官方坐标，但本网络内 Docker Hub 不可达
# （auth.docker.io i/o timeout），需要时用 --build-arg 换成可达的镜像站前缀。
ARG BUILDER_BASE=maven:3.9.9-eclipse-temurin-8
ARG RUNTIME_BASE=eclipse-temurin:8-jre

FROM ${BUILDER_BASE} AS builder

WORKDIR /build

# z-vector-server 的 -am 链会牵进 api/core/storage/protocol/grpc-server，一次全拷，
# 少拷一个就是构建期 "Could not resolve dependencies"，不在运行期报错。
# 根 pom 的 <modules> 列了全部 7 个模块，Maven 在解析 reactor 阶段就要求它们**都在**
# 上下文里，缺一个直接 ProjectBuildingException，连 -pl 都进不去。
COPY ./pom.xml ./pom.xml
COPY ./z-vector-api ./z-vector-api
COPY ./z-vector-core ./z-vector-core
COPY ./z-vector-storage ./z-vector-storage
COPY ./z-vector-protocol ./z-vector-protocol
COPY ./z-vector-grpc-server ./z-vector-grpc-server
COPY ./z-vector-server ./z-vector-server
COPY ./z-vector-spring-boot-starter ./z-vector-spring-boot-starter

RUN mvn -f pom.xml -pl z-vector-server -am clean package \
        -DskipTests \
        -Dmaven.javadoc.skip=true \
        -q

# ============================================================
# Stage 2: runtime (Eclipse Temurin 8 JRE + 非 root 用户)
# ============================================================
FROM ${RUNTIME_BASE}

LABEL maintainer="zifang"
LABEL description="z-vector - Vector Database (Qdrant-compatible REST API)"

RUN apt-get update && apt-get install -y --no-install-recommends tzdata wget && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apt-get autoremove -y && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 zvector

WORKDIR /app

# shade 插件的瘦身副本叫 original-z-vector-server-<ver>.jar，不以 z-vector-server- 开头，
# 这个 glob 只会命中 fat jar 那一份。
COPY --from=builder /build/z-vector-server/target/z-vector-server-*.jar /app/z-vector-server.jar

RUN mkdir -p /app/data /app/logs && chown -R zvector:zvector /app
USER 10001

# 6333 = Qdrant 兼容 REST + /health + /instances
EXPOSE 6333

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:6333/health || exit 1

ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC"
ENV ZVECTOR_PORT="6333"
ENV ZVECTOR_DATA_DIR="/app/data"
# 默认索引：不带 index_type 的 POST /collections 用哪一个（FLAT|HNSW|IVF）。空串 = 走内置 FLAT。
ENV ZVECTOR_DEFAULT_INDEX=""
ENV ZVECTOR_INDEX_PARAMS=""

# VectorServerApplication 是裸 main()，只认环境变量，不解析任何 --x.y 属性 ——
# 别在命令行追加 --z.vector.port= 之类，那样静默无效。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/z-vector-server.jar"]
