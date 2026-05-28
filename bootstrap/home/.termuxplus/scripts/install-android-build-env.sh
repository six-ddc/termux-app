#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

if [ "$#" -gt 1 ]; then
  tp_die "usage: $(basename "$0") [termux-app-project-dir]"
fi

TERMUX_ANDROID_SDK_ROOT="${TERMUX_ANDROID_SDK_ROOT:-$HOME/android-sdk}"
TERMUX_ANDROID_NDK_VERSION="${TERMUX_ANDROID_NDK_VERSION:-29.0.14206865}"
TERMUX_ANDROID_COMPILE_SDK_VERSION="${TERMUX_ANDROID_COMPILE_SDK_VERSION:-34}"
TERMUX_ANDROID_BUILD_ABI="${TERMUX_ANDROID_BUILD_ABI:-arm64-v8a}"
TERMUX_ANDROID_BOOTSTRAP_ARCHS="${TERMUX_ANDROID_BOOTSTRAP_ARCHS:-aarch64}"
TERMUX_ANDROID_BUILD_TOOLS_VERSIONS="${TERMUX_ANDROID_BUILD_TOOLS_VERSIONS:-35.0.0 36.0.0}"
TERMUX_ANDROID_PLATFORM_TOOLS_REVISION="${TERMUX_ANDROID_PLATFORM_TOOLS_REVISION:-37.0.0}"
TERMUX_ANDROID_PROJECT_DIR="${TERMUX_ANDROID_PROJECT_DIR:-${1:-$PWD}}"
TERMUX_ANDROID_WRITE_LOCAL_PROPERTIES="${TERMUX_ANDROID_WRITE_LOCAL_PROPERTIES:-true}"
TERMUX_ANDROID_DOWNLOAD_SDK_PACKAGES="${TERMUX_ANDROID_DOWNLOAD_SDK_PACKAGES:-true}"
TERMUX_ANDROID_APT_PACKAGES="${TERMUX_ANDROID_APT_PACKAGES:-openjdk-21 git clang make zip unzip coreutils findutils aapt aapt2 aidl android-tools apksigner d8 ndk-multilib ndk-sysroot}"

if [ -d "$TERMUX_ANDROID_PROJECT_DIR" ]; then
  TERMUX_ANDROID_PROJECT_DIR="$(CDPATH= cd -- "$TERMUX_ANDROID_PROJECT_DIR" && pwd)"
fi

ANDROID_HOME="$TERMUX_ANDROID_SDK_ROOT"
ANDROID_SDK_ROOT="$ANDROID_HOME"
NDK_DIR="$ANDROID_HOME/ndk/$TERMUX_ANDROID_NDK_VERSION"
export ANDROID_HOME ANDROID_SDK_ROOT

gradle_sdk_download_failed=false

require_arm64_termux() {
  arch="$(tp_bootstrap_arch)"
  case "$arch" in
    aarch64|arm64) ;;
    *) tp_die "Termux Android build environment only supports aarch64/arm64; got '$arch'" ;;
  esac

  case "$TERMUX_ANDROID_BUILD_ABI" in
    arm64-v8a) ;;
    *) tp_die "TERMUX_ANDROID_BUILD_ABI must be arm64-v8a for this local NDK shim" ;;
  esac
}

is_termux_app_project() {
  [ -x "$TERMUX_ANDROID_PROJECT_DIR/gradlew" ] && [ -f "$TERMUX_ANDROID_PROJECT_DIR/app/build.gradle" ]
}

install_packages() {
  tp_require_command apt-get
  tp_require_command dpkg-query

  # Package names are controlled by this installer default and do not contain spaces.
  # shellcheck disable=SC2086
  tp_install_packages $TERMUX_ANDROID_APT_PACKAGES
}

verify_required_tools() {
  for tool in java clang aapt aapt2 aidl adb apksigner d8 r8 zipalign llvm-strip; do
    tp_require_command "$tool"
  done
}

write_source_properties() {
  target_file="$1"
  desc="$2"
  revision="$3"
  temp_dir="$(tp_mktemp_dir)"
  temp_file="$temp_dir/source.properties"

  cat > "$temp_file" <<EOF
Pkg.Desc=$desc
Pkg.Revision=$revision
EOF
  tp_replace_file "$temp_file" "$target_file" 644
  rm -rf "$temp_dir"
}

write_project_local_properties() {
  case "$TERMUX_ANDROID_WRITE_LOCAL_PROPERTIES" in
    true) ;;
    false) return 0 ;;
    *) tp_die "TERMUX_ANDROID_WRITE_LOCAL_PROPERTIES must be true or false" ;;
  esac

  if ! is_termux_app_project; then
    tp_warn "Termux app Gradle project not found at $TERMUX_ANDROID_PROJECT_DIR; skipping local.properties"
    return 0
  fi

  temp_dir="$(tp_mktemp_dir)"
  temp_file="$temp_dir/local.properties"
  target_file="$TERMUX_ANDROID_PROJECT_DIR/local.properties"

  if [ -f "$target_file" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        sdk.dir=*|ndk.dir=*) ;;
        *) printf '%s\n' "$line" ;;
      esac
    done < "$target_file" > "$temp_file"
  else
    : > "$temp_file"
  fi

  {
    printf 'sdk.dir=%s\n' "$ANDROID_HOME"
    printf 'ndk.dir=%s\n' "$NDK_DIR"
  } >> "$temp_file"

  tp_replace_file "$temp_file" "$target_file" 600
  rm -rf "$temp_dir"
  tp_log "configured Gradle SDK paths in $target_file"
}

install_platform_tools_shim() {
  adb_path="$(command -v adb || true)"
  [ -n "$adb_path" ] || tp_die "required command not found: adb"

  mkdir -p "$ANDROID_HOME/platform-tools"
  ln -sf "$adb_path" "$ANDROID_HOME/platform-tools/adb"
  write_source_properties \
    "$ANDROID_HOME/platform-tools/source.properties" \
    "Android SDK Platform-Tools" \
    "$TERMUX_ANDROID_PLATFORM_TOOLS_REVISION"
}

install_build_tools_symlinks() {
  found_build_tools=false

  for version in $TERMUX_ANDROID_BUILD_TOOLS_VERSIONS; do
    dir="$ANDROID_HOME/build-tools/$version"
    [ -d "$dir" ] || continue
    found_build_tools=true

    for tool in aapt aapt2 aidl apksigner d8 r8 zipalign; do
      tool_path="$(command -v "$tool" || true)"
      [ -n "$tool_path" ] || tp_die "Missing required tool: $tool"
      ln -sf "$tool_path" "$dir/$tool"
    done

    tp_log "linked Termux-native build-tools in $dir"
  done

  if [ "$found_build_tools" != "true" ]; then
    tp_warn "no SDK build-tools directories found under $ANDROID_HOME/build-tools"
  fi
}

install_ndk_shim() {
  mkdir -p "$NDK_DIR"
  write_source_properties "$NDK_DIR/source.properties" "Android NDK" "$TERMUX_ANDROID_NDK_VERSION"

  temp_dir="$(tp_mktemp_dir)"
  ndk_build_file="$temp_dir/ndk-build"

  cat > "$ndk_build_file" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu

termux_prefix=${PREFIX:-/data/data/com.termux/files/usr}
app_build_script=
abi=
ndk_out=
ndk_libs_out=
dry_run=0
clean=0
cflags=
ldflags=

for arg in "$@"; do
    case "$arg" in
        -n)
            dry_run=1
            ;;
        clean)
            clean=1
            ;;
        APP_BUILD_SCRIPT=*)
            app_build_script=${arg#APP_BUILD_SCRIPT=}
            ;;
        APP_ABI=*)
            abi=${arg#APP_ABI=}
            ;;
        NDK_OUT=*)
            ndk_out=${arg#NDK_OUT=}
            ;;
        NDK_LIBS_OUT=*)
            ndk_libs_out=${arg#NDK_LIBS_OUT=}
            ;;
        APP_CFLAGS+=*|APP_CPPFLAGS+=*)
            flag=${arg#*+=}
            case "$flag" in
                -Wl,*) ldflags="$ldflags $flag" ;;
                *) cflags="$cflags $flag" ;;
            esac
            ;;
    esac
done

if [ -z "$app_build_script" ] || [ -z "$abi" ] || [ -z "$ndk_out" ] || [ -z "$ndk_libs_out" ]; then
    echo "Termux ndk-build shim: missing APP_BUILD_SCRIPT, APP_ABI, NDK_OUT, or NDK_LIBS_OUT" >&2
    exit 2
fi

if [ "$abi" != "arm64-v8a" ]; then
    echo "Termux ndk-build shim: unsupported ABI $abi" >&2
    exit 2
fi

src_dir=$(cd "$(dirname "$app_build_script")" && pwd)
needs_cxx=0
link_libs=
case "$src_dir" in
    */app/src/main/cpp)
        module=termux-bootstrap
        sources="termux-bootstrap-zip.S termux-bootstrap.c"
        ;;
    */terminal-emulator/src/main/jni)
        module=termux
        sources="termux.c"
        ;;
    */termux-shared/src/main/cpp)
        module=local-socket
        sources="local-socket.cpp"
        needs_cxx=1
        link_libs="-llog"
        ;;
    *)
        echo "Termux ndk-build shim: unsupported Android.mk at $app_build_script" >&2
        exit 2
        ;;
esac

obj_dir="$ndk_out/local/$abi/objs/$module"
so_dir="$ndk_out/local/$abi"
lib_dir="$ndk_libs_out/$abi"
so_file="$so_dir/lib$module.so"
lib_file="$lib_dir/lib$module.so"

if [ "$clean" = 1 ]; then
    rm -rf "$obj_dir" "$so_file" "$lib_file"
    exit 0
fi

compile_flags="-fPIC -D_GNU_SOURCE -I$termux_prefix/include -I$src_dir $cflags"
objects=
for src in $sources; do
    objects="$objects $obj_dir/${src%.*}.o"
done

if [ "$dry_run" = 1 ]; then
    for src in $sources; do
        obj="$obj_dir/${src%.*}.o"
        case "$src" in
            *.S)
                echo "clang $compile_flags -c $src_dir/$src -o $obj"
                ;;
            *.cpp)
                echo "clang++ $compile_flags -c $src_dir/$src -o $obj"
                ;;
            *)
                echo "clang $compile_flags -c $src_dir/$src -o $obj"
                ;;
        esac
    done
    if [ "$needs_cxx" = 1 ]; then
        echo "clang++ -shared $ldflags -o $so_file $objects $link_libs"
    else
        echo "clang -shared $ldflags -o $so_file $objects $link_libs"
    fi
    exit 0
fi

mkdir -p "$obj_dir" "$so_dir" "$lib_dir"
for src in $sources; do
    obj="$obj_dir/${src%.*}.o"
    case "$src" in
        *.S)
            (cd "$src_dir" && clang $compile_flags -c "$src" -o "$obj")
            ;;
        *.cpp)
            clang++ $compile_flags -c "$src_dir/$src" -o "$obj"
            ;;
        *)
            clang $compile_flags -c "$src_dir/$src" -o "$obj"
            ;;
    esac
done

if [ "$needs_cxx" = 1 ]; then
    clang++ -shared $ldflags -o "$so_file" $objects $link_libs
else
    clang -shared $ldflags -o "$so_file" $objects $link_libs
fi
cp "$so_file" "$lib_file"
EOF

  tp_replace_file "$ndk_build_file" "$NDK_DIR/ndk-build" 700
  rm -rf "$temp_dir"

  llvm_strip="$(command -v llvm-strip || true)"
  [ -n "$llvm_strip" ] || tp_die "llvm-strip is missing. Install or repair the Termux clang package."

  mkdir -p "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
  ln -sf "$llvm_strip" "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
}

download_sdk_packages_if_possible() {
  case "$TERMUX_ANDROID_DOWNLOAD_SDK_PACKAGES" in
    true) ;;
    false) return 0 ;;
    *) tp_die "TERMUX_ANDROID_DOWNLOAD_SDK_PACKAGES must be true or false" ;;
  esac

  if ! is_termux_app_project; then
    tp_warn "Termux app Gradle project not found at $TERMUX_ANDROID_PROJECT_DIR; skipping SDK package download"
    return 0
  fi

  tp_log "asking Gradle to download SDK packages for android-$TERMUX_ANDROID_COMPILE_SDK_VERSION"
  if (
    cd "$TERMUX_ANDROID_PROJECT_DIR"
    TERMUX_BOOTSTRAP_ARCHS="$TERMUX_ANDROID_BOOTSTRAP_ARCHS" ./gradlew :app:tasks \
      -PcompileSdkVersion="$TERMUX_ANDROID_COMPILE_SDK_VERSION" \
      -Pandroid.injected.build.abi="$TERMUX_ANDROID_BUILD_ABI"
  ); then
    gradle_sdk_download_failed=false
  else
    gradle_sdk_download_failed=true
    tp_warn "Gradle SDK package download failed; continuing to install Termux-native shims"
  fi
}

verify_environment_state() {
  if [ ! -f "$ANDROID_HOME/platforms/android-$TERMUX_ANDROID_COMPILE_SDK_VERSION/android.jar" ]; then
    tp_warn "missing $ANDROID_HOME/platforms/android-$TERMUX_ANDROID_COMPILE_SDK_VERSION/android.jar; rerun this script from the repository root or run Gradle once"
  fi

  if [ "$gradle_sdk_download_failed" = "true" ]; then
    tp_warn "if Gradle downloaded SDK build-tools before failing, rerun this script to relink those executables"
  fi
}

print_next_steps() {
  cat <<EOF
[termuxplus] Android build environment is configured.
[termuxplus] Build from the repository root with:
[termuxplus]   TERMUX_BOOTSTRAP_ARCHS=$TERMUX_ANDROID_BOOTSTRAP_ARCHS ./gradlew :app:assembleDebug \\
[termuxplus]     -Pandroid.aapt2FromMavenOverride="\$PREFIX/bin/aapt2" \\
[termuxplus]     -PcompileSdkVersion=$TERMUX_ANDROID_COMPILE_SDK_VERSION \\
[termuxplus]     -Pandroid.injected.build.abi=$TERMUX_ANDROID_BUILD_ABI \\
[termuxplus]     -Pandroid.injected.testOnly=false
EOF
}

require_arm64_termux
install_packages
verify_required_tools
mkdir -p "$ANDROID_HOME"
write_project_local_properties
install_platform_tools_shim
install_ndk_shim
download_sdk_packages_if_possible
install_build_tools_symlinks
verify_environment_state
print_next_steps
