# `tp-android` error codes

Every failure emits an envelope of the shape:

```json
{
  "ok": false,
  "schema_version": "tp-android.v1",
  "error": {
    "code": "TARGET_NOT_FOUND",
    "message": "UI action verification failed after 5s",
    "hint": "Selector matched nothing. Run: tp-android ui digest (or ui dump) to inspect current screen.",
    "recoverable": true,
    "details": { "checks": [...], "expectedText": "Continue" }
  }
}
```

The contract:

- **`code`** is stable. Branch on this. Add new codes in `tp-android` source if a new failure category emerges; never repurpose a code.
- **`message`** is for humans. Do not parse it.
- **`hint`** is a one-line, directly-actionable suggestion. Often it is a literal command the user or agent can run.
- **`recoverable`** is a hint that the agent can retry/work-around without aborting. `false` means "no point trying again from here without changing something".
- **`details`** is free-form structured context — `selector`, `bounds`, `current_package`, etc. Read it when debugging; don't depend on field names being stable across versions.

## Codes

| code | when | recoverable | how to act |
|---|---|---|---|
| `CLI_ERROR` | generic fallback (mostly bad CLI args) | no | re-read `tp-android <cmd> --help` |
| `INTERRUPTED` | Ctrl-C from terminal | no | user-initiated |
| `AUTOTERMUX_KILLED` | bridge replied `result=0` with no payload | yes | ask the user to foreground AutoTermux and whitelist in OEM battery settings; then batch calls |
| `TERMUX_NOT_FOREGROUND` | Termux backgrounded and died | yes | run `tp-android keepalive ensure --restart` |
| `A11Y_DISABLED` | accessibility service off | yes | open Settings → Accessibility → AutoTermux; or `tp-android setup` |
| `A11Y_TIMEOUT` | a11y service alive but unresponsive | yes | toggle a11y off/on; or reboot AutoTermux |
| `NOTIF_LISTENER_DISABLED` | notification listener off | yes | Settings → Notifications → Special access → AutoTermux |
| `PERMISSION_MISSING` | a runtime permission denied | yes | `tp-android permissions status --pretty` then re-grant |
| `OVERLAY_BLOCKED` | SYSTEM_ALERT_WINDOW denied | yes | Settings → Special access → Display over other apps → AutoTermux |
| `SCREENSHOT_CONSENT_DENIED` | MediaProjection consent cancelled | yes | retry — Android will re-prompt |
| `TARGET_NOT_FOUND` | selector matched nothing | yes | `tp-android ui digest` to see what's available; switch selector or wait longer |
| `TARGET_NOT_CLICKABLE` | element found but `clickable=false` / disabled | yes | inspect `actions` field; try `--idx` or parent node |
| `SELECTOR_AMBIGUOUS` | selector matched >1 nodes when 1 was expected | yes | tighten with `--resource-id`, `--package`, or `--bounds` |
| `USER_CANCELLED` | user pressed Cancel on the HUD | no | run is over; do not retry |
| `USER_INTERRUPTED` | user touched the screen mid-flow | yes | `status intervene` to ask whether to continue |
| `INPUT_TIMEOUT` | a `wait` command expired | yes | increase `--wait-timeout` or verify pre-conditions (target package, screen on, etc.) |
| `BINDER_OVERFLOW` | payload >~1 MB Binder cap | yes | re-run with `--cache`, or tighten `--limit` |
| `OEM_BATTERY_KILL` | repeated `result=0` due to OEM aggressive kill | yes | foreground AutoTermux + battery whitelist |
| `TERMUX_PROP_MISSING` | `allow-external-apps=true` not set in `~/.termux/termux.properties` | yes | add it, run `termux-reload-settings` |
| `BRIDGE_PARSE_FAILED` | could not parse AutoTermux's reply | yes | `tp-android doctor` |
| `BRIDGE_RETURNED_ERROR` | AutoTermux explicitly returned `status=error` | yes | check `details` for the server-side cause |
| `COMPANION_NOT_INSTALLED` | AutoTermux APK absent | yes | `tp-android install-companion` |

## Agent self-heal recipes

```sh
# Generic retry-after-doctor loop, capped at 2 attempts
for attempt in 1 2; do
  if tp-android ui click --text "Login" --pretty; then break; fi
  CODE=$(jq -r '.error.code' <<<"$(tp-android ui click --text "Login" 2>&1)" || echo "")
  case "$CODE" in
    A11Y_DISABLED|AUTOTERMUX_KILLED|TERMUX_NOT_FOREGROUND)
      tp-android keepalive ensure --restart --pretty || true
      sleep 1 ;;
    TARGET_NOT_FOUND)
      tp-android ui digest --pretty   # let the LLM see what's there
      tp-android status intervene --reason "Cannot find Login button — what now?" \
        --choices "retry,take over,abort" --wait-timeout 60 --raw
      break ;;
    *) break ;;
  esac
done
```

Don't write retry loops that don't branch on the code — silent retries waste tokens and time, and mask configuration problems the user could fix in one tap.

## When you need a new code

If you find yourself raising `CLI_ERROR` for a category that an agent could meaningfully branch on, add a new entry to `ERROR_CODES` in `bootstrap/bin/tp-android`. Keep the hint actionable, set `recoverable` honestly, and update this table.
