#!/usr/bin/env bash
# Shopper-visible sale checks against a running Boot process, PostgreSQL, and Kafka.
# This script is the only client while it runs. It resets the pool and deletes customers.
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
root="$(cd "$(dirname "$0")/.." && pwd)"
topic="inventory.order-accepted"
run="live-$(date +%s)-$$"

usage() {
	cat <<EOF
Usage: live-sale-checks.sh [options]

Shopper-visible sale checks against a running Boot process, PostgreSQL, and Kafka.
The script is the only client while it runs. It resets the pool to 10 and deletes
customer rows. Compose must already be up.

Options:
  -h, --help           Show this help and exit
  --base-url URL       Page to call. Default: ${BASE_URL:-http://localhost:8080}
                       Same setting as the BASE_URL environment variable.
                       A flag wins over BASE_URL.

Checks, in order:
  quantity 0                     The order was not accepted. Pool stays 10.
  negative quantity              The order was not accepted. Pool stays 10.
  three units, one user          The order is in process. Pool 9, then 8, then 7.
  same user, still in the hour   Wait one hour before ordering again.
  same user, hour elapsed        The order is refused.
  another user, quantity 3       The order is in process. Pool down by 3.
  pool set to 1, quantity 3      The order is in process. One unit kept. Pool 0.
  same user, pool empty          The item has sold out.
  blank user id                  The order was not accepted.

A quantity of 10 and a sold-out answer means the process still holds the
in-memory hint. Restart Boot and run the script again.
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		-h|--help)
			usage
			exit 0
			;;
		--base-url)
			[[ $# -ge 2 ]] || { echo "live-sale-checks.sh: --base-url needs a URL" >&2; exit 2; }
			base_url="$2"
			shift 2
			;;
		--base-url=*)
			base_url="${1#--base-url=}"
			[[ -n "$base_url" ]] || { echo "live-sale-checks.sh: --base-url needs a URL" >&2; exit 2; }
			shift
			;;
		*)
			echo "live-sale-checks.sh: unknown option: $1" >&2
			echo "Try live-sale-checks.sh --help" >&2
			exit 2
			;;
	esac
done

cd "$root"

psql_q() {
	docker compose exec -T postgres \
		psql -U flashsale -d flashsale -v ON_ERROR_STOP=1 -tA -c "$1"
}

fail() {
	echo "FAIL: $*" >&2
	exit 1
}

quantity() {
	psql_q "SELECT quantity FROM item WHERE id = 'item-1'"
}

units_of() {
	psql_q "SELECT coalesce((SELECT units_ordered FROM customer WHERE user_id = '$1'), -1)"
}

end_offset() {
	local line
	# Git Bash rewrites a leading /opt into a Windows path. Kafka runs inside the container.
	line="$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker compose exec -T kafka \
		/opt/kafka/bin/kafka-get-offsets.sh \
		--bootstrap-server kafka:19092 \
		--topic "$topic")"
	line="$(printf '%s\n' "$line" | tr -d '\r' | awk -F: '$1 == "'"$topic"'" && $2 == 0 { print $3; exit }')"
	[[ -n "$line" ]] || fail "could not read the Kafka end offset"
	printf '%s' "$line"
}

post_order() {
	curl -fsS --max-time 30 -X POST "$base_url/" \
		--data-urlencode "userId=$1" \
		--data-urlencode "quantity=$2"
}

require_sentence() {
	local body="$1"
	local sentence="$2"
	grep -F -q -- "$sentence" <<<"$body" || fail "page did not say: $sentence"
}

forbid_sentence() {
	local body="$1"
	local sentence="$2"
	grep -F -q -- "$sentence" <<<"$body" && fail "page also said: $sentence" || true
}

require_quiet() {
	local before="$1"
	local after
	sleep 5
	after="$(end_offset)"
	[[ "$before" == "$after" ]] || fail "Kafka moved from $before to $after"
}

require_event() {
	local before="$1"
	local user_id="$2"
	local units="$3"
	local record key value order_id deducted
	record="$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker compose exec -T kafka \
		/opt/kafka/bin/kafka-console-consumer.sh \
		--bootstrap-server kafka:19092 \
		--topic "$topic" \
		--partition 0 \
		--offset "$before" \
		--max-messages 1 \
		--timeout-ms 8000 \
		--formatter-property print.key=true \
		--formatter-property "key.separator=$(printf '\t')" \
		| tr -d '\r' | awk 'index($0, "{") { print; exit }')"
	[[ -n "$record" ]] || fail "no Kafka record for $user_id"
	key="${record%%$'\t'*}"
	value="${record#*$'\t'}"
	[[ "$value" == *"$user_id"* ]] || fail "Kafka record was not for $user_id: $value"
	[[ "$value" == *'"itemId":"item-1"'* || "$value" == *'"itemId": "item-1"'* ]] || fail "Kafka item id was not item-1: $value"
	order_id="$(printf '%s' "$value" | sed -n 's/.*"orderId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
	deducted="$(printf '%s' "$value" | sed -n 's/.*"unitsDeducted"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
	[[ -n "$order_id" && "$key" == "$order_id" ]] || fail "Kafka key was not orderId: $record"
	[[ "$deducted" == "$units" ]] || fail "unitsDeducted was $deducted, expected $units"
	[[ "$(end_offset)" == "$((before + 1))" ]] || fail "expected one new Kafka record after offset $before"
}

expect_accepted() {
	local user_id="$1"
	local requested="$2"
	local pool="$3"
	local units="$4"
	local deducted="$5"
	local before body
	before="$(end_offset)"
	body="$(post_order "$user_id" "$requested")"
	require_sentence "$body" "The order is in process"
	[[ "$(quantity)" == "$pool" ]] || fail "$user_id left quantity $(quantity), expected $pool"
	[[ "$(units_of "$user_id")" == "$units" ]] || fail "$user_id units were $(units_of "$user_id"), expected $units"
	require_event "$before" "$user_id" "$deducted"
	echo "ok  in process  $user_id  pool=$pool  units=$units  deducted=$deducted"
}

expect_quiet() {
	local user_id="$1"
	local requested="$2"
	local sentence="$3"
	local pool="$4"
	local units="$5"
	local before body
	before="$(end_offset)"
	body="$(post_order "$user_id" "$requested")"
	require_sentence "$body" "$sentence"
	if [[ "$sentence" == "The item has sold out" ]]; then
		forbid_sentence "$body" "Wait one hour before ordering again"
	fi
	[[ "$(quantity)" == "$pool" ]] || fail "quantity $(quantity), expected $pool"
	if [[ "$units" == "none" ]]; then
		[[ "$(units_of "$user_id")" == "-1" ]] || fail "$user_id created a customer row"
	else
		[[ "$(units_of "$user_id")" == "$units" ]] || fail "$user_id units were $(units_of "$user_id"), expected $units"
	fi
	require_quiet "$before"
	echo "ok  quiet  $sentence"
}

if ! curl -fsS --max-time 5 -o /dev/null "$base_url/"; then
	echo "Boot is not reachable at $base_url" >&2
	exit 1
fi

psql_q "UPDATE item SET quantity = 10 WHERE id = 'item-1'" >/dev/null
psql_q "DELETE FROM customer" >/dev/null
[[ "$(quantity)" == "10" ]] || fail "reset left quantity $(quantity)"

zero_user="$run-zero"
negative_user="$run-negative"
alex="$run-alex"
second="$run-second"
short_user="$run-short"

expect_quiet "$zero_user" 0 "The order was not accepted" 10 none
expect_quiet "$negative_user" -1 "The order was not accepted" 10 none

before="$(end_offset)"
body="$(post_order "$alex" 1)"
if grep -F -q -- "The item has sold out" <<<"$body"; then
	echo "Restart the process so the in-memory sold-out hint clears." >&2
	exit 1
fi
require_sentence "$body" "The order is in process"
[[ "$(quantity)" == "9" ]] || fail "first order left quantity $(quantity)"
[[ "$(units_of "$alex")" == "1" ]] || fail "first order left units $(units_of "$alex")"
require_event "$before" "$alex" 1
echo "ok  in process  $alex  pool=9  units=1  deducted=1"

expect_accepted "$alex" 1 8 2 1
expect_accepted "$alex" 1 7 3 1
expect_quiet "$alex" 1 "Wait one hour before ordering again" 7 3

# next_allowed is timestamp without time zone. A one-second offset taken in psql's
# session can still be in the future for the JVM session. A fixed past instant is not.
psql_q "UPDATE customer SET next_allowed = TIMESTAMP '2000-01-01 00:00:00' WHERE user_id = '$alex'" >/dev/null
expect_quiet "$alex" 1 "The order is refused" 7 3

expect_accepted "$second" 3 4 3 3

psql_q "UPDATE item SET quantity = 1 WHERE id = 'item-1'" >/dev/null
expect_accepted "$short_user" 3 0 1 1
expect_quiet "$short_user" 1 "The item has sold out" 0 1
expect_quiet "" 1 "The order was not accepted" 0 none

[[ "$(quantity)" == "0" ]] || fail "finished at quantity $(quantity)"
[[ "$(psql_q "SELECT count(*) FROM customer")" == "3" ]] || fail "expected 3 customer rows"
[[ "$(psql_q "SELECT count(*) FROM customer WHERE units_ordered > 3")" == "0" ]] || fail "a customer ordered more than 3"

echo "live sale checks passed"
