#!/usr/bin/env bash
# Deterministic SHA-256 fingerprint of the delivered SOURCE SNAPSHOT: hash of a
# sorted manifest that itself hashes the working-tree BYTES of every relevant
# source/build/test/model file — staged, unstaged and relevant untracked files
# together. Because content (not git state) is hashed, a dirty tree is
# identified by its exact bytes and bare HEAD is never used as the matching
# source identity.
#
# Excluded from the manifest (cannot participate, so the fingerprint can never
# reference itself or churn with rebuilds): .git, build caches/outputs
# (build/ and .gradle/ trees anywhere), generated delivery metadata
# (artifacts/), and lane-orchestration material (task/). Machine- and
# session-specific ignored paths (local.properties, libs/repo/, logs) are
# excluded by --exclude-standard enumeration.
#
# No dependencies beyond git + sha256sum.
#
# Usage:
#   scripts/source-fingerprint.sh              # summary lines (fingerprint)
#   scripts/source-fingerprint.sh --manifest   # full sorted manifest
#   scripts/source-fingerprint.sh --self-check # prove invariants; hard-fails
set -euo pipefail

# Relevant top-level areas + build-config files: everything the delivered APK
# and its tests are produced from (see docs/TESTING.md, layer 4).
SCOPE_DIRS=(app docs gradle models scripts tests)
SCOPE_FILES=(build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat .gitlab-ci.yml)

ROOT="${SOURCE_FINGERPRINT_ROOT:-}"
if [ -z "$ROOT" ]; then ROOT="$(cd "$(dirname "$0")/.." && pwd)"; fi
cd "$ROOT"

# Emitted manifest entry: "<sha256|DELETED>\t<repo-relative path>".
manifest_entries() {
  local p
  while IFS= read -r -d '' p; do
    case "$p" in
      artifacts/*|task/*) continue ;;                       # generated metadata / planning
      build/*|*/build/*|.gradle/*|*/.gradle/*) continue ;;  # builds & caches
    esac
    if [ -f "$p" ]; then
      printf '%s\t%s\n' "$(sha256sum -- "$p" | cut -d' ' -f1)" "$p"
    else
      printf 'DELETED\t%s\n' "$p"   # tracked file staged in the index but gone
    fi
  done < <(git ls-files -c -o --exclude-standard -z -- \
             "${SCOPE_DIRS[@]}" "${SCOPE_FILES[@]}")
}

print_manifest() {
  printf '# source-fingerprint manifest v1\n'
  printf '# scope-paths: %s %s\n' "${SCOPE_DIRS[*]}" "${SCOPE_FILES[*]}"
  printf '# hashed content: working-tree bytes (staged+unstaged+relevant untracked); excluded: .git build/ .gradle/ artifacts/ task/; DELETED=tracked file absent\n'
  manifest_entries | LC_ALL=C sort
}

fingerprint() { print_manifest | sha256sum | cut -d' ' -f1; }

fail() { echo "SELF-CHECK FAIL: $*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3 (got: '$1', expected: '$2')"; }
assert_ne() { [ "$1" != "$2" ] || fail "$3"; }

summary() {
  local fp n; fp="$(fingerprint)"
  n="$(manifest_entries | wc -l | tr -d ' ')"
  echo "source_content_sha256=$fp"
  echo "source_entries=$n"
  echo "source_dirty=$( [ -n "$(git status --porcelain)" ] && echo true || echo false )"
}

# Build a scratch mini-repo and prove the fingerprint reacts exactly to source
# content and to nothing else. Warns nothing, mutates nothing in the real tree.
self_check() (
  set -euo pipefail
  local t self
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' EXIT

  cd "$t"
  git init -q && git config user.email lane@invalid && git config user.name lane
  mkdir -p app/src/main/java app/src/test scripts tests/fixtures models docs artifacts .gradle/4 app/build/outputs
  printf 'plugins { kotlin("x") }\n' > settings.gradle.kts
  echo 'class A' > app/src/main/java/A.kt
  echo 'class' > app/src/test/T.kt
  printf '#!/bin/sh\n' > scripts/build.sh
  printf 'wav' > tests/fixtures/f.wav
  echo 'hashes' > models/SHA256SUMS
  echo '# d' > docs/D.md
  git add -A && git commit -qm base

  fp() { SOURCE_FINGERPRINT_ROOT="$t" "$self" "$@"; }   # summary/manifest passthrough
  fpval() { SOURCE_FINGERPRINT_ROOT="$t" "$self" | sed -n 's/^source_content_sha256=//p'; }

  local base
  base="$(fpval)" ; [ ${#base} = 64 ] || fail "fingerprint is not 64 hex chars: $base"

  # --manifest prints exactly the manifest whose hash is the fingerprint.
  local m mh; m="$(fp --manifest)"
  mh="$(printf '%s\n' "$m" | sha256sum | cut -d' ' -f1)"
  assert_eq "$mh" "$base" "--manifest output must hash to the fingerprint"
  local h; h="$(sha256sum "$t/app/src/main/java/A.kt" | cut -d' ' -f1)"
  printf '%s\n' "$m" | grep -qF "$(printf '%s\tapp/src/main/java/A.kt' "$h")" || fail "manifest missing correct hash for app/src/main/java/A.kt"

  # Build outputs, caches and generated metadata never move the fingerprint.
  printf 'apkbytes' > "$t/app/build/outputs/app-debug.apk"
  printf 'cache' > "$t/.gradle/4/evict.bin"
  printf 'APK_SHA256=whatever' > "$t/artifacts/delivery-summary.env"
  assert_eq "$(fpval)" "$base" "build/cache/metadata writes must not change the fingerprint"

  # Relevant untracked source bytes do count.
  printf 'untracked source' > "$t/app/src/main/java/B.kt"
  assert_ne "$(fpval)" "$base" "adding an untracked source file must change the fingerprint"
  dirty_state="$(fpval)"
  [ "$(fpval)" = "$dirty_state" ] || fail "fingerprint must be deterministic across runs"

  # Committing the same bytes (git state change) must not change the fingerprint.
  # This also tracks the build/cache/metadata junk, proving tracked-forbidden
  # paths are pruned by the manifest filter, not by gitignore alone.
  git add -A && git commit -qm second
  assert_ne "$(git rev-parse HEAD)" "$(git rev-parse HEAD~1)" "sanity: HEAD moved"
  assert_eq "$(fpval)" "$dirty_state" "committing identical bytes must not change the fingerprint"
  # Dirty flag flips false once the tree is clean.
  fp | grep -qx 'source_dirty=false' || fail "source_dirty must be false on a clean tree"

  # A source edit changes the fingerprint...
  printf 'class A2' > "$t/app/src/main/java/A.kt"
  assert_ne "$(fpval)" "$dirty_state" "editing source must change the fingerprint"
  post_edit="$(fpval)"

  # Path handling: spaces and exact-name matching.
  printf 'spacefile' > "$t/app/src/main/java/a b.kt"
  local m2 hs
  m2="$(fp --manifest)"
  hs="$(sha256sum "$t/app/src/main/java/a b.kt" | cut -d' ' -f1)"
  printf '%s\n' "$m2" | grep -qF "$(printf '%s\t%s' "$hs" 'app/src/main/java/a b.kt')" || fail "file with a space must appear with its exact path"
  assert_ne "$(fpval)" "$post_edit" "untracked file with space must change the fingerprint"
  rm "$t/app/src/main/java/a b.kt"
  assert_eq "$(fpval)" "$post_edit" "removing the space-named file must restore the fingerprint"

  # A tracked file deleted from the worktree is recorded and moves the fingerprint.
  rm "$t/app/src/test/T.kt"
  printf '%s\n' "$(fp --manifest)" | grep -q "^DELETED	app/src/test/T.kt$" || fail "deleted tracked file must be flagged DELETED"
  assert_ne "$(fpval)" "$dirty_state" "deleting a tracked source file must change the fingerprint"

  # Cwd independence: same snapshot computed from a subdirectory.
  local from_sub; from_sub="$(cd "$t/app" && SOURCE_FINGERPRINT_ROOT="$t" "$self" | grep '^source_content_sha256=')"
  local top; top="$(SOURCE_FINGERPRINT_ROOT="$t" "$self" | grep '^source_content_sha256=')"
  assert_eq "$(printf '%s\n' "$from_sub" | grep '^source_content_sha256=')" \
            "$(printf '%s\n' "$top" | grep '^source_content_sha256=')" \
            "fingerprint must be independent of invoking cwd"

  echo "source-fingerprint: SELF-CHECK PASSED"
)

case "${1:-}" in
  --self-check) self_check ;;
  --manifest)   print_manifest ;;
  "")           summary ;;
  *) echo "usage: scripts/source-fingerprint.sh [--manifest|--self-check]" >&2; exit 2 ;;
esac
