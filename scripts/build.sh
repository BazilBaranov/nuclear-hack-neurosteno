#!/usr/bin/env bash
# Deterministic build entry point. Environment is read from explicit variables so
# the same pin works on the developer host and in CI:
#
#   JAVA_HOME   default: host JDK path used for this project (see mentor docs) —
#               falls back to "java" on PATH.
#   ANDROID_HOME / ANDROID_SDK_ROOT — SDK location; also written to the *untracked*
#               local.properties only when absent (that file must stay out of Git).
#
# All arguments are passed through to ./gradlew, e.g.
#   ./scripts/build.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shim: local.properties holds the SDK path; regeneration is environment-specific.
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" && ! -s local.properties ]]; then
  echo "build.sh: ANDROID_HOME/ANDROID_SDK_ROOT not set and local.properties empty/missing." >&2
  exit 1
fi
if [[ -n "${ANDROID_HOME:-}" && ! -s local.properties ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi
if [[ -n "${ANDROID_SDK_ROOT:-}" && ! -s local.properties ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
fi
if [[ -n "${ANDROID_HOME:-}" && -s local.properties ]] \
   && ! grep -q '^sdk.dir=' local.properties; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" >> local.properties
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "build.sh: JAVA_HOME=${JAVA_HOME} has no bin/java." >&2
    exit 1
  fi
fi

exec ./gradlew "$@"
