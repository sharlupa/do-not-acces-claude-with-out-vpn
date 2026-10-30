#!/usr/bin/env bash
# Установка Claude VPN Guard (Linux + systemd)
set -euo pipefail
cd "$(dirname "$0")"

if [ "$(id -u)" -ne 0 ]; then
    exec sudo "$0" "$@"
fi

for bin in nft ip systemctl; do
    if ! command -v "$bin" >/dev/null 2>&1; then
        echo "Не найдено: $bin. Установите пакеты nftables и iproute2." >&2
        exit 1
    fi
done

install -Dm755 claude-vpn-guard /usr/local/bin/claude-vpn-guard
install -Dm644 claude-vpn-guard.service /etc/systemd/system/claude-vpn-guard.service
if [ -e /etc/claude-vpn-guard/config ]; then
    install -Dm644 config /etc/claude-vpn-guard/config.new
    echo "Существующий /etc/claude-vpn-guard/config сохранён (новый образец: config.new)"
else
    install -Dm644 config /etc/claude-vpn-guard/config
fi

systemctl daemon-reload
systemctl enable claude-vpn-guard.service
systemctl restart claude-vpn-guard.service
sleep 1
/usr/local/bin/claude-vpn-guard status
echo
echo "Готово. Без VPN Claude будет недоступен."
