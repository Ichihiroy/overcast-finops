# ── Build stage ───────────────────────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src
RUN npm run build

# ── Runtime stage: unprivileged nginx on 8080 ────────────────────────
FROM nginxinc/nginx-unprivileged:1.31-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
# --chmod: the nginx entrypoint only RUNS executable scripts in
# /docker-entrypoint.d/ — a non-executable copy is silently skipped and the
# committed local-dev env.js (localhost API) would ship to production.
COPY --chmod=755 docker-entrypoint.d/10-runtime-env.sh /docker-entrypoint.d/10-runtime-env.sh
# --chown so the entrypoint (running as nginx, uid 101) can rewrite env.js at start
COPY --from=build --chown=nginx:nginx /workspace/dist /usr/share/nginx/html

# Explicit non-root user (uid 101 = nginx in the unprivileged base). Redundant
# with the base image's default, but declared so the guarantee survives a base
# bump and satisfies image-scanners (Trivy DS-0002) that can't see through FROM.
USER 101

EXPOSE 8080
