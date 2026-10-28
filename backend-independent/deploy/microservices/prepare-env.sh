#!/bin/sh
# Create local secrets once; never overwrite an existing deployment configuration.
set -eu
cd -- "$(dirname -- "$0")"
if [ -e .env ]; then
  printf '%s\n' '.env already exists; no configuration was overwritten.' >&2
  exit 1
fi
command -v openssl >/dev/null 2>&1 || { printf '%s\n' 'Install openssl before generating secrets.' >&2; exit 1; }
umask 077
environment_file=$(mktemp .env.new.XXXXXX)
trap 'rm -f -- "$environment_file"' EXIT HUP INT TERM
{
  printf '%s\n' 'SC_IMAGE_PREFIX=student-cloud' 'SC_IMAGE_TAG=0.1.0-SNAPSHOT'
  printf '%s\n' 'SC_ALLOWED_ORIGINS=http://127.0.0.1:18094,http://localhost:18094'
  printf '%s\n' 'SC_NODE_A_IP=172.16.0.10' 'SC_NODE_B_IP=172.16.0.11'
  printf '%s\n' 'SC_EDGE_SUBNET=172.30.50.0/28' 'SC_FRONTEND_EDGE_IP=172.30.50.2' 'SC_HTTPS_EDGE_PROXY_IP=172.30.50.1'
  for name in SC_INTERNAL_KEY MYSQL_ROOT_PASSWORD SC_PLATFORM_DB_PASSWORD SC_CONTENT_DB_PASSWORD SC_INTELLIGENCE_DB_PASSWORD SC_REDIS_PASSWORD; do
    printf '%s=%s\n' "$name" "$(openssl rand -hex 32)"
  done
  printf 'SC_STORAGE_MASTER_KEY=%s\n' "$(openssl rand -base64 32)"
  printf '%s\n' 'SC_STORAGE_ALLOWED_ENDPOINTS='
} > "$environment_file"
# A hard link gives create-if-absent semantics even if two operators run this together.
ln -- "$environment_file" .env
printf '%s\n' 'Created .env with private permissions. Edit origins and private ECS addresses; do not commit it.'
