#!/usr/bin/env bash
# Удаление Claude VPN Guard
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    exec sudo "$0" "$@"
fi

systemctl disable --now claude-vpn-guard.service 2>/dev/null || true
/usr/local/bin/claude-vpn-guard stop 2>/dev/null || true
rm -f /usr/local/bin/claude-vpn-guard /etc/systemd/system/claude-vpn-guard.service
systemctl daemon-reload
echo "Удалено. Настройки остались в /etc/claude-vpn-guard (удалите вручную, если не нужны)."
