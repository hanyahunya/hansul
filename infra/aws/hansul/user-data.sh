#!/bin/bash
set -euo pipefail
dnf install -y docker awscli jq xfsprogs
systemctl enable --now docker amazon-ssm-agent
curl -fL --retry 3 https://github.com/docker/compose/releases/download/v2.39.4/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
chmod 755 /usr/local/bin/docker-compose
install -d -m 755 /opt/hansul/releases /srv/hansul
