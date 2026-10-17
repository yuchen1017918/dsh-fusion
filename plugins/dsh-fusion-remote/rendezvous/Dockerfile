# 会合服务器。镜像很小 —— 它只做「转发字节」这一件事。
FROM node:22-alpine

# 不用 root 跑。万一有 RCE，攻击者拿到的是个普通用户。
RUN addgroup -S rv && adduser -S -G rv rv
WORKDIR /app

COPY package.json ./
# `--omit=dev` 是默认，但写出来更明确
RUN npm install --omit=dev --no-audit --no-fund && npm cache clean --force

COPY server.mjs ./

USER rv
EXPOSE 8443

# 健康检查探测的是 `/healthz`（只回 "ok"），不是首页 —— 首页故意是 404
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8443/healthz || exit 1

CMD ["node", "server.mjs"]
