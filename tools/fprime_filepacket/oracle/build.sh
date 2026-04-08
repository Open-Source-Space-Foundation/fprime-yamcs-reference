#!/usr/bin/env bash
# Build the F´ FilePacket oracle program against the F´ static libraries
# produced by `fprime-util build`. Run from the repo root.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../../.." && pwd)
BUILD=$REPO/build-fprime-automatic-native
LIBS=$BUILD/lib/Linux

if [[ ! -d $LIBS ]]; then
  echo "ERROR: F´ build artifacts not found at $LIBS" >&2
  echo "Run 'cd FprimeYamcsReference/YamcsDeployment && fprime-util build' first." >&2
  exit 1
fi

OUT=$REPO/tools/fprime_filepacket/oracle/dump_vectors

# Link against every static lib in the F´ build dir, inside a --start-group
# so the linker resolves dependencies regardless of order. This is brutal
# but reliable; we don't need to know the dep DAG.
ALL_LIBS=()
for lib in "$LIBS"/lib*.a; do
  ALL_LIBS+=("$lib")
done

c++ -std=c++14 -DTGT_OS_TYPE_LINUX -O0 -g \
  -I"$REPO/lib/fprime" \
  -I"$REPO" \
  -I"$BUILD" \
  -I"$BUILD/F-Prime" \
  -I"$BUILD/cmake/platform/unix/Platform/.." \
  -I"$BUILD/.." \
  -I"$BUILD/F-Prime/default/config/.." \
  "$REPO/tools/fprime_filepacket/oracle/dump_vectors.cpp" \
  "$BUILD/F-Prime/Fw/Types/CMakeFiles/Fw_StringFormat_snprintf.dir/snprintf_format.cpp.o" \
  -Wl,--start-group "${ALL_LIBS[@]}" -Wl,--end-group \
  -lpthread -ldl \
  -o "$OUT"

echo "Built: $OUT"
