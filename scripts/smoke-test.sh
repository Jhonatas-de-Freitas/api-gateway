#!/usr/bin/env bash
# Checks the stack started by docker compose from the outside, the way a client would:
# real tokens from Keycloak, real requests through the gateway. Needs curl and jq.
set -euo pipefail

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8080}
KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8180}
WIREMOCK_URL=${WIREMOCK_URL:-http://localhost:8089}

ORDER_ID=7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c
KEYBOARD_ID=11111111-1111-1111-1111-111111111111
NEW_ORDER='{"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com","items":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}]}'

cd "$(dirname "$0")/.."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

pass() {
  echo "ok    $*"
}

fail() {
  echo "FAIL  $*" >&2
  exit 1
}

# call METHOD PATH [curl options]: leaves the answer in $status, $seconds, $work/body and $work/headers
call() {
  local method=$1 path=$2 result
  shift 2
  result=$(curl -sS -o "$work/body" -D "$work/headers" -w '%{http_code} %{time_total}' -X "$method" "$@" "$GATEWAY_URL$path")
  status=${result% *}
  seconds=${result#* }
}

as() {
  echo "Authorization: Bearer $1"
}

header() {
  grep -i "^$1:" "$work/headers" | tail -n 1 | cut -d ' ' -f 2- | tr -d '\r'
}

expect_status() {
  local expected=$1 description=$2
  [[ $status == "$expected" ]] || fail "$description: expected $expected, got $status $(cat "$work/body")"
  pass "$description -> $status"
}

expect_problem() {
  local expected=$1 path=$2 description=$3
  [[ $status == "$expected" ]] || fail "$description: expected $expected, got $status $(cat "$work/body")"
  [[ $(header content-type) == application/problem+json* ]] \
    || fail "$description: content type is '$(header content-type)'"
  jq -e --argjson status "$expected" --arg path "$path" \
    '.type == "about:blank" and .status == $status and .instance == $path
      and (.title | length > 0) and (.detail | length > 0)' "$work/body" > /dev/null \
    || fail "$description: not the expected problem: $(cat "$work/body")"
  pass "$description -> $status problem+json"
}

expect_header() {
  local name=$1 expected=$2
  [[ $(header "$name") == "$expected" ]] || fail "header $name: expected '$expected', got '$(header "$name")'"
}

faster_than() {
  awk -v took="$seconds" -v limit="$1" 'BEGIN { exit !(took < limit) }'
}

token_for() {
  curl -fsS "$KEYCLOAK_URL/realms/store/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=store-web -d "username=$1" -d "password=$2" \
    | jq -er .access_token
}

claim() {
  jq -Rr --arg claim "$2" \
    'split(".")[1] | gsub("-"; "+") | gsub("_"; "/") | . + "=="[:(4 - length % 4) % 4]
      | @base64d | fromjson | .[$claim]' <<< "$1"
}

echo "== tokens"
ana=$(token_for ana demo123)
admin=$(token_for admin admin123)
[[ $(claim "$ana" iss) == http://localhost:8180/realms/store ]] || fail "issuer is $(claim "$ana" iss)"
pass "ana and admin logged in at $KEYCLOAK_URL, issuer $(claim "$ana" iss)"

echo "== access rules"
call GET /api/products
expect_status 200 "GET /api/products without a token"
jq -e 'length == 6' "$work/body" > /dev/null || fail "expected the 6 sample products: $(cat "$work/body")"

call GET /api/orders
expect_problem 401 /api/orders "GET /api/orders without a token"
expect_header www-authenticate Bearer

call GET /api/orders -H "$(as "$ana")"
expect_status 200 "GET /api/orders as ana"

call PUT "/api/products/$KEYBOARD_ID/stock" -H "$(as "$ana")" -H 'Content-Type: application/json' -d '{"availableQuantity": 50}'
expect_problem 403 "/api/products/$KEYBOARD_ID/stock" "PUT /api/products/{id}/stock as ana"

call PUT "/api/products/$KEYBOARD_ID/stock" -H "$(as "$admin")" -H 'Content-Type: application/json' -d '{"availableQuantity": 50}'
expect_status 200 "PUT /api/products/{id}/stock as admin"

call GET /api/analytics/summary -H "$(as "$ana")"
expect_problem 403 /api/analytics/summary "GET /api/analytics/summary as ana"

call GET /api/analytics/summary -H "$(as "$admin")"
expect_status 200 "GET /api/analytics/summary as admin"

echo "== identity and correlation id"
curl -fsS -X DELETE "$WIREMOCK_URL/__admin/requests" > /dev/null
call GET "/api/orders/$ORDER_ID" -H "$(as "$ana")" -H 'X-User-Id: someone-else' -H 'X-User-Roles: admin' -H 'X-Correlation-Id: smoke-test-42'
expect_status 200 "GET /api/orders/{id} as ana, claiming to be someone else"
expect_header x-correlation-id smoke-test-42
curl -fsS "$WIREMOCK_URL/__admin/requests" \
  | jq '.requests[0].request.headers | with_entries(.key |= ascii_downcase)' > "$work/received"
jq -e --arg sub "$(claim "$ana" sub)" \
  '.["x-user-id"] == $sub and .["x-user-name"] == "Ana Souza" and .["x-user-email"] == "ana@exemplo.com"
    and (.["x-user-roles"] | split(",") | index("customer") != null and index("admin") == null)
    and .["x-correlation-id"] == "smoke-test-42"' "$work/received" > /dev/null \
  || fail "the service received $(cat "$work/received")"
pass "the service received ana's identity from the token, not the forged headers"

call GET /api/products
[[ -n $(header x-correlation-id) ]] || fail "no X-Correlation-Id in the response"
pass "X-Correlation-Id created when missing: $(header x-correlation-id)"

echo "== order stream"
call GET /api/orders/stream --no-buffer --max-time 30 -H "$(as "$ana")" -H 'Accept: text/event-stream'
expect_status 200 "GET /api/orders/stream as ana"
events=$(grep -c '^event: order-updated' "$work/body" || true)
[[ $events == 3 ]] || fail "expected 3 events, got $events"
# The other routes give up on a service after 2 s. The sample stream takes 6 s to send everything.
if faster_than 3; then fail "the stream ended after ${seconds}s"; fi
pass "3 events relayed over ${seconds}s"

echo "== rate limit"
burst=15
for n in $(seq 1 $burst); do
  curl -sS -o "$work/order-$n.body" -D "$work/order-$n.headers" -w '%{http_code}\n' -X POST \
    -H "$(as "$ana")" -H 'Content-Type: application/json' -d "$NEW_ORDER" \
    "$GATEWAY_URL/api/orders" > "$work/order-$n.status" &
done
wait
accepted=$(cat "$work"/order-*.status | grep -c '^202$' || true)
refused=$(cat "$work"/order-*.status | grep -c '^429$' || true)
(( accepted + refused == burst )) || fail "unexpected answers in the burst: $(cat "$work"/order-*.status | sort | uniq -c | xargs)"
(( accepted >= 5 && refused >= 1 )) || fail "burst of $burst orders: $accepted accepted, $refused refused"
pass "burst of $burst POST /api/orders as ana: $accepted accepted, $refused refused"

refused_order=$(grep -lx 429 "$work"/order-*.status | head -n 1)
cp "${refused_order%.status}.body" "$work/body"
cp "${refused_order%.status}.headers" "$work/headers"
status=429
expect_problem 429 /api/orders "refused order"
expect_header x-ratelimit-remaining 0
expect_header x-ratelimit-burst-capacity 5
expect_header x-ratelimit-replenish-rate 2
expect_header retry-after 1

call POST /api/orders -H "$(as "$admin")" -H 'Content-Type: application/json' -d "$NEW_ORDER"
expect_status 202 "POST /api/orders as admin right after"

echo "== service down"
docker compose stop wiremock
for attempt in 1 2 3 4 5 6; do
  call GET "/api/payments/$ORDER_ID" -H "$(as "$ana")"
  expect_problem 503 "/api/payments/$ORDER_ID" "GET /api/payments/{id} with the service down, in ${seconds}s"
  faster_than 3 || fail "the 503 took ${seconds}s"
done

docker compose up -d --wait wiremock
for attempt in $(seq 1 60); do
  call GET "/api/payments/$ORDER_ID" -H "$(as "$ana")"
  [[ $status == 200 ]] && break
  sleep 1
done
expect_status 200 "GET /api/payments/{id} after the service came back"

echo
echo "All checks passed."
