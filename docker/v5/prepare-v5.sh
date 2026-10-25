#!/usr/bin/env bash
# Preparation only. Never pulls, starts, stops, imports, migrates or cuts over a service.
set +x
set -Eeuo pipefail
umask 077

readonly target=/opt/student-cloud-v5
readonly source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly volume_name=student-cloud-v5-mysql
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" == 0 ]] || fail 'Run as root. This script does not invoke sudo.'
for required in docker openssl install realpath find grep; do
    command -v "$required" >/dev/null 2>&1 || fail "Missing prerequisite: $required"
done
[[ ! -L "$target" ]] || fail 'V5 destination is a symlink; no files were changed.'
[[ "$(realpath -m -- "$target")" == "$target" ]] || fail 'V5 destination resolves outside its fixed deployment directory.'
[[ "$source_dir" != "$target" && "$source_dir" != "$target/"* ]] || fail 'Stage this bundle outside /opt/student-cloud-v5.'

volume_names="$(docker volume ls --format '{{.Name}}')" || fail 'Cannot inspect Docker volume names.'
if printf '%s\n' "$volume_names" | grep -Fxq -- "$volume_name"; then
    printf 'ERROR: %s already exists. Nothing was deleted or overwritten; inspect the existing deployment first.\n' "$volume_name" >&2
    exit 42
fi
unset volume_names
[[ ! -e "$target/.env" && ! -L "$target/.env" ]] || fail 'An existing V5 .env was found; refusing to overwrite it.'
if [[ -e "$target" ]]; then
    [[ -d "$target" ]] || fail 'V5 destination is not a directory.'
    [[ -z "$(find "$target" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail 'V5 destination is not empty; no overwrite or cleanup is permitted.'
fi
for relative in compose.yaml config/application.properties nginx/nginx.conf nginx/default.conf nginx/https.conf nginx/acme.conf nginx/routes.conf nginx/proxy.conf; do
    [[ -f "$source_dir/$relative" ]] || fail "Missing bundle file: $relative"
done

install -d -m 750 "$target"
install -d -m 750 -o 0 -g 10001 "$target/config"
install -d -m 755 "$target/nginx" "$target/certbot" "$target/certbot/www"
install -d -m 700 "$target/certbot/conf"
for relative in data data/home data/tmp data/logs data/storage data/workspaces data/course-materials; do
    install -d -m 750 -o 10001 -g 10001 "$target/$relative"
done
install -d -m 700 -o 10001 -g 10001 "$target/upload-staging"
install -m 644 "$source_dir/compose.yaml" "$target/compose.yaml"
install -m 640 -o 0 -g 10001 "$source_dir/config/application.properties" "$target/config/application.properties"
for relative in nginx.conf default.conf https.conf acme.conf routes.conf proxy.conf; do
    install -m 644 "$source_dir/nginx/$relative" "$target/nginx/$relative"
done

# Values exist only in shell memory and the designated 0600 server .env.
mysql_root_password="$(openssl rand -hex 24)"
mysql_password="$(openssl rand -hex 24)"
redis_password="$(openssl rand -hex 24)"
mail_code_secret="$(openssl rand -hex 32)"
[[ "$mysql_root_password" =~ ^[0-9a-f]{48}$ && "$mysql_password" =~ ^[0-9a-f]{48}$ && "$redis_password" =~ ^[0-9a-f]{48}$ && "$mail_code_secret" =~ ^[0-9a-f]{64}$ ]] || fail 'Credential generation failed.'
(
    set -o noclobber
    cat > "$target/.env" <<EOF
MYSQL_DATABASE=student_cloud
MYSQL_USER=student_cloud_user
MYSQL_ROOT_PASSWORD=$mysql_root_password
MYSQL_PASSWORD=$mysql_password
REDIS_PASSWORD=$redis_password
MAIL_CODE_SECRET=$mail_code_secret
PUBLIC_ORIGIN=http://127.0.0.1:18082
WEB_HTTP_BIND=127.0.0.1:18082
WEB_HTTPS_BIND=127.0.0.1:18444
STUDENT_CLOUD_COURSE_MAX_OWNER_BYTES=2147483648
STUDENT_CLOUD_COURSE_MAX_TOTAL_BYTES=6442450944
STUDENT_CLOUD_UPLOAD_STAGING_QUOTA_BYTES=4294967296
MAIL_ENABLED=false
MAIL_HOST=smtp.qq.com
MAIL_PORT=465
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=
MAIL_AUTH=true
MAIL_SSL=true
MAIL_STARTTLS=false
AI_ENABLED=false
DEEPSEEK_API_KEY=
EOF
)
chmod 600 "$target/.env"
unset mysql_root_password mysql_password redis_password mail_code_secret

printf 'Prepared %s with new private configuration. No containers or volumes were created.\n' "$target"
printf 'Next: pin approved image digests, validate Compose quietly, pull, and start V5 on loopback only.\n'
printf 'Preserve previous data. Pause after readiness/install-status for user initialization; formal cutover requires separate confirmation.\n'
