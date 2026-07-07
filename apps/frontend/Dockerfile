# ── Build stage ───────────────────────────────────────────────────────
FROM node:26-alpine AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src
RUN npm run build

# ── Runtime stage: unprivileged nginx on 8080 ────────────────────────
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.d/10-runtime-env.sh /docker-entrypoint.d/10-runtime-env.sh
# --chown so the entrypoint (running as nginx, uid 101) can rewrite env.js at start
COPY --from=build --chown=nginx:nginx /workspace/dist /usr/share/nginx/html

EXPOSE 8080
