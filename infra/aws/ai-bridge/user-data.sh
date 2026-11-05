#!/bin/bash
set -euo pipefail
dnf install -y nodejs22 nodejs22-npm python3 awscli
systemctl enable --now amazon-ssm-agent
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
npm-22 install --global --prefix /usr/local @openai/codex@0.156.0
install -d -m 700 -o ec2-user -g ec2-user /home/ec2-user/.codex
