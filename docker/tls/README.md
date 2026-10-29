# TLS material

Mounted read-only into the edge NGINX (`/etc/nginx/tls/`) by `docker/docker-compose.prod.yml`;
ignored by git — never commit these files.

| File | Purpose |
|------|---------|
| `cert.pem` | This gateway's server certificate chain (HTTPS for the console and for peers) |
| `key.pem` | Its private key |
| `peer-ca.pem` | CA bundle that issued peer operators' client certificates (mTLS on `/np`) |

After placing `peer-ca.pem`, configure each peer's endpoint `tls_subject` (exact subject DN, as
NGINX reports it in `$ssl_client_s_dn`) and set `INBOUND_TRUST_PROXY_CLIENT_CERT=true`.
Peers without client certificates can be authenticated by `ip_allowlist` instead.

For outbound mTLS (our client certificate towards peers) set `OUTBOUND_TLS_KEYSTORE` (PKCS#12)
and `OUTBOUND_TLS_KEYSTORE_PASSWORD`, and mount the keystore into the dispatcher container.
