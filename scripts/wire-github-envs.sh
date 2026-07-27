#!/usr/bin/env bash
# Give all eleven repos the GitHub configuration their pipeline needs: a `dev` and a `prod`
# environment, the AWS role ARN for each, and the two repository variables that gate
# deploying at all.
#
#   ./scripts/wire-github-envs.sh            wire dev on all eleven (prod created, gated off)
#   ./scripts/wire-github-envs.sh --prod-on  additionally set PROD_DEPLOY_ENABLED=true
#   ./scripts/wire-github-envs.sh neo-04     just one repo
#
# Idempotent: every call is a PUT or an upsert, so re-running fixes drift rather than
# duplicating anything. Written down as a script and not done by hand because eleven repos ×
# six settings is 66 chances to mistype one, and the failure mode of a wrong role ARN is an
# OIDC error that names nothing useful.
#
# Needs: gh (authenticated, `repo` scope) and awscli (to read the role ARNs back from the
# roles stacks rather than trusting a list pasted in here).
set -euo pipefail

OWNER=Neueda-Learning
REGION="${AWS_REGION:-ap-southeast-1}"
REPOS=(neo-00 neo-01 neo-02 neo-03 neo-04 neo-05 neo-06 neo-07 neo-08 neo-09 neo-10)

PROD_ON=false
WANT=()
for arg in "$@"; do
  case "$arg" in
    --prod-on) PROD_ON=true ;;
    neo-[0-9][0-9]) WANT+=("$arg") ;;
    *) echo "usage: $0 [--prod-on] [neo-NN ...]" >&2; exit 2 ;;
  esac
done
[ "${#WANT[@]}" -gt 0 ] && REPOS=("${WANT[@]}")

# The reviewer who must approve a prod deploy. A human gate on production is the point of
# the prod environment; without a reviewer the environment is decoration.
REVIEWER_ID="$(gh api user --jq .id)"
echo "prod reviewer: $(gh api user --jq .login) (id $REVIEWER_ID)"

# Confirm a role actually exists before writing its ARN into a variable — a variable pointing
# at a role that was never created fails at deploy time with an unhelpful message, hours later.
role_arn() {  # <env> <repo>
  local name="neobank-$1-deploy-$2"
  aws iam get-role --role-name "$name" --query 'Role.Arn' --output text 2>/dev/null \
    || { echo "no such role: $name (deploy infra/roles.yaml for $1 first)" >&2; return 1; }
}

setvar() {  # <repo> <env> <name> <value>
  gh api -X POST "repos/$OWNER/$1/environments/$2/variables" \
    -f "name=$3" -f "value=$4" --silent 2>/dev/null \
  || gh api -X PATCH "repos/$OWNER/$1/environments/$2/variables/$3" \
    -f "name=$3" -f "value=$4" --silent
}

setrepovar() {  # <repo> <name> <value>
  gh api -X POST "repos/$OWNER/$1/actions/variables" \
    -f "name=$2" -f "value=$3" --silent 2>/dev/null \
  || gh api -X PATCH "repos/$OWNER/$1/actions/variables/$2" \
    -f "name=$2" -f "value=$3" --silent
}

for repo in "${REPOS[@]}"; do
  echo "== $repo"

  # --- dev: no protection. dev is meant to move on every merge to main.
  gh api -X PUT "repos/$OWNER/$repo/environments/dev" --silent
  setvar "$repo" dev AWS_REGION "$REGION"
  setvar "$repo" dev AWS_DEPLOY_ROLE_ARN "$(role_arn dev "$repo")"
  setrepovar "$repo" DEV_DEPLOY_ENABLED true
  echo "   dev  role + region set, DEV_DEPLOY_ENABLED=true"

  # --- prod: a required reviewer AND main-only. Two independent gates, because the
  #     workflow's own `github.ref == main` check lives in a file a team can edit; these
  #     two are enforced by GitHub, outside the repo's control.
  gh api -X PUT "repos/$OWNER/$repo/environments/prod" \
    --input - --silent <<JSON
{
  "reviewers": [{"type": "User", "id": $REVIEWER_ID}],
  "deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}
}
JSON
  gh api -X POST "repos/$OWNER/$repo/environments/prod/deployment-branch-policies" \
    -f name=main --silent 2>/dev/null || true   # already present on a re-run
  setvar "$repo" prod AWS_REGION "$REGION"
  setvar "$repo" prod AWS_DEPLOY_ROLE_ARN "$(role_arn prod "$repo")"

  if $PROD_ON; then
    setrepovar "$repo" PROD_DEPLOY_ENABLED true
    echo "   prod role + region set, reviewer + main-only, PROD_DEPLOY_ENABLED=true"
  else
    # Deliberately NOT set. Three gates must line up before anything reaches production:
    # this variable, a manual `promote` dispatch from main, and the reviewer's approval.
    echo "   prod role + region set, reviewer + main-only, PROD_DEPLOY_ENABLED unset (gated off)"
  fi

  # The ops repo alone drives the Power workflow, which scales EVERY service and therefore
  # cannot use a per-repo role scoped to one stack.
  if [ "$repo" = "neo-00" ]; then
    setrepovar "$repo" AWS_POWER_ROLE_ARN \
      "arn:aws:iam::$(aws sts get-caller-identity --query Account --output text):role/neobank-dev-power"
    echo "   AWS_POWER_ROLE_ARN set (ops repo)"
  fi
done

echo
echo "Done. dev deploys on every push to main; prod needs --prod-on plus a manual promote"
echo "plus an approval."
