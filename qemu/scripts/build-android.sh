#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/qemu/version.env"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to NDK 27.2.12479018}"

HOST_TAG="linux-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
PREFIX="$ROOT_DIR/qemu/output/sysroot"
SRC_DIR="$ROOT_DIR/qemu/output/src"
BUILD_DIR="$ROOT_DIR/qemu/output/build"
STAGE_DIR="$ROOT_DIR/qemu/output/stage"

mkdir -p "$SRC_DIR" "$BUILD_DIR" "$STAGE_DIR" "$PREFIX"

CC="$TOOLCHAIN/bin/clang --target=${ANDROID_TRIPLE}${ANDROID_API}"
CXX="$TOOLCHAIN/bin/clang++ --target=${ANDROID_TRIPLE}${ANDROID_API}"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

export PATH="$TOOLCHAIN/bin:$PATH"
export PKG_CONFIG_ALLOW_CROSS=1

# QEMU requires GLib. Build it for Android rather than accidentally linking
# against the Ubuntu runner's host GLib. GLib's Android cross-build is based
# on Meson and a target-specific pkg-config sysroot.
GLIB_VERSION="2.80.5"
GLIB_ARCHIVE="glib-${GLIB_VERSION}.tar.xz"
GLIB_URL="https://download.gnome.org/sources/glib/2.80/${GLIB_ARCHIVE}"
GLIB_SHA256="9f23a9de803c695bbfde7e37d6626b18b9a83869689dd79019bf3ae66c3e6771"

if [[ ! -f "$SRC_DIR/$GLIB_ARCHIVE" ]]; then
  curl -fL --retry 3 -o "$SRC_DIR/$GLIB_ARCHIVE" "$GLIB_URL"
fi
echo "$GLIB_SHA256  $SRC_DIR/$GLIB_ARCHIVE" | sha256sum -c -

if [[ ! -d "$SRC_DIR/glib-${GLIB_VERSION}" ]]; then
  tar -xf "$SRC_DIR/$GLIB_ARCHIVE" -C "$SRC_DIR"
fi

cat > "$BUILD_DIR/android-aarch64.cross" <<EOF
[binaries]
c = ['$TOOLCHAIN/bin/clang', '--target=${ANDROID_TRIPLE}${ANDROID_API}']
cpp = ['$TOOLCHAIN/bin/clang++', '--target=${ANDROID_TRIPLE}${ANDROID_API}']
ar = '$AR'
strip = '$STRIP'
pkgconfig = '$TOOLCHAIN/bin/llvm-pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'

[properties]
c_args = ['--target=${ANDROID_TRIPLE}${ANDROID_API}']
c_link_args = ['--target=${ANDROID_TRIPLE}${ANDROID_API}']
EOF

# Build GLib with fallback dependencies and without optional host-oriented
# integration. The result is installed into the private Android sysroot.
meson setup "$BUILD_DIR/glib" "$SRC_DIR/glib-${GLIB_VERSION}" \
  --cross-file "$BUILD_DIR/android-aarch64.cross" \
  --prefix "$PREFIX" \
  --libdir lib \
  --buildtype release \
  --default-library static \
  --wrap-mode forcefallback \
  -Dinternal_pcre=true \
  -Dlibmount=disabled \
  -Dselinux=disabled \
  -Dxattr=false \
  -Dman-pages=disabled \
  -Ddocumentation=false \
  -Dtests=false \
  -Dinstalled_tests=false \
  -Dnls=disabled \
  -Dsysprof=disabled \
  -Dlibelf=disabled \
  -Doss_fuzz=disabled \
  -Dgobject_introspection=disabled
meson compile -C "$BUILD_DIR/glib"
meson install -C "$BUILD_DIR/glib"

# Use only target pkg-config metadata from our private sysroot.
PKG_WRAPPER="$BUILD_DIR/pkg-config-android"
cat > "$PKG_WRAPPER" <<EOF
#!/usr/bin/env bash
export PKG_CONFIG_DIR=
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$PREFIX"
exec pkg-config "\$@"
EOF
chmod +x "$PKG_WRAPPER"
export PKG_CONFIG="$PKG_WRAPPER"

QEMU_ARCHIVE_PATH="$SRC_DIR/$QEMU_ARCHIVE"
if [[ ! -f "$QEMU_ARCHIVE_PATH" ]]; then
  curl -fL --retry 3 -o "$QEMU_ARCHIVE_PATH" "$QEMU_URL"
fi
echo "$QEMU_SHA256  $QEMU_ARCHIVE_PATH" | sha256sum -c -

if [[ ! -d "$SRC_DIR/qemu-${QEMU_VERSION}" ]]; then
  tar -xf "$QEMU_ARCHIVE_PATH" -C "$SRC_DIR"
fi

QEMU_SRC="$SRC_DIR/qemu-${QEMU_VERSION}"
QEMU_BUILD="$BUILD_DIR/qemu"

# First target: one system emulator, TCG only. GUI/host integrations are
# intentionally disabled at this stage; VNC/display integration is a later
# Android-runtime milestone.
"$QEMU_SRC/configure" \
  --target-list="$QEMU_TARGET_LIST" \
  --cc="$CC" \
  --cxx="$CXX" \
  --host-cc=gcc \
  --cross-prefix="$TOOLCHAIN/bin/aarch64-linux-android-" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --prefix=/data/local/virtualpcvm/qemu \
  --disable-docs \
  --disable-werror \
  --disable-gio \
  --disable-gtk \
  --disable-sdl \
  --disable-opengl \
  --disable-vnc \
  --disable-spice \
  --disable-curl \
  --disable-gnutls \
  --disable-libssh \
  --disable-libnfs \
  --disable-libiscsi \
  --disable-cap-ng \
  --disable-xen \
  --disable-kvm \
  --disable-hvf \
  --disable-whpx \
  --disable-tpm \
  --disable-bpf \
  --disable-fdt \
  --disable-debug-info \
  --enable-tcg

meson compile -C "$QEMU_BUILD"

BINARY="$QEMU_BUILD/qemu-system-x86_64"
test -x "$BINARY"
"$BINARY" --version

rm -rf "$STAGE_DIR/qemu-system-x86_64"
mkdir -p "$STAGE_DIR/qemu-system-x86_64/bin"
cp "$BINARY" "$STAGE_DIR/qemu-system-x86_64/bin/qemu-system-x86_64"
chmod 0755 "$STAGE_DIR/qemu-system-x86_64/bin/qemu-system-x86_64"

cat > "$STAGE_DIR/qemu-system-x86_64/README.txt" <<EOF
VirtualPC-VM Android QEMU runtime
QEMU: ${QEMU_VERSION}
Host ABI: ${ANDROID_ABI}
Guest target: ${QEMU_TARGET_LIST}
Binary: qemu-system-x86_64
Build mode: TCG

This is the first headless native build. Display/VNC integration is a
separate Android integration stage.
EOF

echo "Built: $STAGE_DIR/qemu-system-x86_64/bin/qemu-system-x86_64"
