#!/usr/bin/env bash
# Create the shared appuser on <env>'s RDS — ONCE, from a laptop, right after platform.yaml
# deploys. The generated password is injected into the db-init container as APP_PASSWORD from
# Secrets Manager and sed'd into the SQL inside the container, so it never touches this shell
# or any log. Idempotent (CREATE USER IF NOT EXISTS + ALTER).
#
#   ./infra/db-init-appuser.sh <env>
set -euo pipefail

ENV="${1:?usage: db-init-appuser.sh <env>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
exp() { aws cloudformation list-exports --query "Exports[?Name=='neobank-$ENV-$1'].Value" --output text; }
CLUSTER="$(exp ClusterArn)"; TASKDEF="$(exp DbInitTaskDef)"
SUBNETS="$(exp SubnetIds)"; SG="$(exp TaskSgId)"

SQL="$(cat "$HERE/sql/appuser.sql")"
# set -e so a failing sed/mysql fails the task (the container's default sh has no -e).
CMD="set -e
cat > /tmp/u.sql <<'EOSQL'
$SQL
EOSQL
sed -i \"s|__APP_PASSWORD__|\$APP_PASSWORD|g\" /tmp/u.sql
mysql -h \"\$DB_HOST\" -u \"\$MASTER_USER\" -p\"\$MASTER_PASSWORD\" < /tmp/u.sql
echo 'appuser is in place'"
# EntryPoint is already [sh, -c]: pass the script ALONE, not wrapped in another sh -c.
OVERRIDES="$(jq -nc --arg c "$CMD" '{containerOverrides:[{name:"db-init",command:[$c]}]}')"
NET="awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=ENABLED}"

echo "==> creating appuser on neobank-$ENV"
ARN="$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$TASKDEF" --launch-type FARGATE \
  --network-configuration "$NET" --overrides "$OVERRIDES" --query 'tasks[0].taskArn' --output text)"
aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$ARN"
CODE="$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$ARN" \
  --query 'tasks[0].containers[0].exitCode' --output text)"
echo "appuser init exit=$CODE"
test "$CODE" = "0"
