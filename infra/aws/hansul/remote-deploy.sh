#!/bin/bash
set -euo pipefail

release_id="${1:?release ID required}"
bucket="${2:?bucket required}"
region="${3:?region required}"
ai_ip="${4:?AI private IP required}"
restore_id="${5:-none}"
hansul_sha="${6:-none}"
keycloak_sha="${7:-none}"
if [[ ! "$release_id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] ||
   [[ ! "$bucket" =~ ^hansul-deploy-[0-9]{12}-[a-z0-9-]+$ ]] ||
   [[ ! "$ai_ip" =~ ^10\.45\.1\.[0-9]{1,3}$ ]]; then
    echo 'Invalid deployment arguments' >&2
    exit 1
fi
if [[ "$restore_id" != none ]] &&
   { [[ ! "$restore_id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] ||
     [[ ! "$hansul_sha" =~ ^[0-9a-f]{64}$ ]] ||
     [[ ! "$keycloak_sha" =~ ^[0-9a-f]{64}$ ]]; }; then
    echo 'Invalid restore arguments' >&2
    exit 1
fi
if [[ "$restore_id" == none && ( "$hansul_sha" != none || "$keycloak_sha" != none ) ]]; then
    echo 'Unexpected restore hashes without a snapshot' >&2
    exit 1
fi

release_dir="/opt/hansul/releases/$release_id"
install -d -m 755 "$release_dir" /srv/hansul

# Only an additional, unmounted EBS disk may be initialized. Never format root.
if ! mountpoint -q /srv/hansul; then
    root_source="$(findmnt -n -o SOURCE /)"
    root_disk="$(lsblk -no PKNAME "$root_source" | head -1)"
    mapfile -t candidates < <(lsblk -dnpo NAME,TYPE | awk '$2 == "disk" { print $1 }' | grep -v "/$root_disk$" || true)
    if (( ${#candidates[@]} != 1 )); then
        echo 'Expected exactly one detached application data disk' >&2
        exit 1
    fi
    data_disk="${candidates[0]}"
    if lsblk -nrpo MOUNTPOINT "$data_disk" | grep -q '[^[:space:]]'; then
        echo 'Data disk already mounted elsewhere' >&2
        exit 1
    fi
    if ! blkid "$data_disk" >/dev/null 2>&1; then
        mkfs.xfs -f "$data_disk" >/dev/null
    fi
    data_uuid="$(blkid -s UUID -o value "$data_disk")"
    if ! grep -q "UUID=$data_uuid " /etc/fstab; then
        printf 'UUID=%s /srv/hansul xfs defaults,nofail 0 2\n' "$data_uuid" >> /etc/fstab
    fi
    mount /srv/hansul
fi

install -d -m 700 /srv/hansul/secrets /srv/hansul/backups
# Phone-video chunks outlive the API container; the API runs as 10001 (Dockerfile).
install -d -m 700 -o 10001 -g 10001 /var/lib/hansul/video-uploads
aws s3 cp "s3://$bucket/releases/$release_id.tar.gz" /tmp/hansul-release.tar.gz --region "$region" --only-show-errors
tar -xzf /tmp/hansul-release.tar.gz -C "$release_dir"
rm -f /tmp/hansul-release.tar.gz

secret_dir=/srv/hansul/secrets
aws ssm get-parameter --name /hansul/app/cloudflare-crt --with-decryption --query Parameter.Value --output text --region "$region" > "$secret_dir/cloudflare.crt"
aws ssm get-parameter --name /hansul/app/cloudflare-key --with-decryption --query Parameter.Value --output text --region "$region" > "$secret_dir/cloudflare.key"
chmod 600 "$secret_dir/cloudflare.crt" "$secret_dir/cloudflare.key"
openssl x509 -in "$secret_dir/cloudflare.crt" -noout -checkhost hansul.example.com >/dev/null
db_password="$(aws ssm get-parameter --name /hansul/app/db-password --with-decryption --query Parameter.Value --output text --region "$region")"
admin_password="$(aws ssm get-parameter --name /hansul/app/kc-admin-password --with-decryption --query Parameter.Value --output text --region "$region")"
ai_token="$(aws ssm get-parameter --name /hansul/ai/token --with-decryption --query Parameter.Value --output text --region "$region")"
# Optional: video recipe analysis stays disabled (HTTP 503) until these parameters exist.
gemini_api_key="$(aws ssm get-parameter --name /hansul/app/gemini-api-key --with-decryption --query Parameter.Value --output text --region "$region" 2>/dev/null || true)"
# Without the YouTube key a link's length is unknown, so the 30-minute limit cannot stop long videos.
youtube_api_key="$(aws ssm get-parameter --name /hansul/app/youtube-api-key --with-decryption --query Parameter.Value --output text --region "$region" 2>/dev/null || true)"
# Phone videos (faces, voices, homes) go to Gemini only when this is "true": set it only for a key from a
# billing-enabled Google Cloud project. Unpaid-tier inputs may be used to improve Google's products and be read
# by human reviewers. YouTube links are public videos and work with any key.
gemini_paid_tier="$(aws ssm get-parameter --name /hansul/app/gemini-paid-tier --with-decryption --query Parameter.Value --output text --region "$region" 2>/dev/null || true)"
[[ "$gemini_paid_tier" == true ]] || gemini_paid_tier=false

write_env() {
    local version="$1"
    umask 077
    printf 'RELEASE_ID=%s\nDB_PASSWORD=%s\nKC_ADMIN_PASSWORD=%s\nAI_TOKEN=%s\nAI_PRIVATE_IP=%s\nGEMINI_API_KEY=%s\nYOUTUBE_API_KEY=%s\nGEMINI_PAID_TIER=%s\n' \
        "$version" "$db_password" "$admin_password" "$ai_token" "$ai_ip" "$gemini_api_key" "$youtube_api_key" "$gemini_paid_tier" > "$secret_dir/runtime.env"
}

previous=""
if [[ -L /opt/hansul/current ]]; then
    previous="$(readlink -f /opt/hansul/current)"
    if [[ "$previous" != /opt/hansul/releases/* ]]; then
        echo 'Unsafe current release target' >&2
        exit 1
    fi
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    docker exec hansul-postgres pg_dump -U hansul -d hansul -Fc > "/srv/hansul/backups/hansul-$stamp.dump"
    docker exec hansul-postgres pg_dump -U hansul -d keycloak -Fc > "/srv/hansul/backups/keycloak-$stamp.dump"
    aws s3 cp "/srv/hansul/backups/hansul-$stamp.dump" "s3://$bucket/backups/hansul-$stamp.dump" --region "$region" --only-show-errors
    aws s3 cp "/srv/hansul/backups/keycloak-$stamp.dump" "s3://$bucket/backups/keycloak-$stamp.dump" --region "$region" --only-show-errors
    aws s3api head-object --bucket "$bucket" --key "backups/hansul-$stamp.dump" --region "$region" >/dev/null
    aws s3api head-object --bucket "$bucket" --key "backups/keycloak-$stamp.dump" --region "$region" >/dev/null
    rm -f "/srv/hansul/backups/hansul-$stamp.dump" "/srv/hansul/backups/keycloak-$stamp.dump"
    echo 'Pre-deployment database backups uploaded'
fi

write_env "$release_id"
compose=(/usr/local/bin/docker-compose --env-file "$secret_dir/runtime.env" -f "$release_dir/compose.production.yaml")
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait postgres
keycloak_db_ready=false
for _ in $(seq 1 30); do
    if docker exec hansul-postgres psql -U hansul -d keycloak -Atc 'select 1' >/dev/null 2>&1; then
        keycloak_db_ready=true
        break
    fi
    sleep 1
done
if [[ "$keycloak_db_ready" != true ]]; then
    echo 'Keycloak database was not initialized' >&2
    exit 1
fi
restore_marker=/srv/hansul/restored-snapshot
if [[ "$restore_id" != none ]]; then
    if [[ -L /opt/hansul/current ]]; then
        echo 'Refusing to restore over an existing application release' >&2
        exit 1
    fi
    if [[ -f "$restore_marker" ]]; then
        if [[ "$(cat "$restore_marker")" != "$restore_id" ]]; then
            echo 'Data disk contains a different restored snapshot' >&2
            exit 1
        fi
        echo 'Previously restored snapshot verified'
    else
        for database in hansul keycloak; do
            table_count="$(docker exec hansul-postgres psql -U hansul -d "$database" -Atc \
                "select count(*) from pg_tables where schemaname='public'")"
            if [[ "$table_count" != 0 ]]; then
                echo "Refusing to restore over non-empty $database database" >&2
                exit 1
            fi
        done
        restore_dir="/tmp/hansul-restore-$restore_id"
        install -d -m 700 "$restore_dir"
        for database in hansul keycloak; do
            aws s3 cp "s3://$bucket/restores/$restore_id/$database.dump" \
                "$restore_dir/$database.dump" --region "$region" --only-show-errors
        done
        printf '%s  %s\n' "$hansul_sha" "$restore_dir/hansul.dump" | sha256sum -c - >/dev/null
        printf '%s  %s\n' "$keycloak_sha" "$restore_dir/keycloak.dump" | sha256sum -c - >/dev/null
        for database in hansul keycloak; do
            docker exec -i hansul-postgres pg_restore --list < "$restore_dir/$database.dump" >/dev/null
            docker exec -i hansul-postgres pg_restore --no-owner --no-privileges --exit-on-error \
                -U hansul -d "$database" < "$restore_dir/$database.dump"
        done
        printf '%s\n' "$restore_id" > "$restore_marker"
        rm -f "$restore_dir/hansul.dump" "$restore_dir/keycloak.dump"
        rmdir "$restore_dir"
        echo "RESTORED_SNAPSHOT=$restore_id"
    fi
elif [[ ! -L /opt/hansul/current && ! -f "$restore_marker" ]]; then
    # Never treat a partially restored or failed first release as a fresh DB.
    for database in hansul keycloak; do
        table_count="$(docker exec hansul-postgres psql -U hansul -d "$database" -Atc \
            "select count(*) from pg_tables where schemaname='public'")"
        if [[ "$table_count" != 0 ]]; then
            echo "Unmarked non-empty $database database; manual recovery is required" >&2
            exit 1
        fi
    done
fi
"${compose[@]}" build api
"${compose[@]}" up -d --no-build

ready=false
for _ in $(seq 1 60); do
    if curl -ksSf --max-time 5 --resolve hansul.example.com:443:127.0.0.1 \
        https://hansul.example.com/actuator/health | jq -e '.status == "UP"' >/dev/null 2>&1 && \
       curl -ksSf --max-time 5 --resolve hansul.example.com:443:127.0.0.1 \
        https://hansul.example.com/realms/hansul/.well-known/openid-configuration >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 5
done

if [[ "$ready" != true ]]; then
    if [[ -n "$previous" ]]; then
        previous_id="$(basename "$previous")"
        write_env "$previous_id"
        /usr/local/bin/docker-compose --env-file "$secret_dir/runtime.env" -f "$previous/compose.production.yaml" up -d --no-build
        echo 'New release failed health checks; previous release restarted' >&2
    else
        echo 'First release failed health checks; DNS was not changed' >&2
    fi
    exit 1
fi

ln -sfn "$release_dir" /opt/hansul/current
echo 'HANSUL_DEPLOY_HEALTHY'
