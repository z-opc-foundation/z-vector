# 多阶段：node 构建 → nginx 服务 dist
# 阶段一（feature001）分开发部署；阶段二会改成 z-vector-server 自托管 dist
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
