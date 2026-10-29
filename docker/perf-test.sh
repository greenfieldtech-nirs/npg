#!/bin/bash
set -e
ENDPOINT=${1:-http://localhost:18081/np/inbound}
DURATION=${2:-30}
CONCURRENCY=${3:-10}

cat > /tmp/npg_check.xml <<'EOF'
<Check>
  <senderOperator>OP01</senderOperator>
  <receiverOperator>OP02</receiverOperator>
  <messageType>Check</messageType>
  <requestId>PERF-REQ</requestId>
  <transactionId>PERF-TX</transactionId>
  <protocolVersion>4.0</protocolVersion>
  <PhoneNumber>972501234567</PhoneNumber>
</Check>
EOF

# Requires apachebench (ab) or curl. Install ab if available.
if command -v ab > /dev/null 2>&1; then
  ab -T "application/xml" -p /tmp/npg_check.xml -t "$DURATION" -c "$CONCURRENCY" "$ENDPOINT"
else
  echo "apachebench (ab) not found; install with 'brew install httpd' or 'apt-get install apache2-utils'"
  exit 1
fi
