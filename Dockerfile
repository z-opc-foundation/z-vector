# z-vector Dockerfile — 多阶段构建 (Eclipse Temurin 8 JDK + JRE)
# 用于把 z-vector-grpc-server 打成可对外提供服务的镜像

# ============================================================
# Stage 1: build (maven:3.9.9 + Eclipse Temurin 8 JDK)
# ============================================================
FROM maven:3.9.9-eclipse-temurin-8 AS builder

ARG M2_REPO=/root/.m2
WORKDIR /build

# 1) 先复制 parent + 子模块 pom
COPY ./pom.xml ./pom.xml
COPY ./z-vector-api ./z-vector-api
COPY ./z-vector-core ./z-vector-core
COPY ./z-vector-storage ./z-vector-storage
COPY ./z-vector-protocol ./z-vector-protocol
COPY ./z-vector-grpc-server ./z-vector-grpc-server

# 2) install parent (NEXUS 跳过测试和 javadoc, 加速)
RUN mvn -N -f pom.xml install -DskipTests -Dmaven.javadoc.skip=true -q

# 3) 编译 grpc-server 及其依赖 (-am)
RUN mvn -f pom.xml -pl z-vector-grpc-server -am clean package \
        -DskipTests \
        -Dmaven.javadoc.skip=true \
        -q

# ============================================================
# Stage 2: runtime (Eclipse Temurin 8 JRE + 非 root 用户)
# ============================================================
FROM eclipse-temurin:8-jre

LABEL maintainer="zifang"
LABEL description="z-vector - Vector Database (gRPC + REST API)"

# 时区
RUN apt-get update && apt-get install -y --no-install-recommends tzdata wget && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apt-get remove -y tzdata && apt-get autoremove -y && apt-get clean

# 创建非 root 用户
RUN useradd --system --uid 10001 zvector

WORKDIR /app

# 复制可执行 jar
COPY --from=builder /build/z-vector-grpc-server/target/z-vector-grpc-server-*.jar /app/z-vector-server.jar

RUN mkdir -p /app/data /app/logs && chown -R zvector:zvector /app
USER 10001

# 端口: 9090 (gRPC), 6333 (REST API)
EXPOSE 9090 6333

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:6333/health || exit 1

# JVM 默认参数 (可在 docker run -e JVM_OPTS=... 覆盖)
ENV JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
ENV ZVECTOR_DATA_DIR="/app/data"

# 启动
# 启动：VectorServerApplication 是裸 main()，旋钮只有环境变量（ZVECTOR_PORT / ZVECTOR_DATA_DIR）。
# 以前这里挂着 --z.vector.data-dir=$ZVECTOR_DATA_DIR：那个进程不解析任何 --x.y 属性，
# 值是靠同名环境变量生效的，这半句纯装饰 —— 谁照它在命令行加 --z.vector.port= 就静默无效。
ENTRYPOINT ["sh", "-c", "exec java $JVM_OPTS -jar /app/z-vector-server.jar"]
