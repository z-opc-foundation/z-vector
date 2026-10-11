# 多阶段：node 构建 → nginx 服务 dist
# 阶段一（feature001）分开发部署；阶段二会改成 z-vector-server 自托管 dist
#
# 三个环境相关的坑，都记在这里免得下次再踩：
#  1) 基础镜像用 node:20 而非 22 —— 250 那台机的本地镜像库只有 20/18-alpine，
#     而它访问 registry-1.docker.io 超时（daemon.json 里配的两个 mirror 没救回来）。
#     用本地已有的 tag，构建才不依赖外网。
#  2) z-vector-component 是 file:../z-vector-component 的本地包，npm 上没有
#     （@yuku123/z-vector-component 在 registry 里查是 NOT_FOUND）。
#     只 COPY suit 目录的话，node_modules 里装不上它，vite 报
#     "Rollup failed to resolve import" —— 所以构建上下文必须是 _frontend 整层。
#  3) npm 默认源 registry.npmjs.org 在 250 上不通，只有 npmmirror 通。
FROM node:20-alpine AS build
WORKDIR /app

# 组件包要先在，因为 package.json 里的 file: 依赖指向它
COPY z-vector-component ./z-vector-component
COPY z-vector-suit/package.json z-vector-suit/package-lock.json* ./z-vector-suit/
RUN cd z-vector-suit && npm ci --registry=https://registry.npmmirror.com/

# z-vector-component 的 antd/react 全是 peerDependencies，它自己没有 node_modules，
# 所以 dist/index.js 里的 import 'antd' 必须从 suit 的 node_modules 往上解析到。
# 放在 /app/z-vector-component 解析不到（node 逐级向上找的是 /app/node_modules，
# 而那里是空的）；放进 suit/node_modules 下才对。
RUN rm -rf /app/z-vector-suit/node_modules/@yuku123/z-vector-component \
 && mkdir -p /app/z-vector-suit/node_modules/@yuku123 \
 && cp -r /app/z-vector-component /app/z-vector-suit/node_modules/@yuku123/z-vector-component

COPY z-vector-suit/index.html z-vector-suit/vite.config.mjs ./z-vector-suit/
COPY z-vector-suit/src ./z-vector-suit/src
COPY z-vector-suit/public ./z-vector-suit/public
RUN cd z-vector-suit && npm run build

FROM nginx:1.27-alpine
LABEL org.opencontainers.image.title="z-vector-frontend" \
      org.opencontainers.image.description="z-vector 管理台 (nginx 静态托管 + /api 反代)" \
      org.opencontainers.image.source="https://github.com/z-opc-foundation/z-vector"

COPY --from=build /app/z-vector-suit/dist /usr/share/nginx/html
# 放 templates/ 而不是 conf.d/：nginx 官方 entrypoint 会对 templates 下的文件做 envsubst，
# 把 ${ZVECTOR_API_UPSTREAM} 替换成环境变量值后写入 conf.d。直接放 conf.d 不做替换。
COPY z-vector-suit/nginx.conf.template /etc/nginx/templates/default.conf.template

# 后端地址：k8s 里走 Service 名，本机调试可覆盖成 127.0.0.1:6333
ENV ZVECTOR_API_UPSTREAM=z-vector-server:6333

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -qO- http://127.0.0.1/healthz >/dev/null 2>&1 || exit 1