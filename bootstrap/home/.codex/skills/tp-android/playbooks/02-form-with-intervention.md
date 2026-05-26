# Playbook 02 — Fill a form, confirm before submit

**Goal:** open a target app, fill a multi-field form from variables, and ask the user to OK the filled values before tapping Submit. Demonstrates the *always-confirm-before-irreversible-action* pattern.

```sh
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Arguments expected: NAME, EMAIL, NOTE
: "${NAME:?usage: NAME=… EMAIL=… NOTE=… $0}"
: "${EMAIL:?}"
: "${NOTE:?}"

TARGET=com.example.contactform   # replace with the real package

tp-android status start --task "Fill contact form for $NAME" --total-steps 5

# 1. Launch + verify foreground
tp-android status step --step 1 --label "Launch app" --state running
tp-android app launch "$TARGET"
tp-android app wait "$TARGET" --wait-timeout 15
tp-android status step --step 1 --label "Launch app" --state done

# 2. Find the Name field and fill it (selector first, coord-tap is forbidden here)
tp-android status step --step 2 --label "Fill Name" --state running
tp-android ui wait --resource-id "$TARGET:id/name" --wait-timeout 10
tp-android ui action set-text --resource-id "$TARGET:id/name" --value "$NAME"
tp-android status step --step 2 --label "Fill Name" --state done

# 3. Email
tp-android status step --step 3 --label "Fill Email" --state running
tp-android ui action set-text --resource-id "$TARGET:id/email" --value "$EMAIL"
tp-android status step --step 3 --label "Fill Email" --state done

# 4. Note (multi-line). Stdin route handles newlines safely.
tp-android status step --step 4 --label "Fill Note" --state running
tp-android ui action set-text --resource-id "$TARGET:id/note" --value "$NOTE"
tp-android status step --step 4 --label "Fill Note" --state done

# 5. Confirm before submit (irreversible)
tp-android status step --step 5 --label "Confirm + submit" --state running

CONFIRM=$(tp-android status confirm \
  --prompt "Send the form to ${EMAIL}? (review the screen first)" \
  --wait-timeout 60 --raw)

if [ "$(jq -r '.confirmed // false' <<<"$CONFIRM")" != "true" ]; then
  tp-android status step --step 5 --label "Confirm + submit" --state skipped --detail "user declined"
  tp-android status finish --state cancelled --summary "user declined submit"
  exit 0
fi

tp-android ui click --text "Submit" --expect-text "Sent" --verify-timeout 8

tp-android status step --step 5 --label "Confirm + submit" --state done
tp-android status finish --state success --summary "Form sent to $EMAIL"
```

**Why this shape:**

- Each field gets its own `step` so the HUD shows clear progress (and the user can see *which* field failed if something goes wrong).
- `set-text` via accessibility goes through the focused EditText's `ACTION_SET_TEXT` — survives autocorrect, emoji, and the "soft keyboard not showing" case that `input text` falls afoul of.
- `confirm` is **non-bypassable**. If you find yourself wanting to add `|| true` to skip it, that's a sign the task isn't actually ready to be one-click automated yet.
- `ui click --expect-text "Sent" --verify-timeout 8` is the post-action check. If "Sent" doesn't show up, the click is treated as failed and you'll get a `TARGET_NOT_FOUND` error you can branch on.
