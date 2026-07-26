#!/usr/bin/env bash
# Stop or start the neobank Fargate services — the cost lever, as a one-liner.
#
# Stopping scales every service to 0 tasks. On Fargate that is $0 compute while stopped
# (the ALB and RDS keep billing ~$2/day). Starting scales back to $START_COUNT (default 1).
# The stacks, images, database and ALB URLs are all untouched, so a start brings the same
# environment back at the same address — this is not a deploy, just a power switch.
#
#   ./services.sh status          # what's running right now (read-only)
#   ./services.sh stop            # stop dev AND prod
#   ./services.sh start           # start both
#   ./services.sh stop dev        # just one environment
#   ./services.sh start prod
#
# Uses your configured AWS credentials. Region defaults to ap-southeast-1 (override with
# AWS_REGION); start count defaults to 1 (override with START_COUNT).
set -euo pipefail

REGION="${AWS_REGION:-ap-southeast-1}"
START_COUNT="${START_COUNT:-1}"
ACTION="${1:-status}"
TARGET="${2:-all}"

case "$TARGET" in
  dev)  ENVS=(dev) ;;
  prod) ENVS=(prod) ;;
  all)  ENVS=(dev prod) ;;
  *) echo "usage: $0 <status|stop|start> [dev|prod|all]" >&2; exit 2 ;;
esac

platform_export() {  # <env> <ExportSuffix> -> value, or empty if absent
  aws cloudformation list-exports --region "$REGION" \
    --query "Exports[?Name=='neobank-$1-$2'].Value" --output text 2>/dev/null
}

for env in "${ENVS[@]}"; do
  # Services now live in per-repo stacks, not a monolith. The cluster comes from the platform
  # export; the services are enumerated off the cluster, so this covers any number of teams.
  cluster=$(platform_export "$env" ClusterArn)
  if [ -z "$cluster" ] || [ "$cluster" = "None" ]; then
    echo "== $env: platform stack 'neobank-$env-platform' not found — skipping"
    continue
  fi
  cluster="${cluster##*/}"   # ARN -> cluster name
  # Portable read (macOS bash 3.2 has no `mapfile`).
  services=()
  while IFS= read -r s; do [ -n "$s" ] && services+=("$s"); done < <(
    aws ecs list-services --region "$REGION" --cluster "$cluster" \
      --query 'serviceArns[]' --output text | tr '\t' '\n' | sed 's#.*/##')
  if [ "${#services[@]}" -eq 0 ]; then
    echo "== $env ($cluster): no services deployed yet"; continue
  fi

  case "$ACTION" in
    status)
      echo "== $env ($cluster)"
      for s in "${services[@]}"; do
        read -r d r <<< "$(aws ecs describe-services --region "$REGION" --cluster "$cluster" \
          --services "$s" --query 'services[0].[desiredCount,runningCount]' --output text)"
        printf '   %-14s desired=%s running=%s\n' "$s" "$d" "$r"
      done
      ;;
    stop|start)
      if [ "$ACTION" = stop ]; then count=0; gerund=stopping; past=stopped
      else count="$START_COUNT"; gerund=starting; past=started; fi
      echo "== $env: $gerund ${#services[@]} services -> desired=$count"
      for s in "${services[@]}"; do
        aws ecs update-service --region "$REGION" --cluster "$cluster" \
          --service "$s" --desired-count "$count" >/dev/null
        echo "   $s -> $count"
      done
      echo "   waiting for $env to settle..."
      # `wait services-stable` takes AT MOST 10 services per call. With eleven (orchestrator
      # + ten modules) a single call fails with "service names can have at most 10 items"
      # AFTER the scaling has already been applied — so the environment is fine and the
      # script reports failure, which is the most confusing pairing available. Batch it.
      batch=()
      for s in "${services[@]}"; do
        batch+=("$s")
        if [ "${#batch[@]}" -eq 10 ]; then
          aws ecs wait services-stable --region "$REGION" --cluster "$cluster" --services "${batch[@]}"
          batch=()
        fi
      done
      [ "${#batch[@]}" -gt 0 ] && \
        aws ecs wait services-stable --region "$REGION" --cluster "$cluster" --services "${batch[@]}"
      echo "   $env is $past"
      ;;
    *)
      echo "usage: $0 <status|stop|start> [dev|prod|all]" >&2; exit 2 ;;
  esac
done
