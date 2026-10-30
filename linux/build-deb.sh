#!/usr/bin/env bash
# Собирает .deb пакет (Debian / Ubuntu / Mint / Pop!_OS ...)
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${VERSION:-1.0.0}"
OUT="${1:-dist}"
PKG="$(mktemp -d)/claude-vpn-guard"
trap 'rm -rf "$(dirname "$PKG")"' EXIT

install -Dm755 claude-vpn-guard "$PKG/usr/bin/claude-vpn-guard"
sed 's#/usr/local/bin/#/usr/bin/#' claude-vpn-guard.service \
    | install -Dm644 /dev/stdin "$PKG/lib/systemd/system/claude-vpn-guard.service"
install -Dm644 config "$PKG/etc/claude-vpn-guard/config"

mkdir -p "$PKG/DEBIAN"
cat > "$PKG/DEBIAN/control" <<CTRL
Package: claude-vpn-guard
Version: $VERSION
Section: net
Priority: optional
Architecture: all
Depends: bash, nftables, iproute2, systemd
Recommends: libnotify-bin
Maintainer: sharlupa <noreply@github.com>
Description: Blocks Claude / Anthropic unless the traffic goes through a VPN
 Systemd service that rejects connections to Claude/Anthropic addresses
 leaving through non-VPN interfaces and stubs the domains in /etc/hosts
 while no VPN is active.
CTRL
echo "/etc/claude-vpn-guard/config" > "$PKG/DEBIAN/conffiles"
cat > "$PKG/DEBIAN/postinst" <<'SH'
#!/bin/sh
set -e
systemctl daemon-reload || true
systemctl enable claude-vpn-guard.service || true
systemctl restart claude-vpn-guard.service || true
SH
cat > "$PKG/DEBIAN/prerm" <<'SH'
#!/bin/sh
set -e
systemctl disable --now claude-vpn-guard.service || true
SH
cat > "$PKG/DEBIAN/postrm" <<'SH'
#!/bin/sh
systemctl daemon-reload || true
SH
chmod 755 "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/prerm" "$PKG/DEBIAN/postrm"

mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$PKG" "$OUT/claude-vpn-guard_${VERSION}_all.deb"
