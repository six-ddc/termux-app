# `tp-android status` — Agent UX protocol

The `status` subcommand family is how a long-running automation tells the user (and the HUD) what is happening and how it solicits human input. Every multi-step run **must** wrap its activity with these commands; the HUD, sticky notification, and `status tail` observer all read the same on-disk state, so following the protocol is what makes the run visible at all.

## A run

A *run* is one logical task. It has an id (`r_<hex>`), a task description, an optional total-step count, a current state (`running | waiting | done | cancelled | error`), and an append-only event log. On-disk layout:

```
~/.termuxplus/runs/
  current                   # plain text file: the run_id of the active run
  <run_id>/
    run.json                # full state — atomically rewritten on every update
    interaction-<id>.req    # pending interaction request (kind, payload, expiry)
    interaction-<id>.res    # response written by HUD or terminal fallback
```

`current` is what `tp-android status current` reads; `$TP_RUN_ID` in the environment overrides it (set this in a subshell to address a specific run from a script).

`status` commands default to flat text for human progress logs. Add `--json` or `--pretty` when a script needs the standard JSON envelope, or `--raw` when you only want the result object.

## Lifecycle

```sh
tp-android status start --task "<one line>" --total-steps N    # creates run
tp-android status step --step K --label "<name>" --state running
# ... do work ...
tp-android status step --step K --label "<name>" --state done
tp-android status finish --state success --summary "<wrap-up>"
```

`--total-steps` is a hint only; you can exceed it.

Granular progress within a step (e.g. a download) is reported via:

```sh
tp-android status progress --percent 60 --detail "Downloading…"
```

## Intervention

When the agent cannot make a decision on its own — captcha, OTP, choice between two plausible buttons, "this might cost money, confirm?" — it **must** ask:

```sh
# Choose one of N labelled options. Returns {"choice":"…"} or {"timeout":true}.
tp-android status intervene \
  --reason "I see two 'Submit' buttons — which one?" \
  --choices "left one,right one,abort" \
  --wait-timeout 120 --raw

# Free-form input. Returns {"value":"…"} or {"timeout":true}.
tp-android status ask-input \
  --prompt "Enter the 6-digit SMS code" \
  --kind number --wait-timeout 180 --raw

# Yes/no. Returns {"confirmed":true|false} or {"timeout":true}.
tp-android status confirm \
  --prompt "Send ¥100 to Alice?" --wait-timeout 60 --raw
```

These are **blocking** by design. They:

1. Write `interaction-<id>.req` into the run directory.
2. Flip the run state to `waiting`.
3. Poll for `interaction-<id>.res` until the HUD writes it or the timeout fires.
4. Append `interaction_response` / `interaction_timeout` to the event log.
5. Flip the run state back to `running` and return.

If no HUD is registered (no PID file under `~/.termuxplus/hud.pid` or that PID is dead), and stdin is a TTY, the CLI falls back to a terminal prompt. This is the dev/SSH workflow. In non-TTY non-HUD environments, intervention immediately times out — design your batch scripts so this is safe.

## Cancellation

Either side can cancel:

- From the HUD: the user taps `✕` on the status bar, which calls `tp-android status cancel --source hud`.
- From a sibling shell: `tp-android status cancel`.
- The agent itself can mark a clean wrap: `tp-android status finish --state cancelled`.

Cancellation sets `state=cancelled` and `ended_at=now`. The next `status step` from the agent returns success (the file is still writable) — it's the agent's responsibility to read `status current` periodically and bail when state is no longer `running`.

## Error reporting

```sh
tp-android status error --code TARGET_NOT_FOUND \
  --message "Login button missing on welcome screen"
```

If the code's `recoverable` flag is `false` (or you pass `--recoverable false`), the run flips to `state=error` and `ended_at` is set. If `recoverable=true`, the error is recorded as an event but the run continues so the agent can retry or escalate to an intervention.

See [errors.md](errors.md) for the full code table.

## Observing a run

```sh
tp-android status current                   # human-readable active run summary
tp-android status current --json            # structured full state for jq/scripts
tp-android status history --limit 20        # newest runs first, summary only
tp-android status tail --follow             # stream the event log
```

`status tail --follow` is ideal for an SSH-attached debugger or a second Termux session: it stays attached until the run ends.

## When stdout must be a JSON pipe

`status` commands default to text. Use `--json` for the standard envelope, or `--raw` to print just the result object:

```sh
CHOICE=$(tp-android status intervene --reason "…" --choices "a,b" --raw | jq -r '.choice // empty')
case "$CHOICE" in
  a) … ;;
  b) … ;;
  "") echo "timeout or cancel"; tp-android status finish --state cancelled; exit 1 ;;
esac
```

## Anti-patterns

- **Don't write tight polling loops between `status step` calls** — every call is a `~/.termuxplus/runs/<id>/run.json` rewrite. Update the step state when it actually transitions, not every second.
- **Don't reuse a run id across logical tasks.** Start a new run per user request; history is what gives the user trust.
- **Don't bypass intervention with a guess.** A misclicked "Confirm transfer" cannot be undone. When in doubt, `confirm`.
- **Don't swallow timeouts.** `{"timeout":true}` means the user is gone or disengaged — abort the run gracefully, don't quietly continue.
