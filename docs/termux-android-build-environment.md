# Building the Termux App Android APK Inside Termux

This document describes the local Android build environment used to build this
TermuxPlus fork directly inside the Termux app on an Android device.

The setup is intentionally narrow. It is meant to build an `arm64-v8a` debug APK
for this repository. It is not a full official Android Studio SDK/NDK
installation, and it is not suitable for validating general multi-ABI NDK
projects.

## What Is Real And What Is Shimmed

Real components:

- OpenJDK from Termux.
- Gradle wrapper from this repository.
- Android Gradle Plugin downloaded by Gradle.
- Official Android SDK platform packages downloaded into `$HOME/android-sdk`,
  including real `android.jar` files.
- Termux native Android tools: `aapt`, `aapt2`, `aidl`, `apksigner`, `d8`,
  `r8`, `zipalign`, `adb`, `clang`, `clang++`, and LLVM tools.
- The generated APK. The build produces real Java bytecode, real dex files,
  real resources, real native libraries, and a real signed APK.

Shimmed or limited components:

- The NDK directory is a minimal compatibility shell for Android Gradle Plugin.
- `ndk-build` is a local shim script, not the official NDK build system.
- The shim supports only `arm64-v8a`.
- The shim supports only this repository's current native modules:
  `termux-bootstrap`, `termux`, and `local-socket`.
- Build-tools executable files under `$ANDROID_HOME/build-tools/*` are replaced
  with symlinks to Termux-native tools.
- `llvm-strip` under the NDK prebuilt path is a symlink to Termux's
  `llvm-strip`.

## Tested Baseline

The environment was tested with:

- Termux on an `aarch64` Android device.
- OpenJDK 21.
- Gradle 9.2.1 from this repository's wrapper.
- Android Gradle Plugin 8.13.2.
- Termux `aapt2` reporting `Android Asset Packaging Tool (aapt) 2.19`.
- Termux clang 21.1.x.

Because the Termux `aapt2` currently fails with this project's default API
35/36 platform jars, the working local build uses:

```sh
-PcompileSdkVersion=34
```

Do not commit this as a project default unless the team intentionally decides to
lower the compile SDK for everyone.

## 1. Install Termux Packages

Run this inside Termux:

```sh
pkg update
pkg install -y \
  openjdk-21 \
  git \
  clang \
  make \
  zip \
  unzip \
  coreutils \
  findutils \
  aapt \
  aapt2 \
  aidl \
  android-tools \
  apksigner \
  d8 \
  ndk-multilib \
  ndk-sysroot
```

Check the critical tools:

```sh
java -version
clang --version
aapt2 version
adb version
apksigner version
```

## 2. Configure The Local SDK Path

From the repository root:

```sh
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$ANDROID_HOME"

cat > local.properties <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_HOME/ndk/29.0.14206865
EOF
```

`local.properties` is intentionally local-only and should not be committed.

## 3. Let Gradle Download SDK Packages

Android Gradle Plugin can download missing SDK platform and build-tools packages
into `$ANDROID_HOME`.

Run:

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:tasks \
  -PcompileSdkVersion=34 \
  -Pandroid.injected.build.abi=arm64-v8a
```

After it completes, check:

```sh
test -f "$ANDROID_HOME/platforms/android-34/android.jar"
test -d "$ANDROID_HOME/build-tools/35.0.0"
```

If the SDK package directories were not created, run the final assemble command
once after installing the shim below. If Gradle downloads new build-tools during
that attempt and then fails with an `Exec format error`, rerun the build-tools
symlink step and build again.

## 4. Replace SDK Build-Tools Executables With Termux Tools

Official Android SDK build-tools packages contain Linux desktop binaries. Those
binaries cannot run inside Android/Termux. Keep the metadata and jars from the
official packages, but replace executable tools with Termux-native tools.

```sh
export ANDROID_HOME="$HOME/android-sdk"

for version in 35.0.0 36.0.0; do
  dir="$ANDROID_HOME/build-tools/$version"
  [ -d "$dir" ] || continue

  for tool in aapt aapt2 aidl apksigner d8 r8 zipalign; do
    tool_path="$(command -v "$tool" || true)"
    if [ -n "$tool_path" ]; then
      ln -sf "$tool_path" "$dir/$tool"
    else
      echo "Missing required tool: $tool" >&2
      exit 1
    fi
  done
done
```

Do not replace `core-lambda-stubs.jar`. It must remain the official SDK jar.

Create a minimal `platform-tools` directory that points to Termux `adb`:

```sh
mkdir -p "$ANDROID_HOME/platform-tools"
ln -sf "$(command -v adb)" "$ANDROID_HOME/platform-tools/adb"
cat > "$ANDROID_HOME/platform-tools/source.properties" <<EOF
Pkg.Desc=Android SDK Platform-Tools
Pkg.Revision=37.0.0
EOF
```

## 5. Install The Minimal NDK Shim

Create the NDK compatibility directory:

```sh
export ANDROID_HOME="$HOME/android-sdk"
NDK_DIR="$ANDROID_HOME/ndk/29.0.14206865"

mkdir -p "$NDK_DIR"
cat > "$NDK_DIR/source.properties" <<EOF
Pkg.Desc=Android NDK
Pkg.Revision=29.0.14206865
EOF
```

Install `ndk-build`:

```sh
cat > "$NDK_DIR/ndk-build" <<'EOF'
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

chmod +x "$NDK_DIR/ndk-build"
```

Install the `llvm-strip` path expected by Android Gradle Plugin:

```sh
LLVM_STRIP="$(command -v llvm-strip || true)"
[ -n "$LLVM_STRIP" ] || {
  echo "llvm-strip is missing. Install or repair the Termux clang package." >&2
  exit 1
}

mkdir -p "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
ln -sf "$LLVM_STRIP" "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
```

## 6. Build The App

Use this command for the local Termux build:

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -PcompileSdkVersion=34 \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false
```

`TERMUX_BOOTSTRAP_ARCHS` only selects which official Termux bootstrap
architectures are downloaded and packaged. TermuxPlus no longer supports a
custom bootstrap directory or generated bootstrap zips; do not use
`TERMUX_BOOTSTRAP_DIR`. TermuxPlus files are packaged as APK assets and copied
into `$HOME`/`$PREFIX` after the official bootstrap finishes.

The `android.injected.testOnly=false` flag is important when installing through
the system package installer. Without it, Android Gradle Plugin may mark the APK
as `android:testOnly="true"`, and some ROM package installers report that as an
invalid package instead of showing a clear `testOnly` error.

The expected APK path is:

```sh
app/build/intermediates/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

## 7. Verify The APK

```sh
APK=app/build/intermediates/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk

ls -lh "$APK"
apksigner verify --verbose "$APK"
zipinfo -1 "$APK" | grep '^lib/arm64-v8a/'
```

The APK should include:

```text
lib/arm64-v8a/liblocal-socket.so
lib/arm64-v8a/libtermux-bootstrap.so
lib/arm64-v8a/libtermux.so
```

## 8. Install On A Device

If `adb` can see the target device:

```sh
adb install -r app/build/intermediates/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

For first-start bootstrap validation, clear app data before launch:

```sh
adb shell pm clear com.termux
adb shell am start -n com.termux/com.termux.app.TermuxActivity
```

## Troubleshooting

### `aapt2` fails with API 35 or API 36

Use `-PcompileSdkVersion=34`. The current Termux `aapt2` is too old for this
repository's default API 35/36 platform jars.

### `Exec format error` from build-tools

An official SDK package probably downloaded Linux desktop binaries after the
symlink step. Rerun the build-tools symlink step.

### Gradle tries to build `armeabi-v7a`, `x86`, or `x86_64`

Make sure the build command includes:

```sh
-Pandroid.injected.build.abi=arm64-v8a
```

The local NDK shim only supports `arm64-v8a`.

### The package installer says the APK is invalid

Check whether the APK is marked as test-only:

```sh
aapt dump xmltree app/build/intermediates/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk AndroidManifest.xml | grep testOnly
```

If `android:testOnly="true"` is present, rebuild with:

```sh
-Pandroid.injected.testOnly=false
```

### Native build says `unsupported Android.mk`

The shim only knows this repository's current native modules. If a new native
module is added, update the `case "$src_dir"` block in the shim.

### Native libraries are missing from the APK

The dry-run output from `ndk-build -n` must print compiler and linker commands,
not just `.so` paths. Android Gradle Plugin parses those commands to populate
its native build model.

### `llvm-strip` cannot be started

Recreate the strip symlink:

```sh
NDK_DIR="$HOME/android-sdk/ndk/29.0.14206865"
mkdir -p "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
ln -sf "$(command -v llvm-strip)" "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
```

## Cleanup Notes

The following paths are local build environment state and should not be
committed:

- `local.properties`
- `$HOME/android-sdk`
- `app/build`
- `terminal-emulator/build`
- `terminal-view/build`
- `termux-shared/build`
- `autotermux/build`
