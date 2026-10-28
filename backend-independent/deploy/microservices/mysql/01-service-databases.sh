#!/bin/sh
# Runs only during initialization of an empty MySQL data volume.
(
set -eu
require_hex_secret() {
  name=$1
  value=$2
  [ "${#value}" -ge 32 ] || { printf '%s\n' "$name must contain at least 32 hex characters." >&2; exit 1; }
  case "$value" in *[!0-9a-fA-F]*) printf '%s\n' "$name must be hex; run prepare-env.sh." >&2; exit 1;; esac
}
require_hex_secret SC_PLATFORM_DB_PASSWORD "${SC_PLATFORM_DB_PASSWORD:?}"
require_hex_secret SC_CONTENT_DB_PASSWORD "${SC_CONTENT_DB_PASSWORD:?}"
require_hex_secret SC_INTELLIGENCE_DB_PASSWORD "${SC_INTELLIGENCE_DB_PASSWORD:?}"
# Passwords are validated hex and never placed in process arguments or printed.
MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?}" mysql --protocol=socket --user=root <<SQL
CREATE DATABASE student_cloud_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE student_cloud_content CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE student_cloud_intelligence CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'sc_platform'@'%' IDENTIFIED BY '${SC_PLATFORM_DB_PASSWORD}';
CREATE USER 'sc_content'@'%' IDENTIFIED BY '${SC_CONTENT_DB_PASSWORD}';
CREATE USER 'sc_intelligence'@'%' IDENTIFIED BY '${SC_INTELLIGENCE_DB_PASSWORD}';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON student_cloud_platform.* TO 'sc_platform'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON student_cloud_content.* TO 'sc_content'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON student_cloud_intelligence.* TO 'sc_intelligence'@'%';
SQL
)
