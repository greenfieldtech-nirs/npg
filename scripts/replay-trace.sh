#!/usr/bin/env bash
# Replays the real operator trace (docs: docs/protocol) into the running dev stack.
# Uses a unique REQUEST_ID per run invocation so idempotency checks don't collide
# with previous replays.
set -euo pipefail

ENDPOINT="${ENDPOINT:-http://localhost:18081/np/inbound}"
REQUEST_ID="${REQUEST_ID:-NPHLBZ260924417320100}"
FIXTURES="backend/src/test/resources/simulation"

post() {
    local file="$1" from="$2" to="$3" type="$4" trx="$5"
    local response
    response=$(curl -s -X POST "$ENDPOINT" \
        -H "Content-Type: application/xml" \
        -H "X-NP-FROM: $from" \
        -H "X-NP-TO: $to" \
        -H "X-NP-MSG-TYPE: $type" \
        -H "X-NP-REQUEST-ID: $REQUEST_ID" \
        -H "X-NP-TRX-NO: $trx" \
        -H "X-NP-VERSION-NO: 1" \
        -H "X-NP-PROCESS-TYPE: PORT" \
        --data-binary @"$FIXTURES/$file")
    local status
    status=$(echo "$response" | grep -o '<Status>[^<]*' | sed 's/<Status>//')
    echo "$type ($from -> $to, trx $trx): $status"
}

# Porting lifecycle (single donor/recipient pair)
post 01-check.xml            HL BZ Check           HL000163454039
post 02-check-response.xml   BZ HL Check_response  BZ000959453468
post 03-request.xml          HL BZ Request         HL000163454222
post 04-request-response.xml BZ HL Request_response BZ000959453698
post 05-execute.xml          HL BZ Execute         HL000163464488
post 06-execute-response.xml BZ HL Execute_response BZ000959460823

# Publish fan-out (subset of the operators from the trace)
post 07-publish.xml HL HI Publish HL000163468556
post 08-publish-response.xml HI HL Publish_response HI000027281731
post 07-publish.xml HL BI Publish HL000163468601
post 08-publish-response.xml BI HL Publish_response BI000046742216
post 07-publish.xml HL GT Publish HL000163468628
post 08-publish-response.xml GT HL Publish_response GT000397047121
post 07-publish.xml HL TZ Publish HL000163468658
post 08-publish-response.xml TZ HL Publish_response TZ000123789053
post 07-publish.xml HL HT Publish HL000163468649
post 08-publish-response.xml HT HL Publish_response HT001949980790
