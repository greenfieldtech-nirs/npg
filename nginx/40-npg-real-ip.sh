#!/bin/sh
# Runs from the nginx image's /docker-entrypoint.d before NGINX starts.
# Trusts X-Forwarded-For only from the load balancer (LB_TRUSTED_CIDRS, comma or space separated), so
# rate limits and the backend see the real client address and nobody else can forge it.
# Missing or malformed values stop the container: without them every client would look like the LB.
set -eu

: "${LB_TRUSTED_CIDRS:?set LB_TRUSTED_CIDRS to the load balancer source addresses (CIDRs)}"

out=/etc/nginx/conf.d/00-real-ip.conf
tmp="$out.tmp"
echo "# generated at start from LB_TRUSTED_CIDRS" > "$tmp"
for cidr in $(echo "$LB_TRUSTED_CIDRS" | tr ',' ' '); do
    # only IPv4/IPv6 addresses and prefixes: nothing that could inject NGINX directives
    case "$cidr" in
        *[!0-9A-Fa-f.:/]*) echo "LB_TRUSTED_CIDRS: invalid entry '$cidr'" >&2; exit 1 ;;
    esac
    echo "set_real_ip_from $cidr;" >> "$tmp"
done
echo "real_ip_header X-Forwarded-For;" >> "$tmp"
echo "real_ip_recursive on;" >> "$tmp"
mv "$tmp" "$out"
