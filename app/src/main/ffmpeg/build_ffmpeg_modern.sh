#!/usr/bin/env bash
#
# Builds OpenSSL and FFmpeg for Android and installs the headers and shared
# libraries into app/src/main/jni/ffmpeg/ffmpeg/<abi>/{include,lib}.
#
# usage: build_ffmpeg_modern.sh <ndk-dir> [abi...]
#
set -euo pipefail

NDK="${1:?usage: $0 <ndk-dir> [abi...]}"
shift || true
ABIS=("$@")
if [ "${#ABIS[@]}" -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a)
fi

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
API=21

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$HERE/build"
DL="$BUILD/downloads"
OUT_ROOT="$(cd "$HERE/.." && pwd)/jni/ffmpeg/ffmpeg"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
JOBS="$(nproc 2>/dev/null || echo 4)"

if [ ! -d "$TOOLCHAIN" ]; then
    echo "NDK toolchain not found: $TOOLCHAIN" >&2
    exit 1
fi

mkdir -p "$DL"

fetch() {
    local dest="$1"; shift
    if [ -s "$dest" ]; then
        return 0
    fi
    local url
    for url in "$@"; do
        echo "Downloading $url"
        if curl -fL --retry 3 --connect-timeout 30 -o "$dest.part" "$url"; then
            mv "$dest.part" "$dest"
            return 0
        fi
    done
    echo "Could not download $(basename "$dest")" >&2
    return 1
}

fetch "$DL/ffmpeg-$FFMPEG_VERSION.tar.xz" \
    "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" \
    "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$FFMPEG_VERSION.tar.gz"
fetch "$DL/openssl-$OPENSSL_VERSION.tar.gz" \
    "https://www.openssl.org/source/openssl-$OPENSSL_VERSION.tar.gz" \
    "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"

build_openssl() {
    local abi="$1" prefix="$2" target="$3" triple="$4"
    local src="$BUILD/src-openssl-$abi"

    rm -rf "$src" "$prefix"
    mkdir -p "$src"
    tar -xf "$DL/openssl-$OPENSSL_VERSION.tar.gz" -C "$src" --strip-components=1

    (
        cd "$src"
        export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK"
        local binutils="$NDK/toolchains/$triple-4.9/prebuilt/linux-x86_64/bin"
        if [ -d "$binutils" ]; then
            export PATH="$TOOLCHAIN/bin:$binutils:$PATH"
        else
            export PATH="$TOOLCHAIN/bin:$PATH"
        fi
        export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"

        ./Configure "$target" -D__ANDROID_API__=$API \
            no-shared no-asm no-tests no-ui-console no-comp no-engine no-dso \
            --prefix="$prefix" --openssldir="$prefix/ssl" -fPIC
        make -j"$JOBS" build_libs
        make install_dev
    )
}

build_ffmpeg() {
    local abi="$1" ossl="$2" arch="$3" cpu="$4" cc_prefix="$5" extra_cflags="$6"
    local src="$BUILD/src-ffmpeg-$abi"
    local prefix="$BUILD/ffmpeg-$FFMPEG_VERSION-$abi"

    rm -rf "$src" "$prefix"
    mkdir -p "$src"
    tar -xf "$DL/ffmpeg-$FFMPEG_VERSION.tar.xz" -C "$src" --strip-components=1

    local ossl_libdir="$ossl/lib"
    [ -d "$ossl/lib64" ] && ossl_libdir="$ossl/lib64"

    (
        cd "$src"
        export PKG_CONFIG_LIBDIR="$ossl_libdir/pkgconfig"
        export PKG_CONFIG_PATH=""

        ./configure \
            --prefix="$prefix" \
            --enable-cross-compile --target-os=android --arch="$arch" --cpu="$cpu" \
            --cc="$TOOLCHAIN/bin/$cc_prefix-clang" \
            --cxx="$TOOLCHAIN/bin/$cc_prefix-clang++" \
            --ar="$TOOLCHAIN/bin/llvm-ar" \
            --nm="$TOOLCHAIN/bin/llvm-nm" \
            --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
            --strip="$TOOLCHAIN/bin/llvm-strip" \
            --pkg-config=pkg-config --pkg-config-flags=--static \
            --enable-shared --disable-static --enable-pic --enable-small \
            --disable-programs --disable-doc --disable-debug --disable-autodetect \
            --disable-avdevice --disable-avfilter --disable-postproc \
            --disable-encoders --disable-muxers --disable-hwaccels \
            --disable-indevs --disable-outdevs --disable-asm \
            --disable-protocols \
            --enable-protocol=file,http,https,tcp,tls,udp,rtp,pipe,mmsh,mmst,rtmp,rtmps,rtmpt,rtmpts,crypto,data,httpproxy \
            --enable-zlib --enable-openssl --enable-version3 \
            --extra-cflags="-O2 -fPIC -I$ossl/include $extra_cflags" \
            --extra-ldflags="-L$ossl_libdir -Wl,-z,max-page-size=16384"

        make -j"$JOBS"
        make install
    )

    local out="$OUT_ROOT/$abi"
    rm -rf "$out"
    mkdir -p "$out/lib" "$out/include"
    cp -r "$prefix/include/." "$out/include/"
    local lib
    for lib in avcodec avformat avutil swresample swscale; do
        cp "$prefix/lib/lib$lib.so" "$out/lib/lib$lib.so"
        "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$out/lib/lib$lib.so"
        echo "$lib: $(du -h "$out/lib/lib$lib.so" | cut -f1)  $("$TOOLCHAIN/bin/llvm-readelf" -d "$out/lib/lib$lib.so" | grep -E 'SONAME' | sed 's/.*Library soname: //')"
    done
}

for abi in "${ABIS[@]}"; do
    if [ -f "$OUT_ROOT/$abi/lib/libavformat.so" ]; then
        echo "FFmpeg for $abi already built, skipping"
        continue
    fi

    case "$abi" in
        arm64-v8a)
            triple=aarch64-linux-android; cc_prefix="aarch64-linux-android$API"
            ossl_target=android-arm64; arch=aarch64; cpu=armv8-a; extra_cflags=""
            ;;
        armeabi-v7a)
            triple=arm-linux-androideabi; cc_prefix="armv7a-linux-androideabi$API"
            ossl_target=android-arm; arch=arm; cpu=armv7-a
            extra_cflags="-mfpu=vfpv3-d16 -mfloat-abi=softfp"
            ;;
        x86_64)
            triple=x86_64-linux-android; cc_prefix="x86_64-linux-android$API"
            ossl_target=android-x86_64; arch=x86_64; cpu=x86-64; extra_cflags=""
            ;;
        x86)
            triple=i686-linux-android; cc_prefix="i686-linux-android$API"
            ossl_target=android-x86; arch=x86; cpu=i686; extra_cflags="-mstackrealign"
            ;;
        *)
            echo "Unsupported ABI: $abi" >&2
            exit 1
            ;;
    esac

    ossl_prefix="$BUILD/openssl-$OPENSSL_VERSION-$abi"
    echo "=== OpenSSL $OPENSSL_VERSION for $abi"
    build_openssl "$abi" "$ossl_prefix" "$ossl_target" "$triple"
    echo "=== FFmpeg $FFMPEG_VERSION for $abi"
    build_ffmpeg "$abi" "$ossl_prefix" "$arch" "$cpu" "$cc_prefix" "$extra_cflags"
done

echo "Native FFmpeg build complete"
