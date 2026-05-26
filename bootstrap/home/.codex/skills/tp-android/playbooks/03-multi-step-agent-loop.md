# Playbook 03 — Multi-step agent loop with cancellation polling

**Goal:** show a loop where the LLM agent itself drives the steps. Each iteration the agent inspects the current screen, picks a next action, executes it, and reports state. The pattern handles user-cancel mid-flow and uses `intervene` when the screen is ambiguous.

This is the canonical shape an LLM agent like Codex/Claude should target when running multi-step automation. The script below is what your agent's *shell tool* invocations should compose into.

```sh
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

TARGET=com.xingin.xhs                 # example: RedNote / 小红书
GOAL="${1:-find and open the first post tagged #travel}"

tp-android status start --task "$GOAL"
RUN_ID=$(tp-android status current --raw | jq -r '.run_id')

step_index=0
max_steps=20

while :; do
  step_index=$((step_index+1))

  # === Cancellation poll ===
  STATE=$(tp-android status current --raw | jq -r '.state')
  if [ "$STATE" = "cancelled" ]; then
    echo "User cancelled; exiting." >&2
    exit 0
  fi
  if [ "$step_index" -gt "$max_steps" ]; then
    tp-android status error --code INPUT_TIMEOUT \
      --message "Exceeded $max_steps steps without reaching goal"
    tp-android status finish --state error --summary "step budget exhausted"
    exit 1
  fi

  # === Sense ===
  tp-android status step --step "$step_index" --label "Inspect screen" --state running
  CURRENT_PKG=$(tp-android app current --package-only --raw)
  DIGEST=$(tp-android ui digest --package "$TARGET" --raw 2>/dev/null || tp-android ui dump --package "$TARGET" --cache --raw)
  # `ui digest` is the token-friendly view; falls back to a cached `ui dump`
  # when digest isn't available (older companion). Feed DIGEST to the LLM.

  # === Decide (this is where the LLM picks the next action) ===
  # In a real loop, the LLM reads $DIGEST + $GOAL and emits ONE next action.
  # For this template we hardcode an example branch.
  NEXT=$(plan_next_action_from_llm "$DIGEST" "$GOAL")    # your bridge to the LLM

  # === Act (with verify) ===
  ACTION_KIND=$(jq -r '.kind' <<<"$NEXT")
  case "$ACTION_KIND" in
    click)
      TEXT=$(jq -r '.text' <<<"$NEXT")
      if ! tp-android ui click --text "$TEXT" --expect-package "$TARGET" --verify-timeout 6 --raw 2>/dev/null; then
        RESP=$(tp-android status intervene \
          --reason "Could not click '$TEXT'. What now?" \
          --choices "retry,skip,abort" --wait-timeout 60 --raw)
        case "$(jq -r '.choice // empty' <<<"$RESP")" in
          retry) continue ;;
          skip)  ;;
          abort|"") tp-android status finish --state cancelled; exit 0 ;;
        esac
      fi
      ;;
    scroll)
      tp-android ui action scroll-forward --package "$TARGET" --scrollable
      ;;
    wait)
      TEXT=$(jq -r '.text' <<<"$NEXT")
      tp-android ui wait --package "$TARGET" --text-contains "$TEXT" --wait-timeout 12 || true
      ;;
    ask_user)
      PROMPT=$(jq -r '.prompt' <<<"$NEXT")
      RESP=$(tp-android status intervene --reason "$PROMPT" --choices "$(jq -r '.choices' <<<"$NEXT")" --wait-timeout 120 --raw)
      # feed back into LLM as next-turn input
      ;;
    done)
      tp-android status step --step "$step_index" --label "Completed" --state done
      tp-android status finish --state success --summary "$(jq -r '.summary // \"goal reached\"' <<<"$NEXT")"
      exit 0
      ;;
    *)
      tp-android status error --code CLI_ERROR --message "Unknown action kind: $ACTION_KIND"
      ;;
  esac

  tp-android status step --step "$step_index" --label "$ACTION_KIND" --state done
done
```

**Key patterns demonstrated:**

1. **Cancellation polling** at the top of every iteration — without this the user pressing ✕ on the HUD silently has no effect until the loop happens to finish.
2. **Budget cap** (`max_steps`) — agents *will* get stuck. Bail with a clean error code, don't burn the user's tokens forever.
3. **Sense → Decide → Act** explicit cycle. The LLM gets a compressed digest, not a raw `ui dump`. This is what `ui digest` exists for.
4. **Recover via `intervene` rather than guess.** When the click verify fails, ask the user; never silently retry the same broken selector.
5. **Always end with `status finish`.** Every exit path — success, cancel, error, step-budget — calls finish so the HUD goes back to idle.

**Coupling to a real LLM:** replace `plan_next_action_from_llm` with whatever IPC you use to ask Codex/Claude/etc. for one next action. The function returns a JSON object like:

```json
{"kind": "click", "text": "登录"}
{"kind": "scroll"}
{"kind": "wait", "text": "Loading"}
{"kind": "ask_user", "prompt": "Which post?", "choices": "first,second,abort"}
{"kind": "done", "summary": "Opened post #42"}
```

Keep the LLM's surface narrow — these 5 kinds plus `done` cover almost all UI agent flows.
