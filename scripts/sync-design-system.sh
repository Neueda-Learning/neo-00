#!/usr/bin/env bash
# Copy the canonical design system into every frontend that vendors it, or verify
# that none of them has drifted.
#
#   ./scripts/sync-design-system.sh          copy canonical -> targets
#   ./scripts/sync-design-system.sh --check  exit 1 if any target differs (CI)
#
# The design system cannot be an npm dependency here: the module repos are
# separate git repos, the Docker build context is frontend/, and the whole stack
# must build offline. So it is a vendored folder — and a vendored folder without a
# drift check is ten slowly diverging design systems.
#
# Fix the design system in ui-kit/. Never in a copy: a copy that has been edited
# is reported below and overwritten on the next sync.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/ui-kit/src/design-system"

TARGETS=(
  "$ROOT/frontend/src/design-system"
  "$ROOT/neo-01/frontend/src/design-system"
  "$ROOT/neo-02/frontend/src/design-system"
  "$ROOT/neo-03/frontend/src/design-system"
  "$ROOT/neo-04/frontend/src/design-system"
  "$ROOT/neo-05/frontend/src/design-system"
  "$ROOT/neo-06/frontend/src/design-system"
  "$ROOT/neo-07/frontend/src/design-system"
  "$ROOT/neo-08/frontend/src/design-system"
  "$ROOT/neo-09/frontend/src/design-system"
  "$ROOT/neo-10/frontend/src/design-system"
)

[ -d "$SOURCE" ] || { echo "no design system at $SOURCE" >&2; exit 1; }

CHECK=false
[ "${1:-}" = "--check" ] && CHECK=true

drift=0
for target in "${TARGETS[@]}"; do
  rel="${target#"$ROOT"/}"

  # A target whose repo is not checked out (a module the class has not created
  # yet) is skipped, not an error.
  if [ ! -d "$(dirname "$(dirname "$target")")" ]; then
    echo "skip   $rel  (no frontend/ here)"
    continue
  fi

  if $CHECK; then
    if [ ! -d "$target" ]; then
      echo "MISSING $rel"
      drift=1
    elif diff -r -q "$SOURCE" "$target" >/dev/null 2>&1; then
      echo "ok     $rel"
    else
      echo "DRIFT  $rel"
      diff -r -q "$SOURCE" "$target" 2>&1 | sed 's/^/         /'
      drift=1
    fi
  else
    rm -rf "$target"
    mkdir -p "$(dirname "$target")"
    cp -R "$SOURCE" "$target"
    echo "synced $rel"
  fi
done

if $CHECK && [ "$drift" -ne 0 ]; then
  echo
  echo "A vendored copy differs from ui-kit/src/design-system." >&2
  echo "Make the change in ui-kit/, then run ./scripts/sync-design-system.sh" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Colour containment.
#
# The system ships ONE theme. That is a decision, but it costs us the thing a
# second theme was doing for free: proving no component had hardcoded a colour.
# With one theme a leak is invisible until somebody writes theme number two and
# finds half the product will not reskin.
#
# So the proof becomes a gate. Every colour must live in theme/ — anywhere else
# is a leak, and this is the only check that will notice.
# ---------------------------------------------------------------------------
if $CHECK; then
  leaks=$(grep -rnE '#[0-9a-fA-F]{3,8}\b|rgba?\(' "$SOURCE" \
            --include='*.css' --include='*.jsx' --include='*.js' 2>/dev/null \
          | grep -v "$SOURCE/theme/" || true)
  if [ -n "$leaks" ]; then
    echo
    echo "Colour outside theme/ — the system is no longer reskinnable:" >&2
    echo "$leaks" | sed 's/^/  /' >&2
    echo "Move the value into theme/ and reference it as a --ds-* variable." >&2
    exit 1
  fi
  echo "ok     no colour outside theme/"
fi
