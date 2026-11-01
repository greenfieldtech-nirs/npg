FROM node:20-alpine AS builder
ARG VITE_GUEST_PAGE=landing
ENV VITE_GUEST_PAGE=$VITE_GUEST_PAGE
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# non-root nginx, listening on 8080
FROM nginxinc/nginx-unprivileged:1.27-alpine
# runtime settings, read at container start (see docker/default.conf.template); empty = off
ENV GA_MEASUREMENT_ID=""
COPY --from=builder /app/dist /usr/share/nginx/html
COPY docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=0755 docker/05-npg-runtime-config.sh /docker-entrypoint.d/05-npg-runtime-config.sh
EXPOSE 8080
