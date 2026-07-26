-- The schema list, and NOTHING else. This one file is the source of truth for the LAPTOP:
-- MySQL's entrypoint runs it on the first start of an empty volume, and only then. (If you
-- had this stack up before a schema was added here, that schema does not exist for you —
-- `docker compose down -v` once, or create it by hand.)
--
-- In AWS it is NOT this file that creates a module's schema: each service's own first deploy
-- runs infra/db-init-schema.sh, which creates and grants exactly the one schema named in its
-- infra/env/<env>.params. That is deliberate — a team's schema arrives with a team's deploy,
-- not with an edit to a file in the orchestrator's repo.
--
-- Keep it portable: only CREATE DATABASE statements belong here. Users and grants differ per
-- environment and live in 01-local-grants.sql / infra/sql/rds-users.sql.
--
-- ELEVEN schemas, one per repo, named after it. Schema isolation is the rule: every service
-- migrates its OWN schema with Liquibase and never reads another's. They integrate over REST,
-- not through shared tables — so nothing here grants anybody access to anybody else's data.
CREATE DATABASE IF NOT EXISTS neo_00;
CREATE DATABASE IF NOT EXISTS neo_01;
CREATE DATABASE IF NOT EXISTS neo_02;
CREATE DATABASE IF NOT EXISTS neo_03;
CREATE DATABASE IF NOT EXISTS neo_04;
CREATE DATABASE IF NOT EXISTS neo_05;
CREATE DATABASE IF NOT EXISTS neo_06;
CREATE DATABASE IF NOT EXISTS neo_07;
CREATE DATABASE IF NOT EXISTS neo_08;
CREATE DATABASE IF NOT EXISTS neo_09;
CREATE DATABASE IF NOT EXISTS neo_10;
