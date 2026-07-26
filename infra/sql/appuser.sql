-- The shared application account, created once per environment by the platform bootstrap
-- (infra/db-init-appuser.sh), right after platform.yaml deploys. Schemas are NOT created
-- here — each service creates and grants its own on first deploy (infra/db-init-schema.sh).
--
-- __APP_PASSWORD__ is substituted for the CloudFormation-generated password INSIDE the
-- db-init container, from Secrets Manager, so the real password never reaches a shell or log.
--
-- Why this is RDS-only: RDS has no MYSQL_USER env var to conjure appuser, and its master
-- account may not GRANT ... ON *.*, so grants are per-schema and live with each service.
CREATE USER IF NOT EXISTS 'appuser'@'%' IDENTIFIED BY '__APP_PASSWORD__';
ALTER  USER 'appuser'@'%' IDENTIFIED BY '__APP_PASSWORD__';
FLUSH PRIVILEGES;
