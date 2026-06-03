---
name: tp-android
description: Control the Android device from inside Termux through the TermuxPlus `tp-android` CLI — screenshots, UI inspection (tree/find/focus/state), tap/swipe/type/key, launch/stop apps, clipboard, audio/screen recording, shared-storage files, APK install, notifications, SMS/call-log/contacts, device events, trigger rules that auto-run Termux commands, and any Termux:API-compatible device API. Reach for this skill even when the user just says "tap that button", "take a screenshot", "wait until WhatsApp shows N", or anything that needs to inspect or drive the phone.
---

# tp-android (short)

`tp-android` talks to the AutoTermux companion over a signature-protected local broadcast (the "bridge"). Most commands return a JSON envelope on stdout by default; diagnostics and automation guard notices go to stderr; errors set a non-zero exit code. `status ...` commands default to flat human-readable text because they are progress logs. Use `--json`/`--pretty` whenever a script or agent needs structured fields, `--raw` for scalar extraction, and `jq -r '.result...'` for structured selection.

## Pre-flight (run these in order at the start of any non-trivial run)

```sh
tp-android doctor --pretty           # bridge ping + a11y readiness
tp-android permissions status --pretty   # which perms still need granting
tp-android app current --pretty      # confirm what's actually foreground
tp-android status start --task "<one-line task>" --total-steps N
```

`status start` writes a run id to `~/.termuxplus/runs/current` and (when the HUD is up) shows it to the user. Read back for humans with `tp-android status current`; use `tp-android status current --json` for machine parsing.

## Workflow envelope — the 5 rules

1. **Any task with >2 logical steps MUST wrap with `status start / step / finish`.** Without that the user has no idea what you're doing in the background. See [reference/status-protocol.md](reference/status-protocol.md).
2. **Never guess when the screen is ambiguous — call `tp-android status intervene` / `ask-input` / `confirm` and route on the returned choice.** These block until the user answers or the timeout fires.
3. **Selectors over coordinates.** Prefer `--text` / `--text-contains` / `--resource-id` over raw `input tap X Y`. Coordinates break across screen sizes. Foreground-dependent `ui` / `input` commands now auto-hide Termux's floating terminal when it is in front, and restore it collapsed when the command exits; read the stderr notice if the target app still is not foreground.
4. **Always pass `--package <target>` when the target app is not the obvious foreground.** Termux's own UI is what gets dumped otherwise. `tp-android ui windows` and `app current` clarify.
5. **Branch on `error.code`, not `error.message`.** All errors carry a stable code + hint from [reference/errors.md](reference/errors.md). The message changes; the code does not.

## Three canonical patterns

**Linear:**
```sh
tp-android status start --task "Open Settings"
tp-android status step --step 1 --label "launch" --state running
tp-android app launch com.android.settings
tp-android app wait com.android.settings --wait-timeout 15
tp-android status step --step 1 --label "launch" --state done
tp-android status finish --state success --summary "settings opened"
```

**With intervention:**
```sh
tp-android status start --task "Login with SMS code"
# ... type username / tap login ...
RESP=$(tp-android status intervene --reason "Enter the SMS code I just sent" \
  --choices "I entered it,Resend,Cancel" --wait-timeout 120 --json)
echo "$RESP" | jq -r '.result.choice'
# branch in shell on the choice
```

**On error:**
```sh
if ! tp-android ui click --text "Continue" --verify-timeout 5 --pretty; then
  tp-android status error --code TARGET_NOT_FOUND \
    --message "Continue button not visible on login screen"
  tp-android ui digest --pretty   # so the agent can re-plan
fi
```

## What this skill does NOT do

- Pure-terminal work inside Termux (file edits, `pkg`, `pip`) — use the shell.
- Driving a different phone — `tp-android` talks to AutoTermux on the **same** device.
- Anything requiring root or system signature.
- Anything the user has not granted permissions for; check with `tp-android permissions status` first.

## Going deeper

- [reference/status-protocol.md](reference/status-protocol.md) — full `status` lifecycle, intervention shapes, HUD contract
- [reference/errors.md](reference/errors.md) — every error code, what it means, and the hint to act on
- [reference/full-reference.md](reference/full-reference.md) — the complete catalog of UI / input / app / files / triggers / events / device-APIs commands (this is the old long SKILL preserved verbatim)
- [playbooks/](playbooks/) — copy-pasteable recipes for common flows (SMS code, form fill with intervention, multi-step agent loop)

## Recovery cheat sheet

- `tp-android doctor --pretty` — always the first thing to try
- `tp-android install-companion` — reinstall AutoTermux from bundled asset
- Repeated `result=0` (`OEM_BATTERY_KILL`) → user must launch AutoTermux to the foreground and whitelist it in OEM battery settings, then batch your calls without long sleeps
- `A11Y_DISABLED` → ask the user to enable AutoTermux accessibility service in Settings; once `tp-android setup` ships you can run it as a one-shot guide
