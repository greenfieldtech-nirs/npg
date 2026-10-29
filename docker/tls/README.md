# TLS material

Place the edge certificate and private key here before enabling the HTTPS
server block in `nginx/nginx.prod.conf`:

- `cert.pem` — certificate chain
- `key.pem` — private key

These files are mounted read-only into the NGINX container by
`docker/docker-compose.prod.yml` and are ignored by git. Never commit them.
