# TLS material

TLS for the console, the API and the operators' inbound gateway terminates at the **load
balancer**, which forwards plain HTTP to NGINX on port 8090 (see `nginx/nginx.prod.conf`). No
server certificate is needed in this stack, and NGINX cannot verify peer client certificates:
authenticate peers by their endpoint's `ip_allowlist` and keep `INBOUND_TRUST_PROXY_CLIENT_CERT=false`.

This directory holds the material for **outbound mTLS** (our client certificate towards peers):
set `OUTBOUND_TLS_KEYSTORE` (PKCS#12) and `OUTBOUND_TLS_KEYSTORE_PASSWORD`, and mount the keystore
into the dispatcher container. Ignored by git — never commit these files.
