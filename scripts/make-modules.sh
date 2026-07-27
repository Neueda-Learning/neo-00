#!/usr/bin/env bash
# Generate the nine sibling module repos (neo-02 … neo-10) from neo-01, stamping each with
# its own identity from the table below.
#
#   ./scripts/make-modules.sh              regenerate neo-02 … neo-10 from neo-01
#   ./scripts/make-modules.sh --check      has the PLUMBING drifted?  (for CI, safe forever)
#   ./scripts/make-modules.sh --check-all  has ANYTHING drifted?      (build-out only)
#   ./scripts/make-modules.sh 04 07        just those two
#
# TWO CHECK MODES, because "drift" means two different things before and after handover.
# --check-all compares the whole tree, and is how the generator itself was verified while
# the repos were being built. It is useless afterwards: the moment a team writes a rule,
# their backend/src differs from the skeleton BY DESIGN, and a check that always fails gets
# switched off — taking the useful half with it. So CI runs --check, which compares only
# $PLUMBING: the deploy config, the pipeline and the ops scripts, none of which a team has
# any reason to touch.
#
# ═══════════════════════════════════════════════════════════════════════════════════════
#  ⚠️  ONCE THE REPOS ARE HANDED TO TEAMS, THIS SCRIPT IS READ-ONLY. Use --check only.
#      A regenerate OVERWRITES a module's working tree, which after handover means
#      overwriting a team's week of work. It exists for the build-out, and afterwards as a
#      way to answer "has anyone edited the plumbing they were told not to touch?"
# ═══════════════════════════════════════════════════════════════════════════════════════
#
# WHY A GENERATOR AND NOT TEN HAND-EDITED REPOS: the ten modules are difficulty-equalised
# copies of one skeleton (CLAUDE.md §1, principle 1). Nine near-identical repos maintained by
# hand diverge within a day, and then no two teams have the same starting point — which is
# the one thing the whole design is trying to guarantee. So: FIX neo-01, NEVER A CLONE.
#
# WHAT IT DOES NOT COPY: .git. Each module is its own repository with its own history; only
# the working tree is generated. It also leaves target/, node_modules/ and dist/ behind.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT/neo-01"
STAMP="$ROOT/scripts/stamp.py"

# ── the module table ────────────────────────────────────────────────────────────────────
# NN | domain | display name | mocked dependencies (comma-separated, may be empty)
#
# Topics and domains are the capstone spec's (CLAUDE.md §3 / project-requirements). The
# ALB listener priority is NOT here because it is derivable — 10×NN — and a second place to
# write a number that must be unique is a second place to get it wrong.
#
# Only the four INTEGRATION modules declare mocked dependencies. The six rule/analytical
# modules mock nothing, and saying so on /info is deliverable #4 answered honestly rather
# than padded.
MODULES=(
  "02|policy|Customer Policy|"
  "03|kyc|Identity Verification (KYC)|id-verification-provider"
  "04|screening|Fraud & AML Screening|sanctions-list"
  "05|credit|Credit Decisioning|"
  "06|agreement|Agreement Management|e-sign-provider, document-store"
  "07|account|Card Account Setup|core-banking"
  "08|card|Card Issuing|card-personalisation-bureau"
  "09|support|Customer Support|"
  "10|analytics|Portfolio & Regulatory Analytics|"
)

# neo-01's own values — what every pattern below is replacing FROM.
T_NN=01
T_DOMAIN=verification
T_NAME="Application Verification"

# What a team must NOT diverge on. Deliberately short, and deliberately not "everything":
#   .github  the pipeline — a team that edits its own CI can deploy something untested
#   infra    deploy params: schema, ALB prefix, listener priority. Two modules claiming one
#            priority fails the second deploy, which then looks like the platform's fault
#   scripts  ops (reset-db.sh)
# NOT here, on purpose: backend/ and frontend/src (their work), db/changelog (their
# migrations), README/AGENTS (they document their own module), docker-compose.yml (an
# integration team legitimately adds a container for its mock provider), and
# frontend/src/design-system — which sync-design-system.sh --check already owns, and one
# gate per artefact is how a failure stays readable.
PLUMBING=(.github infra scripts)

CHECK=false
CHECK_ALL=false
WANT=()
for arg in "$@"; do
  case "$arg" in
    --check) CHECK=true ;;
    --check-all) CHECK=true; CHECK_ALL=true ;;
    [0-9][0-9]) WANT+=("$arg") ;;
    *) echo "usage: $0 [--check|--check-all] [NN ...]" >&2; exit 2 ;;
  esac
done

[ -d "$TEMPLATE" ] || { echo "no template at $TEMPLATE" >&2; exit 1; }
[ -f "$STAMP" ] || { echo "no stamper at $STAMP" >&2; exit 1; }

# Write the substitution table for one module.
#
# Every pattern is DELIBERATELY SPECIFIC. The naive rule — replace the word "verification"
# with "policy" — also rewrites prose ("Identity Verification", "verification failed"), so
# each domain pattern carries the syntax around it: `domain: x`, `Domain=x`, `SERVICE_DOMAIN:x`,
# `.value("x")`. Anything matching none of those was prose, and prose is left alone.
pairs_for() {  # <nn> <domain> <name> <mocked> <outfile>
  local nn="$1" domain="$2" name="$3" mocked="$4" out="$5"
  # "Fraud & AML Screening" is a legal display name and an ILLEGAL html title. A bare & that
  # is not the start of an entity is invalid markup — browsers forgive it, validators and
  # some parsers do not, and a generator should not emit it. The <title> line therefore gets
  # its own pattern with the name escaped; it is the longest pattern, so it is applied before
  # the plain name rule that would otherwise get there first.
  local name_html="${name//&/&amp;}"
  {
    printf '<title>neo-%s · %s</title>\t<title>neo-%s · %s</title>\n' \
      "$T_NN" "$T_NAME" "$nn" "$name_html"
    printf 'neo-%s\tneo-%s\n' "$T_NN" "$nn"
    printf 'neo%s\tneo%s\n' "$T_NN" "$nn"
    printf 'neo_%s\tneo_%s\n' "$T_NN" "$nn"
    printf 'Team %s\tTeam %s\n' "$T_NN" "$nn"
    printf '%s\t%s\n' "$T_NAME" "$name"
    # domain, four syntaxes
    printf 'domain: %s\tdomain: %s\n' "$T_DOMAIN" "$domain"
    printf 'Domain=%s\tDomain=%s\n' "$T_DOMAIN" "$domain"
    printf 'SERVICE_DOMAIN:%s\tSERVICE_DOMAIN:%s\n' "$T_DOMAIN" "$domain"
    printf 'SERVICE_DOMAIN:-%s\tSERVICE_DOMAIN:-%s\n' "$T_DOMAIN" "$domain"
    printf '.value("%s")\t.value("%s")\n' "$T_DOMAIN" "$domain"
    # the mocked-dependency register, in the three places it is configured
    printf 'MockedDependencies=\tMockedDependencies=%s\n' "$mocked"
    printf 'MOCKED_DEPENDENCIES:-}\tMOCKED_DEPENDENCIES:-%s}\n' "$mocked"
    printf 'MOCKED_DEPENDENCIES:}\tMOCKED_DEPENDENCIES:%s}\n' "$mocked"
    # unique ALB listener priority: 10, 20, … 100
    printf 'ListenerRulePriority=10\tListenerRulePriority=%s\n' "$((10#$nn * 10))"
  } > "$out"
}

generate() {  # <dest> <nn> <domain> <name> <mocked>
  local dest="$1" nn="$2" domain="$3" name="$4" mocked="$5"
  mkdir -p "$dest"
  # --delete, NOT `rm -rf $dest`. A regenerate must replace the CONTENT while leaving the
  # module's own `.git` alone: each of these directories is a separate git repository with
  # its own history and remote. `rm -rf` deleted nine repositories' git data in one command
  # here — recoverable only because everything had been pushed, which is not a property to
  # rely on twice.
  #
  # rsync does not delete files it was told to exclude (that needs --delete-excluded), so
  # `.git` is protected by the same line that keeps it from being copied.
  #
  # `--exclude .git` has no trailing slash on purpose: `.git/` matches only directories, and
  # a submodule's `.git` is a FILE pointing into the superproject.
  rsync -a --delete --exclude '.git' --exclude '.DS_Store' --exclude 'target' \
        --exclude 'node_modules' --exclude 'dist' "$TEMPLATE/" "$dest/"
  local pairs
  pairs="$(mktemp)"
  pairs_for "$nn" "$domain" "$name" "$mocked" "$pairs"
  python3 "$STAMP" "$dest" "$pairs"
  rm -f "$pairs"
}

drift=0
for spec in "${MODULES[@]}"; do
  IFS='|' read -r nn domain name mocked <<< "$spec"
  if [ "${#WANT[@]}" -gt 0 ]; then
    printf '%s\n' "${WANT[@]}" | grep -qx "$nn" || continue
  fi
  dest="$ROOT/neo-$nn"

  if $CHECK; then
    if [ ! -d "$dest" ]; then
      echo "MISSING neo-$nn"
      drift=1
      continue
    fi
    tmp="$(mktemp -d)"
    generate "$tmp/neo-$nn" "$nn" "$domain" "$name" "$mocked" >/dev/null
    # Build the compare list: the whole tree, or just the plumbing (see $PLUMBING).
    targets=()
    if $CHECK_ALL; then
      targets=(".")
    else
      for p in "${PLUMBING[@]}"; do
        [ -e "$tmp/neo-$nn/$p" ] && targets+=("$p")
      done
    fi
    out=""
    for p in "${targets[@]}"; do
      # -I '^Team=' : infra/env/*.params is checked because DbName, PathPrefix and
      # ListenerRulePriority MUST NOT diverge — two modules claiming one priority fails the
      # second deploy. But the same file also carries `Team=`, which is the team's own name
      # for itself and is *meant* to be edited (its own comment says "Whose module this is.
      # Shown in the UI's identity box."). The generator can only ever produce `Team NN`, so
      # any team that names itself would trip a gate about deploy config — and a gate that
      # fires on legitimate work is a gate someone switches off, taking the useful half with
      # it. Ignore that one line; everything else in the file is still compared.
      out+="$(diff -r -q -I '^Team=' --exclude '.git' --exclude 'target' --exclude 'node_modules' \
                --exclude 'dist' "$tmp/neo-$nn/$p" "$dest/$p" 2>&1 || true)"
    done
    if [ -z "$out" ]; then
      echo "ok      neo-$nn  ($name)"
    else
      echo "DRIFT   neo-$nn  ($name)"
      printf '%s\n' "$out" | sed 's/^/          /'
      drift=1
    fi
    rm -rf "$tmp"
  else
    echo "neo-$nn  ($name, domain=$domain)"
    generate "$dest" "$nn" "$domain" "$name" "$mocked"
  fi
done

if $CHECK; then
  if [ "$drift" -ne 0 ]; then
    echo
    echo "A module differs from neo-01 stamped with its identity." >&2
    echo "If that is a team's own work, it is expected. If it is the plumbing, fix it in" >&2
    echo "neo-01 and regenerate — never in the clone." >&2
    exit 1
  fi
  echo "no drift"
else
  echo
  echo "Done. Fix neo-01, never a clone; then re-run this script."
fi
