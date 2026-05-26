# Playbook 01 — Read an SMS verification code and forward it

**Goal:** the user's about to receive a 4–6 digit OTP via SMS; capture it, copy it to clipboard, and (optionally) auto-fill the active text input.

**Pre-reqs:** `tp-android permissions status` shows `READ_SMS` granted.

```sh
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

tp-android status start --task "Wait for SMS OTP and fill it" --total-steps 3

# Step 1 — let the user know we're armed
tp-android status step --step 1 --label "Arming SMS watcher" --state running

# Step 2 — block until a numeric body arrives, max 3 minutes
tp-android status step --step 2 --label "Waiting for OTP" --state running

SMS_JSON=$(tp-android sms wait --text-contains "" --wait-timeout 180 --raw || true)
if [ -z "$SMS_JSON" ] || [ "$(jq -r '.timeout // false' <<<"$SMS_JSON")" = "true" ]; then
  tp-android status error --code INPUT_TIMEOUT \
    --message "No SMS arrived within 3 minutes"
  tp-android status finish --state error --summary "no SMS"
  exit 1
fi

CODE=$(jq -r '.body // empty' <<<"$SMS_JSON" | grep -oE '[0-9]{4,8}' | head -n1 || true)
if [ -z "$CODE" ]; then
  RESP=$(tp-android status intervene \
    --reason "SMS arrived but I couldn't extract a code. Type it manually?" \
    --choices "I'll type it,Skip,Abort" \
    --wait-timeout 90 --raw)
  case "$(jq -r '.choice // empty' <<<"$RESP")" in
    "I'll type it")
      INPUT=$(tp-android status ask-input --prompt "Paste the OTP" --kind number --wait-timeout 90 --raw)
      CODE=$(jq -r '.value // empty' <<<"$INPUT")
      ;;
    *) tp-android status finish --state cancelled; exit 0 ;;
  esac
fi

tp-android status step --step 2 --label "Waiting for OTP" --state done --detail "code=$CODE"

# Step 3 — copy + try to fill the focused field
tp-android status step --step 3 --label "Filling code" --state running

tp-android clipboard set "$CODE"

FOCUSED=$(tp-android ui focused --raw 2>/dev/null || echo '{}')
if [ "$(jq -r '.className // empty' <<<"$FOCUSED")" = "android.widget.EditText" ]; then
  tp-android input text "$CODE"
else
  tp-android toast "OTP copied to clipboard: $CODE"
fi

tp-android status step --step 3 --label "Filling code" --state done
tp-android status finish --state success --summary "Filled OTP $CODE"
```

**Notes:**

- `sms wait` returns on the *first* matching message — keep the script short so the user's not waiting on the agent after the SMS lands.
- If you expect a known sender, narrow with `--from +1555…`.
- If the input field isn't an `EditText` (some apps wrap in a custom view), the toast fallback ensures the code is at least in the clipboard.
- Always end with `status finish` — even an `exit 1` should be preceded by `status finish --state error` so the HUD updates.
