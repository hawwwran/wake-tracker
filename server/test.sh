#!/usr/bin/env bash
# Walks through everything a phone asks of the relay. Needs curl, and php for the first form.
#
#   server/test.sh                         against this computer: PHP's built-in server on a
#                                          throwaway copy with its own data folder
#   server/test.sh https://relay.example   against a relay that is deployed; the device it
#                                          registers for the purpose is removed again
#
# Exit 0 when every check passed.
set -u
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
live=${1:-}
server=""
cleanup() {
    # A run that broke off halfway must not leave its device on a real relay.
    [ -z "${device:-}" ] || curl -s -o /dev/null -X DELETE -H "Authorization: Bearer ${write:-}" "$api?a=device&device=$device"
    [ -z "$server" ] || kill "$server" 2>/dev/null
    rm -rf "$work"
}
trap cleanup EXIT

if [ -n "$live" ]; then
    api="${live%/}/api.php"
else
    port=$((20000 + RANDOM % 20000))
    cp "$here"/api.php "$here"/VERSION.md "$work"/
    php -S "127.0.0.1:$port" -t "$work" >"$work/server.log" 2>&1 &
    server=$!
    api="http://127.0.0.1:$port/api.php"
    for _ in $(seq 50); do curl -s -o /dev/null "$api?a=hello" && break; sleep 0.1; done
fi
failed=0
# check NAME EXPECTED_STATUS [EXPECTED_BODY_PART] -- CURL ARGS...
check() {
    local name=$1 status=$2 part=""
    shift 2
    if [ "$1" != "--" ]; then part=$1; shift; fi
    shift
    local got body
    got=$(curl -s -m 30 -o "$work/body" -w '%{http_code}' "$@")
    body=$(head -c 300 "$work/body" | tr -d '\0')
    if [ "$got" != "$status" ] || { [ -n "$part" ] && [[ "$body" != *"$part"* ]]; }; then
        echo "FAIL $name: status $got (wanted $status), body: $body"
        failed=1
    else
        echo "ok   $name"
    fi
}
token() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '='; }

device=$(head -c 16 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')
other=$(head -c 16 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')
write=$(token); read=$(token)
head -c 4124 /dev/urandom > "$work/day1"
head -c 8220 /dev/urandom > "$work/day1b"
head -c 300000 /dev/urandom > "$work/huge"

check "hello" 200 '"service":"wake-tracker"' -- "$api?a=hello"
check "hello says its version" 200 '"version":"' -- "$api?a=hello"

check "upload before registering" 404 unknown_device -- -X PUT -H "Authorization: Bearer $write" --data-binary "@$work/day1" "$api?a=blob&device=$device&name=d20779"
check "register" 200 '"registered":true' -- -X POST -d "{\"write\":\"$write\",\"read\":\"$read\"}" "$api?a=register&device=$device"
check "register again, same tokens" 200 -- -X POST -d "{\"write\":\"$write\",\"read\":\"$read\"}" "$api?a=register&device=$device"
check "register again, other tokens" 409 device_taken -- -X POST -d "{\"write\":\"$(token)\",\"read\":\"$read\"}" "$api?a=register&device=$device"
check "register with a malformed token" 400 bad_tokens -- -X POST -d '{"write":"short","read":"short"}' "$api?a=register&device=$other"
check "malformed device id" 400 bad_device -- -X POST -d '{}' "$api?a=register&device=../etc"

check "upload" 200 '"version":1' -- -X PUT -H "Authorization: Bearer $write" --data-binary "@$work/day1" "$api?a=blob&device=$device&name=d20779"
check "upload with the read token" 403 wrong_token -- -X PUT -H "Authorization: Bearer $read" --data-binary "@$work/day1" "$api?a=blob&device=$device&name=d20779"
check "upload without a token" 403 wrong_token -- -X PUT --data-binary "@$work/day1" "$api?a=blob&device=$device&name=d20779"
check "upload under a made-up name" 400 bad_name -- -X PUT -H "Authorization: Bearer $write" --data-binary "@$work/day1" "$api?a=blob&device=$device&name=../auth"
check "upload too large" 413 too_large -- -X PUT -H "Authorization: Bearer $write" --data-binary "@$work/huge" "$api?a=blob&device=$device&name=d20780"
check "upload meta" 200 '"version":2' -- -X PUT -H "Authorization: Bearer $write" --data-binary "@$work/day1" "$api?a=blob&device=$device&name=meta"

check "changes from the start" 200 '"version":2' -- -H "Authorization: Bearer $read" "$api?a=changes&device=$device&since=0"
check "changes list both files" 200 '{"name":"d20779","version":1,"size":4124},{"name":"meta","version":2,"size":4124}' -- -H "Authorization: Bearer $read" "$api?a=changes&device=$device&since=0"
check "changes with the write token" 403 wrong_token -- -H "Authorization: Bearer $write" "$api?a=changes&device=$device&since=0"
check "changes of an unknown device" 404 unknown_device -- -H "Authorization: Bearer $read" "$api?a=changes&device=$other&since=0"

check "download" 200 -- -H "Authorization: Bearer $read" "$api?a=blob&device=$device&name=d20779"
cmp -s "$work/body" "$work/day1" && echo "ok   download is what was uploaded" || { echo "FAIL download differs from the upload"; failed=1; }
check "download with the write token" 403 wrong_token -- -H "Authorization: Bearer $write" "$api?a=blob&device=$device&name=d20779"
check "download of a day never uploaded" 404 unknown_blob -- -H "Authorization: Bearer $read" "$api?a=blob&device=$device&name=d1"

check "upload the day again, grown" 200 '"version":3' -- -X PUT -H "Authorization: Bearer $write" --data-binary "@$work/day1b" "$api?a=blob&device=$device&name=d20779"
check "changes since the last look" 200 '"blobs":[{"name":"d20779","version":3,"size":8220}]' -- -H "Authorization: Bearer $read" "$api?a=changes&device=$device&since=2"
check "nothing new" 200 '"blobs":[]' -- -H "Authorization: Bearer $read" "$api?a=changes&device=$device&since=3"

check "forget the device with the read token" 403 wrong_token -- -X DELETE -H "Authorization: Bearer $read" "$api?a=device&device=$device"
check "forget the device" 200 '"deleted":true' -- -X DELETE -H "Authorization: Bearer $write" "$api?a=device&device=$device"
check "gone afterwards" 404 unknown_device -- -H "Authorization: Bearer $read" "$api?a=changes&device=$device&since=0"
if [ -z "$live" ]; then
    [ ! -e "$work/data/$device" ] && echo "ok   its files are removed" || { echo "FAIL files left behind"; failed=1; }
else
    # What a web server must not hand out, whatever .htaccess it honours.
    check "the data folder is closed to the web" 403 -- "${live%/}/data/"
    check "the version file is closed to the web" 403 -- "${live%/}/VERSION.md"
fi
check "unknown action" 404 unknown_action -- "$api?a=nope&device=$device"

if [ "$failed" = 0 ]; then
    echo "All relay checks passed."
else
    echo "Some relay checks FAILED."
    [ -n "$live" ] || tail -5 "$work/server.log"
fi
exit "$failed"
