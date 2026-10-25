#!/usr/bin/env bash
# Preparation only: no pull, up, stop, certificate copy, SQL import, or cutover.
set +x
set -Eeuo pipefail
umask 077

readonly target=/opt/student-cloud-v4
readonly source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly volume_name=student-cloud-v4-mysql

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" == 0 ]] || fail 'Run preparation as root; this script never invokes sudo.'
for required in docker openssl install realpath find grep; do
    command -v "$required" >/dev/null 2>&1 || fail "Required command missing: $required"
done

# Refuse symlinks and deployment from inside the destination. Stage this bundle elsewhere.
[[ ! -L "$target" ]] || fail 'Destination is a symlink; refusing to continue.'
[[ "$(realpath -m -- "$target")" == "$target" ]] || fail 'Destination resolves outside the intended V4 directory.'
[[ "$source_dir" != "$target" && "$source_dir" != "$target/"* ]] || fail 'Stage the template bundle outside /opt/student-cloud-v4.'

volume_names="$(docker volume ls --format '{{.Name}}')" || fail 'Cannot read Docker volumes.'
if printf '%s\n' "$volume_names" | grep -Fxq -- "$volume_name"; then
    printf 'ERROR: %s already exists. Stop for explicit user review; nothing was deleted.\n' "$volume_name" >&2
    exit 42
fi
unset volume_names

[[ ! -e "$target/.env" && ! -L "$target/.env" ]] || fail 'Existing .env detected; refusing to read or overwrite it.'
if [[ -e "$target" ]]; then
    [[ -d "$target" ]] || fail 'Destination exists and is not a directory.'
    [[ -z "$(find "$target" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail 'Destination is not empty; refusing to overwrite an earlier deployment.'
fi

for relative in compose.yaml config/application.properties nginx/default.conf nginx/https.conf nginx/acme.conf nginx/routes.conf nginx/proxy.conf; do
    [[ -f "$source_dir/$relative" ]] || fail "Missing template: $relative"
done

install -d -m 750 "$target"
install -d -m 750 -o 0 -g 10001 "$target/config"
install -d -m 755 "$target/nginx" "$target/certbot" "$target/certbot/www"
install -d -m 700 "$target/certbot/conf"
for relative in data data/home data/tmp data/logs data/storage data/workspaces; do
    install -d -m 750 -o 10001 -g 10001 "$target/$relative"
done
install -d -m 700 -o 10001 -g 10001 "$target/upload-staging"

install -m 644 "$source_dir/compose.yaml" "$target/compose.yaml"
install -m 640 -o 0 -g 10001 "$source_dir/config/application.properties" "$target/config/application.properties"
for relative in default.conf https.conf acme.conf routes.conf proxy.conf; do
    install -m 644 "$source_dir/nginx/$relative" "$target/nginx/$relative"
done

# Never print these values, enable xtrace, or export them to parent processes.
mysql_root_password="$(openssl rand -hex 24)"
mysql_password="$(openssl rand -hex 24)"
mail_code_secret="$(openssl rand -hex 32)"
[[ "$mysql_root_password" =~ ^[0-9a-f]{48}$ && "$mysql_password" =~ ^[0-9a-f]{48}$ && "$mail_code_secret" =~ ^[0-9a-f]{64}$ ]] || fail 'Secret generation failed.'

# noclobber enforces the no-overwrite rule again immediately before opening .env.
(
    set -o noclobber
    cat > "$target/.env" <<EOF
MYSQL_DATABASE=student_cloud
MYSQL_USER=student_cloud_user
MYSQL_ROOT_PASSWORD=$mysql_root_password
MYSQL_PASSWORD=$mysql_password
PUBLIC_ORIGIN=http://127.0.0.1:18081
WEB_HTTP_BIND=127.0.0.1:18081
WEB_HTTPS_BIND=127.0.0.1:18443
MAIL_ENABLED=false
MAIL_HOST=smtp.qq.com
MAIL_PORT=465
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=
MAIL_AUTH=true
MAIL_SSL=true
MAIL_STARTTLS=false
MAIL_CODE_SECRET=$mail_code_secret
AI_ENABLED=false
DEEPSEEK_API_KEY=
EOF
)
chmod 600 "$target/.env"
unset mysql_root_password mysql_password mail_code_secret

printf 'Prepared %s with a new private .env. No containers or volumes were created.\n' "$target"
printf 'Next: validate Compose quietly, pull the approved images, then start V4 on localhost only.\n'
printf 'Keep V3 running. Pause after the V4 install-status check for manual /install.\n'
