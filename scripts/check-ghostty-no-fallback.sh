#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

mark_fail() {
    printf 'FAIL: %s\n' "$1" >&2
    fail=1
}

runtime_check=false
runtime_kitty_check=false
runtime_color_check=false
runtime_focus_check=false
runtime_hyperlink_check=false
runtime_style_check=false

for arg in "$@"; do
    case "$arg" in
        --runtime)
            runtime_check=true
            ;;
        --runtime-kitty)
            runtime_check=true
            runtime_kitty_check=true
            ;;
        --runtime-colors)
            runtime_check=true
            runtime_color_check=true
            ;;
        --runtime-focus)
            runtime_check=true
            runtime_focus_check=true
            ;;
        --runtime-hyperlinks)
            runtime_check=true
            runtime_hyperlink_check=true
            ;;
        --runtime-style)
            runtime_check=true
            runtime_style_check=true
            ;;
        *)
            mark_fail "unknown argument: $arg"
            ;;
    esac
done

require_match() {
    local description="$1"
    local pattern="$2"
    shift 2
    if rg -n "$pattern" "$@" >/tmp/termux-ghostty-check-match.$$; then
        printf 'OK: %s\n' "$description"
    else
        mark_fail "$description"
    fi
    rm -f /tmp/termux-ghostty-check-match.$$
}

reject_match() {
    local description="$1"
    local pattern="$2"
    shift 2
    if rg -n "$pattern" "$@" >/tmp/termux-ghostty-check-match.$$; then
        cat /tmp/termux-ghostty-check-match.$$ >&2
        mark_fail "$description"
    else
        printf 'OK: %s\n' "$description"
    fi
    rm -f /tmp/termux-ghostty-check-match.$$
}

factory=terminal-emulator/src/main/java/com/termux/terminal/TerminalEngineFactory.java
runtime_paths=(
    app/src/main/java
    termux-shared/src/main/java
    terminal-view/src/main/java
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngineFactory.java
    terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java
)

require_match "factory fails closed when libghostty-vt is unavailable" \
    "libghostty-vt is required but unavailable|throw new IllegalStateException" "$factory"
require_match "factory creates GhosttyTerminalEngine" \
    "return new GhosttyTerminalEngine" "$factory"

reject_match "legacy Java VT classes must not be public production API" \
    "public (final )?class (TerminalEmulator|TerminalBuffer|TerminalRow|KeyHandler)|public TerminalEmulator\\(|public TerminalBuffer\\(|public TerminalRow\\(" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java \
    terminal-emulator/src/main/java/com/termux/terminal/KeyHandler.java
reject_match "legacy Java VT must not implement the production TerminalEngine contract" \
    "class TerminalEmulator implements TerminalEngine|isGhosttyBacked\\(|getRenderCells\\(|getKittyGraphicsPlacements\\(" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java
reject_match "production code must not instantiate Java TerminalEmulator" \
    "new TerminalEmulator" "${runtime_paths[@]}"
reject_match "production code must not read TerminalBuffer/getScreen" \
    "getScreen\\(|import com\\.termux\\.terminal\\.TerminalBuffer|\\bmScreen\\b" "${runtime_paths[@]}"
reject_match "GPU renderer must use Ghostty cell width metadata, not Java WcWidth" \
    "import com\\.termux\\.terminal\\.WcWidth|WcWidth\\.width" terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
reject_match "text selection must use Ghostty cell width metadata, not Java WcWidth" \
    "import com\\.termux\\.terminal\\.WcWidth|WcWidth\\.width" terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java
require_match "render cells carry Ghostty width metadata" \
    "RENDER_CELL_WIDTH" terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
require_match "render cells reserve a Ghostty selection metadata slot" \
    "RENDER_CELL_STRIDE = 9|RENDER_CELL_SELECTED" terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java
require_match "Ghostty selection is set through the native terminal API" \
    "ghosttySetSelection|gTerminalSet\\(bridge->terminal, 21" \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java
require_match "Ghostty render-state selected cells are bridged into render cells" \
    "GHOSTTY_RENDER_CELL_SELECTED|RENDER_CELL_SELECTED" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
reject_match "GPU renderer must use Ghostty selected cells, not Java per-cell selection math" \
    "isSelected\\(" terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
require_match "Ghostty Kitty graphics storage is enabled" \
    "GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT" terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty system callback API is loaded for Kitty PNG decoding" \
    "ghostty_sys_set|gGhosttySysSet" terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty Kitty PNG decode callback is installed" \
    "GHOSTTY_SYS_OPT_DECODE_PNG|termux_ghostty_decode_png|install_ghostty_sys_callbacks" \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Android native image decoder backs Ghostty PNG decoding" \
    "AImageDecoder_createFromBuffer|AImageDecoder_decodeImage|ANDROID_BITMAP_FORMAT_RGBA_8888" \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty PNG decoder returns memory allocated by Ghostty" \
    "ghostty_alloc|gGhosttyAlloc|gGhosttyFree" \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty Kitty placements and images are snapshotted through native API" \
    "ghosttySnapshotKittyGraphicsPlacements|ghostty_kitty_graphics_placement_render_info|ghostty_kitty_graphics_image_get" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java
require_match "Ghostty Kitty placement snapshot has a typed Java model" \
    "TerminalKittyGraphicsPlacement|isTextureUploadSupported" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalKittyGraphicsPlacement.java
require_match "GPU renderer draws Ghostty Kitty graphics as textures" \
    "drawKittyGraphicsPlacements|getKittyTexture|kittyImages|GL_RGB|GL_RGBA" \
    terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
require_match "render dirty state is acknowledged after GPU rendering" \
    "ghosttyClearRenderDirtyState" terminal-emulator/src/main/java/com/termux/terminal/JNI.java terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty cursor wide-tail metadata is bridged into the renderer" \
    "isCursorWideTail|gRenderStateGet\\(bridge->render_state, 17|cursorRenderCol" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
require_match "Ghostty cursor blinking metadata controls cursor visibility" \
    "mRenderStateCursorBlinking|gRenderStateGet\\(bridge->render_state, 12" \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty password-input cursor metadata controls cursor visibility" \
    "isCursorPasswordInput|mCursorPasswordInput|gRenderStateGet\\(bridge->render_state, 13" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty protected-cell metadata is preserved in render effects" \
    "GHOSTTY_CELL_DATA_PROTECTED|termux_protected_effect_from_ghostty_cell" \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Ghostty underline color, underline style, and overline are preserved in render cells" \
    "RENDER_CELL_UNDERLINE_COLOR|RENDER_CELL_UNDERLINE_STYLE|RENDER_CELL_OVERLINE|underline_color|style\\.underline|style\\.overline|drawUnderline|drawCurlyUnderline" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
require_match "Ghostty underline RGB colors use a non-ARGB fallback sentinel" \
    "RENDER_CELL_COLOR_DEFAULT|TERMUX_RENDER_CELL_COLOR_DEFAULT|resolveUnderlineColor" \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
reject_match "Ghostty blink must not be rendered as fake bold" \
    "CHARACTER_ATTRIBUTE_BOLD \\| TextStyle\\.CHARACTER_ATTRIBUTE_BLINK" \
    terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java
require_match "Ghostty device attribute queries are answered by Termux-compatible native callback" \
    "GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES|termux_ghostty_device_attributes_callback|GHOSTTY_DA_CONFORMANCE_VT420|GHOSTTY_DA_DEVICE_TYPE_VT420" \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Termux color scheme defaults are applied to Ghostty native colors" \
    "ghosttyApplyDefaultColors|TerminalColors\\.COLOR_SCHEME\\.mDefaultColors|GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND|GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND|GHOSTTY_TERMINAL_OPT_COLOR_CURSOR|GHOSTTY_TERMINAL_OPT_COLOR_PALETTE" \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/jni/ghostty_bridge.c
require_match "Termux color reload updates every active session engine" \
    "getTermuxSessions\\(\\)|getTerminalEngine\\(\\)\\.resetColors\\(\\)" \
    app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java
require_match "Ghostty terminal effects are drained back to Termux session callbacks" \
    "ghostty_terminal_set|ghosttyDrainPendingPtyWrite|ghosttyConsumeBellCount|ghosttyConsumeTitleChanged|drainGhosttyEffects|termux_ghostty_size_callback|termux_ghostty_xtversion_callback|termux_ghostty_device_attributes_callback" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java
require_match "Ghostty focus reporting uses libghostty-vt focus encoder" \
    "ghostty_focus_encode|ghosttyEncodeFocus|sendFocusEvent|sendFocusEventToCurrentSession|MODE_FOCUS_EVENT|onWindowFocusChanged" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java \
    terminal-view/src/main/java/com/termux/view/TerminalView.java
require_match "Ghostty text input uses libghostty-vt key event UTF-8 encoder" \
    "ghostty_key_event_set_utf8|ghosttyEncodeCodePoint|sendCodePoint|mLoggedTextInputEncoderPath" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    terminal-view/src/main/java/com/termux/view/TerminalView.java
reject_match "TerminalView must not write text input directly to TerminalSession" \
    "mTermSession\\.write\\(event\\.getCharacters\\(\\)\\)|mTermSession\\.writeCodePoint\\(" \
    terminal-view/src/main/java/com/termux/view/TerminalView.java
require_match "Ghostty paste and autofill use libghostty-vt paste encoder" \
    "ghostty_paste_encode|ghosttyEncodePaste|mLoggedPasteEncoderPath|mTerminalEngine\\.paste\\(value\\.getTextValue\\(\\)\\.toString\\(\\)\\)" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-view/src/main/java/com/termux/view/TerminalView.java
reject_match "production paste must not hand-roll bracketed paste sequences" \
    "isBracketedPasteMode|\\\\033\\[200~|\\\\033\\[201~|mTermSession\\.write\\(value\\.getTextValue\\(\\)\\.toString\\(\\)\\)" \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-view/src/main/java/com/termux/view/TerminalView.java
require_match "OSC 8 hyperlinks are resolved from Ghostty grid refs before regex URL fallback" \
    "ghostty_grid_ref_hyperlink_uri|ghosttyGetHyperlinkAtLocation|getHyperlinkAtLocation|getHyperlinks\\(\\)|hyperlinkAtTap" \
    terminal-emulator/src/main/jni/ghostty_bridge.c \
    terminal-emulator/src/main/java/com/termux/terminal/JNI.java \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java \
    terminal-emulator/src/main/java/com/termux/terminal/TerminalEngine.java \
    app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java
require_match "Ghostty transcript formatting preserves caller trim mode" \
    "ghosttyFormatSelection\\(mNativeContext, 0, -getScrollbackRows\\(\\),|mRows - 1, linesJoined, trim" \
    terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalEngine.java

if [[ "$runtime_check" == true ]]; then
    if ! command -v adb >/dev/null 2>&1; then
        mark_fail "adb not found for runtime check"
    else
        adb logcat -c
        adb shell am force-stop com.termux >/dev/null
        adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
        runtime_wait_seconds="${GHOSTTY_RUNTIME_WAIT_SECONDS:-20}"
        runtime_log=""
        for ((second = 0; second < runtime_wait_seconds; second++)); do
            runtime_log="$(adb logcat -d -v brief 2>/dev/null || true)"
            if printf '%s\n' "$runtime_log" | rg "Using libghostty-vt terminal engine" >/dev/null &&
               printf '%s\n' "$runtime_log" | rg "Rendering Ghostty render-state cells with OpenGL ES|Frame ghostty-render-state" >/dev/null; then
                break
            fi
            sleep 1
        done
        if printf '%s\n' "$runtime_log" | rg "Using libghostty-vt terminal engine" >/dev/null; then
            printf 'OK: runtime log shows libghostty-vt engine\n'
        else
            mark_fail "runtime log does not show libghostty-vt engine"
        fi
        if printf '%s\n' "$runtime_log" | rg "Rendering Ghostty render-state cells with OpenGL ES|Frame ghostty-render-state" >/dev/null; then
            printf 'OK: runtime log shows Ghostty OpenGL render-state path\n'
        else
            mark_fail "runtime log does not show Ghostty OpenGL render-state path"
        fi
        if printf '%s\n' "$runtime_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >/dev/null; then
            printf '%s\n' "$runtime_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >&2
            mark_fail "runtime log contains fallback/crash markers"
        else
            printf 'OK: runtime log has no fallback/crash markers\n'
        fi

        if [[ "$runtime_color_check" == true ]]; then
            color_file="files/home/.termux/colors.properties"
            color_backup="files/home/.termux/colors.properties.ghostty-check-bak.$$"
            tmp_screenshot="$(mktemp -t ghostty-color.XXXXXX.png)"
            if ! adb shell "run-as com.termux sh -c 'mkdir -p files/home/.termux && if [ -f $color_file ]; then cp $color_file $color_backup; else rm -f $color_backup; fi && printf \"background=#123456\nforeground=#abcdef\ncursor=#fedcba\ncolor0=#010203\n\" > $color_file'" >/dev/null 2>&1; then
                mark_fail "runtime color smoke could not install Termux colors"
            else
                adb shell am force-stop com.termux >/dev/null
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
                sleep 4
                if adb exec-out screencap -p > "$tmp_screenshot" &&
                   python3 - "$tmp_screenshot" <<'PY'
from PIL import Image
import sys

path = sys.argv[1]
target = (0x12, 0x34, 0x56)
tolerance = 3
with Image.open(path) as image:
    image = image.convert("RGB")
    width, height = image.size
    y0 = height // 5
    pixels = image.load()
    total = 0
    matches = 0
    for y in range(y0, height):
        for x in range(width):
            pixel = pixels[x, y]
            total += 1
            if all(abs(pixel[i] - target[i]) <= tolerance for i in range(3)):
                matches += 1
ratio = matches / max(total, 1)
print(f"ghostty_color_pixels={matches} ratio={ratio:.4f}")
if ratio < 0.02:
    raise SystemExit(1)
PY
                then
                    printf 'OK: runtime screenshot shows Termux background color rendered by Ghostty\n'
                else
                    mark_fail "runtime screenshot does not show Termux background color rendered by Ghostty"
                fi
            fi
            rm -f "$tmp_screenshot"
            adb shell "run-as com.termux sh -c 'if [ -f $color_backup ]; then mv $color_backup $color_file; else rm -f $color_file; fi'" >/dev/null 2>&1 || true
            adb shell am force-stop com.termux >/dev/null
            adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
        fi

        if [[ "$runtime_focus_check" == true ]]; then
            focus_script="/data/data/com.termux/files/home/ghostty_focus_smoke.sh"
            focus_log="files/home/ghostty_focus_smoke.log"
            tmp_focus_smoke="$(mktemp)"
            cat > "$tmp_focus_smoke" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
log=/data/data/com.termux/files/home/ghostty_focus_smoke.log
exec >"$log" 2>&1
printf 'ghostty-focus-smoke-start\n'
old="$(stty -g < /dev/tty 2>/dev/null || true)"
stty raw -echo min 0 time 0 < /dev/tty 2>/dev/null || true
printf '\033[?1004h' > /dev/tty
printf 'ghostty-focus-smoke-armed\n'
LC_ALL=C
response_bytes=""
deadline=$((SECONDS + 10))
while [ ${#response_bytes} -lt 6 ] && [ "$SECONDS" -lt "$deadline" ]; do
    ch=""
    if IFS= read -r -s -n 1 -t 1 ch < /dev/tty; then
        response_bytes="$response_bytes$ch"
    fi
done
response="$(printf '%s' "$response_bytes" | od -An -tx1 | tr -d ' \n')"
printf '\033[?1004l' > /dev/tty
if [ -n "$old" ]; then
    stty "$old" < /dev/tty 2>/dev/null || true
fi
printf 'focus_response_hex=%s\n' "$response"
has_gained=0
has_lost=0
case "$response" in *1b5b49*) has_gained=1 ;; esac
case "$response" in *1b5b4f*) has_lost=1 ;; esac
if [ "$has_gained" = 1 ] && [ "$has_lost" = 1 ]; then
    printf 'ghostty-focus-smoke-ok\n'
else
    printf 'ghostty-focus-smoke-fail\n'
    exit 1
fi
EOF
            adb push "$tmp_focus_smoke" /data/local/tmp/ghostty_focus_smoke.sh >/dev/null
            rm -f "$tmp_focus_smoke"
            if ! adb shell "run-as com.termux sh -c 'cp /data/local/tmp/ghostty_focus_smoke.sh files/home/ghostty_focus_smoke.sh && chmod 700 files/home/ghostty_focus_smoke.sh && rm -f $focus_log'" >/dev/null 2>&1; then
                mark_fail "runtime focus smoke script could not be installed into Termux home"
            else
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
                sleep 1
                adb shell input tap 120 520 >/dev/null 2>&1 || true
                adb shell input text "$focus_script" >/dev/null
                adb shell input keyevent ENTER >/dev/null
                for ((second = 0; second < 8; second++)); do
                    focus_smoke_log="$(adb shell "run-as com.termux cat $focus_log" 2>/dev/null || true)"
                    if printf '%s\n' "$focus_smoke_log" | rg "ghostty-focus-smoke-armed" >/dev/null; then
                        break
                    fi
                    sleep 1
                done
                sleep 2
                adb shell input keyevent HOME >/dev/null
                sleep 1
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null

                focus_wait_seconds="${GHOSTTY_RUNTIME_FOCUS_WAIT_SECONDS:-10}"
                focus_smoke_log=""
                for ((second = 0; second < focus_wait_seconds; second++)); do
                    focus_smoke_log="$(adb shell "run-as com.termux cat $focus_log" 2>/dev/null || true)"
                    if printf '%s\n' "$focus_smoke_log" | rg "ghostty-focus-smoke-ok" >/dev/null; then
                        break
                    fi
                    sleep 1
                done

                if printf '%s\n' "$focus_smoke_log" | rg "ghostty-focus-smoke-ok" >/dev/null; then
                    printf 'OK: runtime focus reporting reached Termux PTY through Ghostty encoder\n'
                else
                    printf '%s\n' "$focus_smoke_log" >&2
                    mark_fail "runtime focus reporting did not reach Termux PTY through Ghostty encoder"
                fi
            fi
            adb shell "run-as com.termux rm -f files/home/ghostty_focus_smoke.sh $focus_log" >/dev/null 2>&1 || true
            adb shell rm -f /data/local/tmp/ghostty_focus_smoke.sh >/dev/null 2>&1 || true
        fi

        if [[ "$runtime_hyperlink_check" == true ]]; then
            hyperlink_script="/data/data/com.termux/files/home/ghostty_hyperlink_smoke.sh"
            tmp_hyperlink_smoke="$(mktemp)"
            cat > "$tmp_hyperlink_smoke" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
printf '\033[2J\033[Hghostty osc8 hyperlink smoke\n'
printf '\033]8;;https://ghostty.invalid/osc8-smoke\007ghostty-link-label\033]8;;\007\n'
sleep 5
EOF
            adb push "$tmp_hyperlink_smoke" /data/local/tmp/ghostty_hyperlink_smoke.sh >/dev/null
            rm -f "$tmp_hyperlink_smoke"
            if ! adb shell "run-as com.termux sh -c 'cp /data/local/tmp/ghostty_hyperlink_smoke.sh files/home/ghostty_hyperlink_smoke.sh && chmod 700 files/home/ghostty_hyperlink_smoke.sh'" >/dev/null 2>&1; then
                mark_fail "runtime OSC 8 hyperlink smoke script could not be installed into Termux home"
            else
                adb shell am force-stop com.termux >/dev/null
                sleep 1
                adb logcat -c
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
                sleep 4
                adb shell input tap 120 520 >/dev/null 2>&1 || true
                adb shell input text "$hyperlink_script" >/dev/null
                adb shell input keyevent ENTER >/dev/null
                sleep 2
                adb shell input tap 160 275 >/dev/null
                sleep 2

                hyperlink_log="$(adb logcat -d -v brief 2>/dev/null || true)"
                if printf '%s\n' "$hyperlink_log" | rg "https://ghostty\\.invalid/osc8-smoke" >/dev/null; then
                    printf 'OK: runtime OSC 8 hyperlink click opened Ghostty grid-ref URI\n'
                else
                    mark_fail "runtime OSC 8 hyperlink click did not open Ghostty grid-ref URI"
                fi
                if printf '%s\n' "$hyperlink_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >/dev/null; then
                    printf '%s\n' "$hyperlink_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >&2
                    mark_fail "runtime OSC 8 hyperlink log contains fallback/crash markers"
                else
                    printf 'OK: runtime OSC 8 hyperlink log has no fallback/crash markers\n'
                fi
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null 2>&1 || true
            fi
            adb shell "run-as com.termux rm -f files/home/ghostty_hyperlink_smoke.sh" >/dev/null 2>&1 || true
            adb shell rm -f /data/local/tmp/ghostty_hyperlink_smoke.sh >/dev/null 2>&1 || true
        fi

        if [[ "$runtime_style_check" == true ]]; then
            style_script="/data/data/com.termux/files/home/ghostty_style_smoke.sh"
            style_log="files/home/ghostty_style_smoke.log"
            tmp_style_smoke="$(mktemp)"
            tmp_style_screenshot="$(mktemp -t ghostty-style.XXXXXX.png)"
            cat > "$tmp_style_smoke" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
printf 'ghostty-style-smoke-marker\n' > /data/data/com.termux/files/home/ghostty_style_smoke.log
printf '\033[2J\033[Hghostty style smoke\n'
printf '\033[4:2;58:2::255:0:0mDOUBLE_RED_UNDERLINE\033[0m\n'
printf '\033[4:4mDOTTED_UNDERLINE\033[0m\n'
printf '\033[53mOVERLINE_TEXT\033[0m\n'
sleep 4
EOF
            adb push "$tmp_style_smoke" /data/local/tmp/ghostty_style_smoke.sh >/dev/null
            rm -f "$tmp_style_smoke"
            if ! adb shell "run-as com.termux sh -c 'cp /data/local/tmp/ghostty_style_smoke.sh files/home/ghostty_style_smoke.sh && chmod 700 files/home/ghostty_style_smoke.sh'" >/dev/null 2>&1; then
                mark_fail "runtime Ghostty style smoke script could not be installed into Termux home"
            else
                adb shell am force-stop com.termux >/dev/null
                sleep 1
                adb logcat -c
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
                sleep 4
                adb shell input tap 120 520 >/dev/null 2>&1 || true
                adb shell "run-as com.termux rm -f $style_log" >/dev/null 2>&1 || true
                adb shell input text "$style_script" >/dev/null
                adb logcat -c
                adb shell input keyevent ENTER >/dev/null
                style_wait_seconds="${GHOSTTY_RUNTIME_STYLE_WAIT_SECONDS:-10}"
                style_smoke_log=""
                for ((second = 0; second < style_wait_seconds; second++)); do
                    style_smoke_log="$(adb shell "run-as com.termux cat $style_log" 2>/dev/null || true)"
                    if printf '%s\n' "$style_smoke_log" | rg "ghostty-style-smoke-marker" >/dev/null; then
                        break
                    fi
                    sleep 1
                done
                if ! printf '%s\n' "$style_smoke_log" | rg "ghostty-style-smoke-marker" >/dev/null; then
                    mark_fail "runtime Ghostty style smoke script did not run in Termux session"
                fi
                style_frame_log=""
                for ((second = 0; second < style_wait_seconds; second++)); do
                    style_frame_log="$(adb logcat -d -v brief 2>/dev/null || true)"
                    if printf '%s\n' "$style_frame_log" | rg "Frame ghostty-render-state|Rendering Ghostty render-state cells with OpenGL ES" >/dev/null; then
                        break
                    fi
                    sleep 1
                done
                sleep 1
                if adb exec-out screencap -p > "$tmp_style_screenshot" &&
                   python3 - "$tmp_style_screenshot" <<'PY'
from PIL import Image
import sys

path = sys.argv[1]
with Image.open(path) as image:
    image = image.convert("RGB")
    red_pixels = 0
    for r, g, b in image.getdata():
        if r > 220 and g < 50 and b < 50:
            red_pixels += 1
print(f"ghostty_style_red_pixels={red_pixels}")
if red_pixels < 20:
    raise SystemExit(1)
PY
                then
                    printf 'OK: runtime Ghostty underline color/style rendered by GPU path\n'
                else
                    mark_fail "runtime Ghostty underline color/style was not visible in screenshot"
                fi
                style_log="$(adb logcat -d -v brief 2>/dev/null || true)"
                if printf '%s\n' "$style_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >/dev/null; then
                    printf '%s\n' "$style_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >&2
                    mark_fail "runtime Ghostty style log contains fallback/crash markers"
                else
                    printf 'OK: runtime Ghostty style log has no fallback/crash markers\n'
                fi
            fi
            rm -f "$tmp_style_screenshot"
            adb shell "run-as com.termux rm -f files/home/ghostty_style_smoke.sh $style_log" >/dev/null 2>&1 || true
            adb shell rm -f /data/local/tmp/ghostty_style_smoke.sh >/dev/null 2>&1 || true
        fi

        if [[ "$runtime_kitty_check" == true ]]; then
            smoke_script="/data/data/com.termux/files/home/ghostty_kitty_smoke.sh"
            tmp_smoke="$(mktemp)"
            cat > "$tmp_smoke" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
printf '\033[2J\033[Hghostty kitty png smoke\n'
printf '\033_Ga=T,f=100,q=0;iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==\033\\'
printf '\nghostty-kitty-png-smoke-done\n'
sleep 3
EOF
            adb push "$tmp_smoke" /data/local/tmp/ghostty_kitty_smoke.sh >/dev/null
            rm -f "$tmp_smoke"
            if ! adb shell "run-as com.termux sh -c 'cp /data/local/tmp/ghostty_kitty_smoke.sh files/home/ghostty_kitty_smoke.sh && chmod 700 files/home/ghostty_kitty_smoke.sh'" >/dev/null 2>&1; then
                mark_fail "runtime Kitty smoke script could not be installed into Termux home"
            else
                adb shell am force-stop com.termux >/dev/null
                sleep 1
                adb logcat -c
                adb shell am start -n com.termux/com.termux.app.TermuxActivity >/dev/null
                sleep 4
                adb shell input tap 120 520 >/dev/null 2>&1 || true
                adb shell input text "$smoke_script" >/dev/null
                adb shell input keyevent ENTER >/dev/null

                kitty_wait_seconds="${GHOSTTY_RUNTIME_KITTY_WAIT_SECONDS:-12}"
                kitty_log=""
                for ((second = 0; second < kitty_wait_seconds; second++)); do
                    kitty_log="$(adb logcat -d -v brief 2>/dev/null || true)"
                    if printf '%s\n' "$kitty_log" | rg "kittyImages=[1-9][0-9]*" >/dev/null; then
                        break
                    fi
                    sleep 1
                done

                if printf '%s\n' "$kitty_log" | rg "kittyImages=[1-9][0-9]*" >/dev/null; then
                    printf 'OK: runtime Kitty PNG smoke reached Ghostty GPU texture renderer\n'
                else
                    mark_fail "runtime Kitty PNG smoke did not reach Ghostty GPU texture renderer"
                fi
                if printf '%s\n' "$kitty_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >/dev/null; then
                    printf '%s\n' "$kitty_log" | rg "TerminalBuffer fallback|Refusing to render non-Ghostty|TerminalView requires a Ghostty-backed|UnsatisfiedLinkError|FATAL EXCEPTION" >&2
                    mark_fail "runtime Kitty log contains fallback/crash markers"
                else
                    printf 'OK: runtime Kitty log has no fallback/crash markers\n'
                fi
            fi
            adb shell "run-as com.termux rm -f files/home/ghostty_kitty_smoke.sh" >/dev/null 2>&1 || true
            adb shell rm -f /data/local/tmp/ghostty_kitty_smoke.sh >/dev/null 2>&1 || true
        fi
    fi
fi

exit "$fail"
