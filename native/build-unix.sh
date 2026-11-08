#!/usr/bin/env bash
# Builds libusb and libairspyhf as shared libraries for the host platform and
# copies them to dist/native/<platform>. Works on Linux and macOS.
#
# Usage: native/build-unix.sh [airspyhf-git-ref]
#
# The ref may be a branch, a tag or a full commit hash. libusb is built only to
# link against: at runtime libairspyhf uses the libusb that jlibusb supplies,
# loaded from the same directory, so the jar does not contain libusb. It is
# built without udev, as jlibusb's is.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/native/versions.env"
REF="${1:-$AIRSPYHF_REF}"
WORK="${JAIRSPYHF_WORK_DIR:-$ROOT/build/native-work}"
JOBS="$(getconf _NPROCESSORS_ONLN)"

case "$(uname -s)" in
    Linux)  OS=linux ;;
    Darwin) OS=macos ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  ARCH=x86_64 ;;
    aarch64|arm64) ARCH=aarch64 ;;
    *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
PLATFORM="$OS-$ARCH"
OUT="$ROOT/dist/native/$PLATFORM"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT" "$ROOT/dist/native/licenses"
cd "$WORK"

echo "=== libusb $LIBUSB_VERSION ==="
TARBALL="libusb-$LIBUSB_VERSION.tar.bz2"
curl -fsSL -o "$TARBALL" \
    "https://github.com/libusb/libusb/releases/download/v$LIBUSB_VERSION/$TARBALL"
if command -v sha256sum >/dev/null; then
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | sha256sum -c -
else
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | shasum -a 256 -c -
fi
tar xjf "$TARBALL"

MIN_MACOS=11.0
USB_PREFIX="$WORK/usb"
USB_ARGS=(--prefix="$USB_PREFIX" --disable-static --enable-shared)
if [ "$OS" = linux ]; then
    USB_ARGS+=(--disable-udev)
else
    export CFLAGS="-O2 -mmacosx-version-min=$MIN_MACOS"
    export LDFLAGS="-mmacosx-version-min=$MIN_MACOS"
fi
(cd "libusb-$LIBUSB_VERSION" && ./configure "${USB_ARGS[@]}" >/dev/null && make -j"$JOBS" install >/dev/null)
unset CFLAGS LDFLAGS

echo "=== libairspyhf $REF ==="
# Fetching by ref, rather than cloning a branch, also accepts a commit hash.
git init --quiet airspyhf
git -C airspyhf fetch --quiet --depth 1 "$AIRSPYHF_REPOSITORY" "$REF"
git -c advice.detachedHead=false -C airspyhf checkout --quiet FETCH_HEAD
UPSTREAM_COMMIT="$(git -C airspyhf rev-parse HEAD)"
HEADER=airspyhf/libairspyhf/src/airspyhf.h
UPSTREAM_VERSION="$(sed -n 's/^#define AIRSPYHF_VERSION "\(.*\)"/\1/p' "$HEADER")"
if [ "$REF" = "$AIRSPYHF_REF" ] && [ "$UPSTREAM_VERSION" != "$AIRSPYHF_VERSION" ]; then
    echo "airspyhf.h at $REF is version $UPSTREAM_VERSION, not $AIRSPYHF_VERSION" >&2
    exit 1
fi

CMAKE_ARGS=(
    -S "$ROOT/native" -B build-airspyhf
    -DCMAKE_BUILD_TYPE=Release
    -DAIRSPYHF_SOURCE_DIR="$WORK/airspyhf"
    -DLIBUSB_INCLUDE_DIR="$USB_PREFIX/include/libusb-1.0"
)
if [ "$OS" = linux ]; then
    USB_LIB="$USB_PREFIX/lib/libusb-1.0.so"
    # $ORIGIN only: the build tree's paths must not leak into the shipped library.
    CMAKE_ARGS+=(-DCMAKE_BUILD_WITH_INSTALL_RPATH=ON -DCMAKE_INSTALL_RPATH='$ORIGIN')
else
    USB_LIB="$USB_PREFIX/lib/libusb-1.0.0.dylib"
    CMAKE_ARGS+=(-DCMAKE_OSX_DEPLOYMENT_TARGET="$MIN_MACOS")
fi
cmake "${CMAKE_ARGS[@]}" -DLIBUSB_LIBRARY="$USB_LIB" >/dev/null
cmake --build build-airspyhf --parallel "$JOBS" >/dev/null

echo "=== packaging $PLATFORM ==="
if [ "$OS" = linux ]; then
    cp -L build-airspyhf/libairspyhf.so "$OUT/libairspyhf.so"
    chmod 755 "$OUT"/*
    # Only glibc and libusb may remain as dependencies.
    readelf -d "$OUT/libairspyhf.so" | grep -E 'NEEDED|RUNPATH|RPATH'
else
    cp -L build-airspyhf/libairspyhf.dylib "$OUT/libairspyhf.dylib"
    chmod 755 "$OUT"/*
    # Point libairspyhf at jlibusb's libusb, which is extracted next to it, not
    # the absolute build path, and re-sign because install_name_tool
    # invalidates the ad-hoc signature.
    install_name_tool -id @rpath/libairspyhf.dylib "$OUT/libairspyhf.dylib"
    install_name_tool -change "$USB_LIB" @loader_path/libusb-1.0.0.dylib "$OUT/libairspyhf.dylib"
    codesign -f -s - "$OUT/libairspyhf.dylib"
    otool -L "$OUT/libairspyhf.dylib"
    if otool -L "$OUT/libairspyhf.dylib" | grep -qF "$WORK"; then
        echo "libairspyhf still refers to the build directory" >&2
        exit 1
    fi
fi

# Pinned sources must match the bindings. Tracking upstream master only warns,
# so that one new upstream function does not stop the nightly builds.
if ! "$ROOT/native/check-bindings.sh" "$HEADER"; then
    [ "$REF" != "$AIRSPYHF_REF" ] || exit 1
    echo "::warning::Bindings differ from airspyhf.h at $REF"
fi

# The library sources are BSD-3-Clause. Upstream renamed LICENSE to LICENSE.BSD
# when it added the GPL that covers only its build files and tools.
if [ -f airspyhf/LICENSE.BSD ]; then
    cp airspyhf/LICENSE.BSD "$ROOT/dist/native/licenses/libairspyhf-LICENSE"
else
    cp airspyhf/LICENSE "$ROOT/dist/native/licenses/libairspyhf-LICENSE"
fi
cat > "$OUT/build-info.properties" <<INFO
airspyhf.ref=$REF
airspyhf.commit=$UPSTREAM_COMMIT
airspyhf.version=$UPSTREAM_VERSION
libusb.version=$LIBUSB_VERSION
INFO
ls -l "$OUT"
