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
# 名字不写死版本号：这里曾经是 z-vector-server-1.0.1.jar，而构件一路升到 1.0.3 ⇒
# 后来每次 docker build 都在 COPY 这一步当场失败（找不到文件）。#20 把 pom 里的版本
# 收成一处之后，这类"最后一个还写死版本的地方"就只剩这里 —— 一个写死的 jar 名，
# 后果不是显示旧版本，而是这台服务再也构建不出来（250 上那台 1.0.1 就是这么冻住的）。
# spring-boot 那份瘦身分支叫 original-z-vector-server-<ver>.jar。COPY 的 glob 按整个文件名匹配，
# 它不以 z-vector-server- 开头 ⇒ 不会被命中（复现：mvn package 之后在 target/ 里跑
# `ls -1 z-vector-server-*.jar`，列表里只应当有 fat jar 那一条）。
COPY target/z-vector-server-*.jar /app/z-vector-server.jar

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
# 默认索引：不带 index_type 的 POST /collections 用哪一个（FLAT|HNSW|IVF，大小写不敏感）。
# 空串 = 不覆盖，走内置 FLAT。拼错不静默降级：进程直接起不来并在日志里点名这个变量。
ENV ZVECTOR_DEFAULT_INDEX=""
# 默认索引的参数，一个 JSON 对象，形状与 REST body 里的 index_params 一致。
# 只对上面那条生效；显式带了 index_type 的请求一律照本宣科，不吃这里的参数。
ENV ZVECTOR_INDEX_PARAMS=""
# 例：docker run -e ZVECTOR_DEFAULT_INDEX=HNSW -e ZVECTOR_INDEX_PARAMS='{"M":16,"ef_construction":200}' ...

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/z-vector-server.jar"]
