-- LAPTOP ONLY. The mysql image conjures `appuser` out of MYSQL_USER/MYSQL_PASSWORD, so by
-- the time this runs the account already exists and only needs privileges on the schemas
-- 00-databases.sql just created.
--
-- The cloud twin is infra/sql/rds-users.sql, which must also CREATE the user and cannot
-- use ON *.* — an RDS master account may not grant privileges it does not itself hold.
GRANT ALL PRIVILEGES ON *.* TO 'appuser'@'%';
FLUSH PRIVILEGES;
