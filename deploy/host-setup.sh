#!/usr/bin/env bash
# One-time host preparation for Inkwell AI on mini-hp01 (ADR-0005). Needs sudo, so the
# OPERATOR runs it interactively once per host; deploy.sh never needs sudo afterwards.
#
#   scp deploy/host-setup.sh deploy/systemd/* deploy/backup.sh deploy/restore.sh \
#       smahoney@mini-hp01.taile0ffc4.ts.net:/tmp/
#   ssh -t smahoney@mini-hp01.taile0ffc4.ts.net 'bash /tmp/host-setup.sh'
#
# What it does: docker group, /srv/inkwell/{staging,prod}, systemd units (boot-time
# `compose up` only), the nightly backup timer + scripts (ADR-0011), the backup tools
# (zstd/age/rclone), and tailscale serve on 8444 (staging) and 8443 (prod).
# It never writes secrets: each env's .env is created from .env.example by the operator.
set -euo pipefail

OPERATOR="${SUDO_USER:-$USER}"

echo ">> docker group for ${OPERATOR}"
sudo usermod -aG docker "${OPERATOR}"

echo ">> /srv/inkwell/{staging,prod}"
sudo mkdir -p /srv/inkwell/staging /srv/inkwell/prod
sudo chown -R "${OPERATOR}:${OPERATOR}" /srv/inkwell
chmod 750 /srv/inkwell /srv/inkwell/staging /srv/inkwell/prod

echo ">> systemd units (boot-time compose up; deploys do not restart via systemd)"
for env in staging prod; do
  if [ -f "/tmp/inkwell-${env}.service" ]; then
    sudo cp "/tmp/inkwell-${env}.service" "/etc/systemd/system/inkwell-${env}.service"
  fi
done

echo ">> backup tooling (ADR-0011): zstd + age + rclone"
# The host may be Debian-family (apt), Arch-family (pacman, e.g. Omarchy) or Fedora (dnf).
if command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq zstd age rclone
elif command -v pacman >/dev/null 2>&1; then
  sudo pacman -Sy --noconfirm --needed zstd age rclone
elif command -v dnf >/dev/null 2>&1; then
  sudo dnf install -y -q zstd age rclone
else
  echo "!! no supported package manager found; install zstd, age and rclone by hand, then re-run" >&2
  exit 1
fi
for t in zstd age rclone; do command -v "$t" >/dev/null || { echo "!! $t still missing" >&2; exit 1; }; done

echo ">> backup/restore scripts -> /srv/inkwell/bin (refreshed by deploy.sh thereafter)"
sudo install -d -o "${OPERATOR}" -g "${OPERATOR}" -m 0755 /srv/inkwell/bin
for s in backup restore; do
  if [ -f "/tmp/inkwell-${s}.sh" ]; then
    sudo install -o "${OPERATOR}" -g "${OPERATOR}" -m 0755 "/tmp/inkwell-${s}.sh" "/srv/inkwell/bin/${s}.sh"
  fi
done

echo ">> nightly backup timer units (templated per env; run as the operator)"
if [ -f /tmp/inkwell-backup@.service ]; then
  sudo sed "s/OPERATOR_PLACEHOLDER/${OPERATOR}/" /tmp/inkwell-backup@.service \
    | sudo tee /etc/systemd/system/inkwell-backup@.service >/dev/null
fi
if [ -f /tmp/inkwell-backup@.timer ]; then
  sudo cp /tmp/inkwell-backup@.timer /etc/systemd/system/inkwell-backup@.timer
fi

sudo systemctl daemon-reload

echo ">> enable nightly backup for staging (03:30 UTC)"
if [ -f /etc/systemd/system/inkwell-backup@.timer ]; then
  sudo systemctl enable --now inkwell-backup@staging.timer
fi

echo ">> tailscale serve: staging :8444 -> 127.0.0.1:8001, prod :8443 -> 127.0.0.1:8000"
echo "   (443 belongs to another service on this host and is left alone)"
sudo tailscale serve --bg --https=8444 http://127.0.0.1:8001
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8000
tailscale serve status

cat <<MSG

Done. Next, as ${OPERATOR} (log out and back in so the docker group applies):
  1. Create /srv/inkwell/staging/.env from deploy/.env.example (mode 600) with real
     values for POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY,
     ANTHROPIC_API_KEY, and API_PORT=8001 (prod: API_PORT=8000).
  2. From the workstation: ./deploy/deploy.sh staging <tag-or-digest>
MSG
