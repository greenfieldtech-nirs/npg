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
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
