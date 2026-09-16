# z-vector-server Dockerfile
FROM eclipse-temurin:8-jre

LABEL maintainer="zifang"
LABEL description="z-vector standalone server"

# 时区
RUN apt-get update && apt-get install -y --no-install-recommends tzdata wget && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apt-get remove -y tzdata && apt-get autoremove -y && apt-get clean

# 创建非 root 用户
RUN useradd --system --uid 10001 zvector

WORKDIR /app

# 复制 fat jar
COPY target/z-vector-server-1.0.1.jar /app/z-vector-server.jar

RUN mkdir -p /data/zvector && chown -R zvector:zvector /app /data/zvector
USER 10001

# 端口: 6333 (REST API)
EXPOSE 6333

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:6333/health || exit 1

# JVM 参数
ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC"
ENV ZVECTOR_PORT="6333"
ENV ZVECTOR_DATA_DIR="/data/zvector"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/z-vector-server.jar"]
