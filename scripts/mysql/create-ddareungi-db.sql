-- Creates the ddareungrider database and application account on an existing MySQL 8.x server.
-- Run as an administrator (root), after replacing CHANGE_ME_STRONG_PASSWORD with the value
-- you put in .env as MYSQL_PASSWORD:
--   mysql -u root -p < scripts/mysql/create-ddareungi-db.sql
-- Tables are NOT created here. The collector (bikeseoul_data) creates them with Flyway on first start.

CREATE DATABASE IF NOT EXISTS ddareungi
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 'localhost' covers socket/127.0.0.1 connections from host JVMs.
-- '%' is needed only if the backend/collector run in Docker or on another machine.
CREATE USER IF NOT EXISTS 'ddareungi'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';
CREATE USER IF NOT EXISTS 'ddareungi'@'%' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

-- Scoped to this schema only. Flyway needs DDL (CREATE/ALTER/INDEX/DROP/CREATE VIEW).
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE VIEW, SHOW VIEW, CREATE TEMPORARY TABLES, LOCK TABLES
  ON ddareungi.* TO 'ddareungi'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE VIEW, SHOW VIEW, CREATE TEMPORARY TABLES, LOCK TABLES
  ON ddareungi.* TO 'ddareungi'@'%';

FLUSH PRIVILEGES;
