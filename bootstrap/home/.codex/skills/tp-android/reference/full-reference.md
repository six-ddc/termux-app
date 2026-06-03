# tp-android — full reference

> Detailed command catalog. The short SKILL.md at the parent directory holds the 80-line essentials; this file preserves the long-form examples organized by domain. Look here when you need an exact flag or a less common command.

`tp-android` is the canonical Android-control CLI installed in Termux on TermuxPlus. Use it instead of ad hoc `adb`, `input`, `am`, or screen-scraping — those don't run from inside the Termux app context anyway, and `tp-android` gives typed args, proper bridge IPC, and explicit `--json`/`--pretty` modes for structured output.

## Why bridge-only

`tp-android` always talks to the AutoTermux companion app over a signature-protected local broadcast (the "bridge"). It does **not** open or rely on an HTTP/WebSocket server. AutoTermux still ships HTTP/WS code internally but the CLI does not expose any way to enable, query, or control those servers — you cannot start them, stop them, change their port, or fetch their token from `tp-android`. If a previous workflow toggled them on, they keep running until disabled via AutoTermux's UI; that is intentional and not something this skill should fight.

The reason: bridge is in-process, has no listening port, no per-invocation handshake, and works regardless of which Wi-Fi the device is on. The only constraint to know about is the **~1 MB Binder transaction limit** — see "Large payloads" below.

## Output contract

- stdout = data, stderr = diagnostics.
- Most successful commands print a single JSON envelope unless `--format text`, `--raw`, `--format raw`, `--format none`, or a binary `-o -` output is requested.
- `status ...` commands default to flat text for progress logs; pass `--json` or `--pretty` when a script needs the standard envelope.
- `event watch`, `sms watch`, `notification watch` are streaming — one JSON object per line, indefinitely until killed.
- Errors print JSON on stderr by default, or flat text when `--format text` is active, and exit non-zero — always check the exit code before trusting stdout.

Envelope shape:

```json
{"ok":true,"schema_version":"tp-android.v1","command":"input.tap","result":{}}
```

Use `--pretty` for structured human inspection, `--format text` for flat logs, `--raw` for scalar/result extraction in scripts, and `jq -r '.result...'` for structured selection.

## Sanity check first

When something is not working, before guessing:

```sh
tp-android doctor --pretty       # bridge ping + accessibility readiness
tp-android ping --pretty         # round-trip latency
tp-android permissions status --pretty   # which runtime / special perms still need to be granted
tp-android state connection --pretty     # internal HTTP/WS server state (informational only)
tp-android mode status --pretty          # a11y vs no-a11y mode, server enabled flags
tp-android ui diagnose --pretty          # screenshot path + current app + windows + visible text snippets
```

If `tp-android doctor` fails with `AccessibilityService not available`, ask the user to enable AutoTermux's accessibility service in Settings, then retry. If commands return `Could not parse bridge broadcast output: ... result=0`, AutoTermux's process was probably killed by the OEM (vivo/MIUI/etc.); ask the user to launch AutoTermux to the foreground or whitelist it from battery optimization, then retry.

## Inspect UI

Prefer selectors over coordinate taps. Coordinates are brittle across screen sizes and orientations.

```sh
tp-android ui dump --pretty
tp-android ui dump --package com.example --pretty       # dump a visible target package even if another window has focus
tp-android ui dump --no-filter --pretty                # include invisible/disabled nodes
tp-android ui tree --pretty
tp-android ui phone-state --pretty                     # currentApp / packageName / activityName / keyboardVisible
tp-android ui windows --pretty                         # all visible accessibility windows, z-order, focus, bounds
tp-android ui texts --package com.example --pretty     # compact visible text/contentDescription list
tp-android ui find --text-contains "Continue" --limit 5 --pretty
tp-android ui focused --pretty
tp-android ui dump --cache --pretty                    # caches under /sdcard/Download/.termuxplus/tp-android-cache, prints path
tp-android ui dump -o /sdcard/Download/ui-state.json
```

Click via selector when possible, fall back to taps only when no selector is available:

```sh
tp-android ui click --text "OK"
tp-android ui click --text-contains "Allow"
tp-android ui click --text "Example" --expect-package com.example --verify-timeout 8
tp-android ui wait --text-contains "Done" --wait-timeout 15            # block until visible
tp-android ui wait --package com.example --text-contains "Done" --wait-timeout 15
tp-android ui wait --text-contains "Loading" --gone --wait-timeout 30  # block until disappears
tp-android ui action scroll-forward --class-name RecyclerView
tp-android ui scroll-until --scrollable --target-text-contains "Done" --max-scrolls 8
tp-android ui action set-text --resource-id com.example:id/name --value "Ada"
```

Screenshot (saves to anywhere you can write — `~`, `/sdcard/...`):

```sh
tp-android ui screenshot -o ~/screen.png
tp-android ui screenshot -o /sdcard/Download/screen.png
```

Foreground-dependent `ui` and `input` commands automatically hide Termux's floating terminal if accessibility currently reports Termux in front, then restore it collapsed when the command exits. The guard writes a notice to stderr and can be disabled with `TP_ANDROID_AUTO_HIDE_TERMUX=0`; collapsed restore can be disabled with `TP_ANDROID_AUTO_RESTORE_TERMUX=0`.

If a Termux/Codex floating window is still over the target app, treat `app current` and plain `ui dump` as focus-oriented diagnostics, not proof of what is visually dominant. First run `tp-android ui windows --pretty` or `tp-android ui diagnose --package <target> --pretty`, then use package-scoped commands:

```sh
tp-android ui texts --package com.xingin.xhs --pretty
tp-android ui dump --package com.xingin.xhs --cache --pretty
tp-android ui action scroll-forward --package com.xingin.xhs --scrollable
```

Use `tp-android overlay set-visible false` only for AutoTermux's own overlay. It does not hide Termux's floating terminal window; package-scoped UI commands and screenshots are the safer first pass when that window is present.

## Input

```sh
tp-android input tap 540 1200
tp-android input swipe 540 1600 540 400 --duration-ms 350
tp-android input double-tap 540 1200 --interval-ms 80
tp-android input drag 900 1300 180 1300 --duration-ms 900
tp-android input edge-swipe left --distance 420 --duration-ms 500
tp-android input long-press 540 1200 --duration-ms 700
tp-android input text hello there                # multi-word text accepted as positional args
tp-android input text "hello" --clear            # replace existing field content
printf '%s' "long text" | tp-android input text --stdin
tp-android input key enter
tp-android input global back
tp-android input key-list --pretty               # discover available key names
tp-android input global-list --pretty            # discover global actions
```

## Apps

```sh
tp-android app list --pretty
tp-android app current --pretty
tp-android app current --package-only --raw
tp-android app info com.android.settings --pretty
tp-android app launch com.android.settings
tp-android app launch-interactive com.android.settings --pretty
tp-android app wait com.android.settings --wait-timeout 10 --pretty
tp-android app open-url "https://example.com" --pretty
tp-android app intent --action android.intent.action.VIEW --data "geo:0,0?q=coffee" --pretty
tp-android app settings com.android.settings --screen permissions --pretty
tp-android app stop com.example.app
```

## Clipboard / overlay / screen / device

```sh
tp-android clipboard get --raw
tp-android clipboard set "copied text"           # multi-word accepted

tp-android overlay status --pretty
tp-android overlay set-visible false
tp-android overlay offset 120
tp-android overlay auto-offset enable

tp-android screen status --pretty
tp-android screen wake --duration-ms 3000 --pretty
tp-android screen lock --pretty
tp-android screen orientation set landscape --pretty
tp-android screen keep-awake set true --pretty

tp-android device identity --pretty
tp-android time                                  # device epoch ms
```

## Screen recording

```sh
tp-android screen record start --max-duration 5000 --bit-rate 4000000 --frame-rate 24
# (first run prompts the system MediaProjection consent — the user must tap "Start now")
tp-android screen record status --pretty         # state, current path, duration, dims
tp-android screen record stop --pretty
```

The MP4 lands in AutoTermux's internal cache (`/data/data/com.termux.autotermux/cache/screenrec/recording-<ts>.mp4`). The status snapshot is retained after the recording finishes so you can fetch the path post-hoc. Adb users can pull it via `adb exec-out run-as com.termux.autotermux cat <path>`.

## Files (shared storage, relative to `/sdcard`)

```sh
tp-android file list Download --pretty
tp-android file read Download/input.txt -o ./input.txt
tp-android file write ./output.txt Download/output.txt
tp-android file delete Download/output.txt
tp-android file fetch https://example.com/file.txt Download/file.txt

tp-android storage get --mime 'image/*' -o ./picked-image
tp-android saf managedir --pretty
tp-android saf dirs --pretty
tp-android saf ls '<tree-or-directory-uri>' --pretty
tp-android saf read '<document-uri>' -o ./document.bin
tp-android saf write '<document-uri>' ./document.bin

tp-android apk install https://example.com/app.apk
```

## Dialogs / biometrics / NFC / USB / keystore

```sh
tp-android dialog confirm --title "Continue?" --hint "Allow the agent to proceed?" --pretty
tp-android dialog text --title "Input" --hint "Notes" --pretty
tp-android dialog radio --values "Approve,Reject,Ask later" --pretty

tp-android fingerprint --title "Confirm action" --pretty
tp-android nfc status --pretty
tp-android nfc read full --pretty
tp-android usb list --detailed --pretty

tp-android keystore list --detailed --pretty
tp-android keystore generate agent-key -a RSA -s 2048 --pretty
tp-android keystore sign agent-key SHA256withRSA ./payload.bin -o ./payload.sig
tp-android keystore verify agent-key SHA256withRSA ./payload.bin ./payload.sig --pretty
```

## Device APIs (Termux:API parity, 57 commands)

Domain-named commands are the preferred surface:

```sh
tp-android battery status --pretty
tp-android audio info --pretty
tp-android volume get --pretty
tp-android brightness set 120                    # 0-255; needs WRITE_SETTINGS special access
tp-android wifi connectioninfo --pretty
tp-android telephony device-info --pretty
tp-android telephony cell-info --pretty
tp-android location get --provider gps --pretty
tp-android sensor list --pretty
tp-android sensor read accelerometer -n 3 --pretty       # `-n / --limit` is sample count, not duration
tp-android infrared frequencies --pretty
tp-android torch set on                          # or `set off`
tp-android vibrate --duration 300
tp-android toast hello there                     # multi-word accepted
tp-android speech-to-text --language zh-CN --prompt "请说话" --pretty
tp-android tts speak hello there
tp-android wallpaper set --file /sdcard/Download/wallpaper.jpg
tp-android camera info --pretty
tp-android camera photo -o /sdcard/Download/camera.jpg --pretty
tp-android media player info --pretty
tp-android microphone record info --pretty
tp-android job-scheduler list --pretty           # alias of `pending`
tp-android job-scheduler schedule /data/data/com.termux/files/home/job.sh --job-id 7 --period-ms 900000 --pretty
```

Permission-gated APIs (SMS / contacts / call-log / camera / mic / location / etc.) will return an `ERR` envelope until Android runtime permissions are granted. Run `tp-android permissions status --pretty` first when something fails.

```sh
tp-android permissions open notification-listener        # opens the Settings page
```

## Messaging: SMS / contacts / call-log / notifications

```sh
tp-android contacts list --cache --pretty                # `--cache` returns a JSON path instead of inlining
tp-android call-log list --limit 20 --pretty
tp-android sms list --limit 20 --pretty
tp-android sms send -n +15551234567 "hello there"
tp-android sms watch --event-format rpc --count 1        # streaming, one event per line
tp-android sms wait --from +15551234567 --text-contains "code" --wait-timeout 60 --pretty

tp-android notification list --cache --pretty
tp-android notification post --title "Done" "Task finished"
tp-android notification post --title "Need input" --reply "Reply:answer" "Send a reply from the notification"
tp-android notification action '<notification-key>' 0
tp-android notification reply '<notification-key>' 0 "message"
tp-android notification watch --event-format rpc --count 5
tp-android notification wait --package com.whatsapp --title-contains "Alice" --text-contains "approve" --wait-timeout 60 --pretty
```

`wait` blocks until a matching event arrives or the timeout expires. `--text-contains` matches notification `content`, SMS `body`, replies, messages, and other text-like payload fields uniformly.

## Triggers (persistent rules → auto-launch Termux commands)

`tp-android trigger` manages persistent rules that fire on device events (SMS, notifications, app entered, battery low, time of day, …) and run a Termux command via `RunCommandService`. Rules survive reboots. Templates available in `--prompt` / `--arg` / `--stdin-template`: `{{trigger.package}}`, `{{trigger.title}}`, `{{trigger.text}}`, `{{trigger.phone_number}}`, `{{trigger.message}}`, `{{rule.name}}`, etc.

```sh
tp-android trigger status --pretty                       # accessibility, notification-listener, perms readiness
tp-android trigger catalog --pretty                      # all source types + their available fields
tp-android trigger rule list --pretty
tp-android trigger rule get <id> --pretty

# Add a rule firing on SMS containing "code":
tp-android trigger rule add "SMS code agent" SMS_RECEIVED \
  --from +1555 --message-contains code \
  --prompt "Handle SMS from {{trigger.phone_number}}: {{trigger.message}}" \
  --command /data/data/com.termux/files/usr/bin/codex --arg exec \
  --stdin-template "{{trigger.message}}" \
  --cooldown-seconds 30

# Add a notification rule with burst-debounce (merge multiple posts within window into one launch):
tp-android trigger rule add "Notification agent" NOTIFICATION_POSTED \
  --package com.whatsapp --title-contains Alice --text-contains approve \
  --notification-debounce-ms 5000 \
  --busy-policy QUEUE \
  --cooldown-seconds 60 \
  --prompt "Notification: {{trigger.title}}: {{trigger.text}}" \
  --command /data/data/com.termux/files/home/agent-hook.sh

tp-android trigger rule test <id>                        # dry-run a rule with a synthesized payload
tp-android trigger rule set-enabled <id> --enabled false
tp-android trigger rule delete <id>
tp-android trigger run list --limit 20 --pretty          # recent fire/match/skip/buffered/debounced records
```

Important: rules with `--command` need AutoTermux to hold `com.termux.permission.RUN_COMMAND` (granted at install) and Termux must have `allow-external-apps=true` in `~/.termux/termux.properties` (or `termux-reload-settings` after editing). Without that, command-launching trigger fires silently drop.

`--busy-policy QUEUE` (vs default `SKIP`) buffers signals that arrive during the rule's cooldown and replays them after the cooldown expires (drained on a 1-second tick). `--notification-debounce-ms` merges bursts of similar notifications from the same sender into a single launch, with merged text in `{{trigger.text}}`.

## Events (live stream and one-shot wait)

```sh
tp-android event list --pretty                           # configurable event types
tp-android event disable window-content-changed          # mute noisy ones to keep watchers responsive
tp-android event watch --event-format rpc --count 5      # stream 5 events then exit
tp-android event wait --type foreground-app-changed \
  --package com.android.settings --wait-timeout 15 --pretty
```

## Provider passthrough / raw escape hatches

When you need to call an action that does not have a typed wrapper yet, you can still reach it:

```sh
tp-android provider query mode_status --pretty
tp-android provider insert set_event -b event:s:WINDOW_CONTENT_CHANGED -b enabled:b:false
tp-android api get state_full --pretty
tp-android api post tap -p x=540 -p y=1200
tp-android call tap -p x=540 -p y=1200
tp-android call clipboard/set -p text="hello"
tp-android termux-api support --pretty                   # list supported Termux:API command names
tp-android termux-api notification --title "Done" --content "Task finished" --id 42 --pretty
tp-android ta dialog checkbox -v "A,B,C" -t "Pick one or more" --pretty
```

`termux-api` (alias `ta`) accepts the official script-style arguments for the bundled 57 Termux:API command names. Typed domain commands (`tp-android sms list`, `tp-android notification post`, …) are still the preferred surface — they have nicer flags and produce the same JSON envelope.

## Large payloads and the transfer cache

The bridge transport carries data through Android Binder. Each Binder transaction is capped at roughly **1 MB** (including framing and any base64 expansion). To stay under the cap:

- Screenshots, UI dumps, file reads, camera photos, and similar "big" results stage the bytes in `/storage/emulated/0/Download/.termuxplus/tp-android-cache/` and the bridge returns a `path` field. `tp-android` then opens that file directly from the Termux process (`untrusted_app` has shared-storage read) and copies to `-o`.
- Large list endpoints (e.g. `contacts list`, `notification list`, full `ui dump`) accept `--cache`, which writes the JSON to the same transfer cache and returns the path instead of inlining the array.
- When you control a query that supports `--limit`, set it. Some vendor ContentProviders (vivo's `CallLogProvider` observed) ignore `QUERY_ARG_LIMIT` and return every row, which then blows past the Binder cap. The server-side defensively caps for known cases but a sensible `--limit` is still cheaper.

If shared storage is not set up (no Termux storage permission, or `~/storage/shared` missing), large-payload commands will fail at the final read step. Run `termux-setup-storage` once, then retry.

## When NOT to use this skill

- For pure-terminal operations inside Termux (file edits, package installs, `pkg`, `pip`, etc.) — use the shell directly.
- For driving a remote device or another phone — `tp-android` only talks to AutoTermux on the *same* device.
- For features that need root or system signature (modifying secure settings beyond `WRITE_SETTINGS`, accessing other apps' private storage). Those are out of scope.
- When the user already has a specific `adb` command they want run from their host machine — that runs in a different context (shell uid 2000, SELinux `runas_app` when going through `run-as`) and has different permissions than Termux, so don't translate it to `tp-android` blindly.

## Recovery

```sh
tp-android doctor --pretty                # always the first thing to try
tp-android install-companion              # reinstall/update AutoTermux APK from the bundled asset
```

If the same command keeps returning `Broadcast completed: result=0` despite `doctor` reporting `bridge_ping: ok`, the AutoTermux process is likely being killed between calls by the OEM's aggressive battery manager (vivo / MIUI / EMUI). Ask the user to launch AutoTermux to the foreground and allow it to run in background in the OEM's battery settings, then batch all your bridge calls in a single shell block without long sleeps.
