#!/usr/bin/env bash
# Runnable cart acceptance for docs/06_curl_testing.md.
#
# It creates a throw-away account named from the current timestamp, never prints the
# access token, and proves the optimistic-locking rule: a PATCH that carries an old
# cart row version must come back as HTTP 409, while the current version succeeds.
#
#   sbt "run 9000"          # in another shell
#   bash scripts/curl_cart_example.sh
#
# Override the target with `BASE_URL=http://127.0.0.1:8080 bash scripts/curl_cart_example.sh`.
set -euo pipefail

BASE_URL=${BASE_URL:-http://127.0.0.1:9000}
API=$BASE_URL/api/v1
GOODS_ID=${GOODS_ID:-12}
GOODS_QTY=${GOODS_QTY:-2}
RESPONSE=$(mktemp)
TOKEN=

# jq is optional here: Python does the JSON reads so the script runs anywhere.
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
else echo 'python3 (or python) is required to read the JSON responses' >&2; exit 2; fi

jget() { printf '%s' "$1" | "$PY" -c 'import json,sys;d=json.load(sys.stdin)
for k in sys.argv[1].split("."):
    d = d[int(k)] if isinstance(d, list) else d[k]
print("" if d is None else (json.dumps(d) if isinstance(d, (dict, list)) else d))' "$2" 2>/dev/null || true; }

PASS=0
FAIL=0

check() { # check LABEL ACTUAL EXPECTED
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf 'ok   %-44s %s\n' "$1" "$2"
  else FAIL=$((FAIL+1)); printf 'FAIL %-44s got %s want %s\n' "$1" "$2" "$3"; fi
}

api() { # api METHOD PATH [JSON_BODY] -> echoes the status code, body stays in $RESPONSE
  local method=$1 path=$2 body=${3-}
  local args=(-sS --noproxy '*' -o "$RESPONSE" -w '%{http_code}' -H 'Content-Type: application/json')
  [ -n "$TOKEN" ] && args+=(-H "Authorization: Bearer $TOKEN")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}" -X "$method" "$API$path"
}

STAMP=$(date +%s)
USER="cart$STAMP"
MAIL="cart$STAMP@example.test"
PASSWORD="correct-horse-battery-staple"

echo "== cart acceptance against $BASE_URL =="

check 'health probe'            "$(curl -sS --noproxy '*' -o "$RESPONSE" -w '%{http_code}' "$BASE_URL/healthz")" 200
check 'seeded goods is public'  "$(api GET "/goods/$GOODS_ID")" 200

# A fresh identity for every run, so cart rows and versions are never reused.
check 'register'                "$(api POST /auth/register \
  "{\"username\":\"$USER\",\"email\":\"$MAIL\",\"password\":\"$PASSWORD\"}")" 201
TOKEN=$(jget "$(cat "$RESPONSE")" access_token)
if [ -z "$TOKEN" ]; then echo 'register did not return an access_token' >&2; exit 1; fi
printf 'ok   %-44s %s\n' 'bearer token acquired' '(hidden)'

check 'cart starts empty'       "$(api GET /me/cart)" 200
check 'cart has no rows'        "$(jget "$(cat "$RESPONSE")" total_quantity)" 0

check 'add to cart'             "$(api POST /me/cart \
  "{\"goods_id\":$GOODS_ID,\"quantity\":$GOODS_QTY}")" 201
ITEM_ID=$(jget "$(cat "$RESPONSE")" id)
VERSION=$(jget "$(cat "$RESPONSE")" version)
check 'a new line starts at version 1'  "$(jget "$(cat "$RESPONSE")" version)" 1

# The row is now at $VERSION, so this PATCH must succeed and bump it.
check 'patch with current version' "$(api PATCH "/me/cart/$ITEM_ID" \
  "{\"quantity\":3,\"version\":$VERSION}")" 200
check 'quantity was updated'      "$(jget "$(cat "$RESPONSE")" quantity)" 3
check 'the row version moved on'  "$(jget "$(cat "$RESPONSE")" version)" 2

# Replay the same, now stale, version: the optimistic lock has to refuse it.
check 'patch with a stale version' "$(api PATCH "/me/cart/$ITEM_ID" \
  "{\"quantity\":5,\"version\":$VERSION}")" 409
check 'stale patch reports conflict' "$(jget "$(cat "$RESPONSE")" code)" "conflict"

check 'unknown fields are refused' "$(api POST /me/cart \
  "{\"goods_id\":$GOODS_ID,\"quantity\":1,\"quntity\":1}")" 400
check 'cart is readable'            "$(api GET /me/cart)" 200
check 'line delete'                 "$(api DELETE "/me/cart/$ITEM_ID")" 204
check 'delete twice is not found'   "$(api DELETE "/me/cart/$ITEM_ID")" 404

# Without the token the same cart must be unreachable.
SAVED=$TOKEN; TOKEN=
check 'cart needs a bearer token'   "$(api GET /me/cart)" 401
check 'anonymous cart add'          "$(api POST /me/cart "{\"goods_id\":$GOODS_ID,\"quantity\":1}")" 401
TOKEN=$SAVED

rm -f "$RESPONSE"
printf '\npassed=%d failed=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
