# Claude VPN Guard

Не даёт открыть Claude (приложение, claude.ai, anthropic.com, Claude Code) без включённого VPN.
Нужно, чтобы случайно не зайти в Claude напрямую.

| Платформа | Как блокирует |
|---|---|
| **Linux (systemd)** | Фаервол nftables отклоняет соединения к серверам Anthropic, если они идут не через VPN-интерфейс. Пока VPN выключен, домены Claude ещё и заглушаются в `/etc/hosts`, чтобы DNS-запросы не уходили провайдеру. |
| **Android 15–16** | Служба специальных возможностей: когда открыто приложение Claude или в браузере открыт claude.ai, а VPN выключен, — уводит на домашний экран и показывает экран «Claude заблокирован» с кнопкой «Открыть VPN». Когда VPN включится, Claude откроется снова сам. |

## Скачать

Готовые файлы лежат в **[Releases → latest](../../releases/tag/latest)** (собираются GitHub Actions при каждом push):

- `claude-vpn-guard-android.apk` — приложение для Android
- `claude-vpn-guard-*-any.pkg.tar.zst` — пакет для Arch Linux (и Manjaro, EndeavourOS, CachyOS)
- `claude-vpn-guard_*.deb` — пакет для Debian / Ubuntu / Mint
- `claude-vpn-guard-linux.tar.gz` — для любого другого Linux с systemd

---

## Linux

Нужны `nftables`, `iproute2` и systemd.

**Arch Linux** — готовый пакет из Releases:

```bash
sudo pacman -U claude-vpn-guard-*-any.pkg.tar.zst
```

или собрать из клона репозитория:

```bash
cd linux && makepkg -si
```

Служба включается сама при установке. `pacman -R claude-vpn-guard` снимает блокировку и удаляет всё.

**Debian / Ubuntu:**

```bash
sudo apt install ./claude-vpn-guard_*.deb
```

**Любой дистрибутив:**

```bash
tar xzf claude-vpn-guard-linux.tar.gz && sudo ./install.sh
```

(или из клона репозитория: `sudo linux/install.sh`)

Проверка:

```bash
claude-vpn-guard status
```

```
Маршрут к Claude (160.79.104.10): wlan0
VPN:        НЕ АКТИВЕН — Claude заблокирован
nftables:   правила загружены
/etc/hosts: заглушки доменов Claude включены
Служба:     active
```

### Как это устроено

- Служба `claude-vpn-guard.service` загружает таблицу `inet claude_vpn_guard`. В ней трафик к сетям
  Anthropic (`160.79.104.0/23`, `2607:6bc0::/48`) и к текущим IP доменов Claude разрешён **только**
  через интерфейсы VPN (`tun*`, `wg*`, `ppp*`, `tailscale*`, `nekoray*`, `sing-box*`, …).
  Через `eth0` / `wlan0` соединение сразу отклоняется.
- Это работает для любого браузера (включая DNS-over-HTTPS и QUIC), для Claude Code в терминале и для
  Docker-контейнеров.
- `nftables.service` в Arch при запуске делает `flush ruleset` — служба это замечает и за ~3 секунды
  возвращает свои правила.
- Split tunneling тоже учтён: если VPN включён, но Claude в него не заворачивается, Claude будет заблокирован.
- Раз в 3 секунды служба проверяет, через какой интерфейс идёт маршрут к Claude, и включает/выключает
  заглушки в `/etc/hosts`. Если правила кто-то сбросит (например, `nft flush ruleset`), они восстановятся.
- При смене состояния на рабочий стол приходит уведомление (нужен `notify-send`).
- Если процесс упадёт, правила остаются (блокировка не снимается). Снимаются они только при
  `systemctl stop`.

### Claude Code в терминале

Claude Code обращается к `api.anthropic.com`, `claude.ai` и `platform.claude.com` (вход), — все они
находятся в сети Anthropic `160.79.104.0/23`, поэтому без VPN он не подключится. Плюс в `/etc/hosts`
заглушаются `downloads.claude.ai` (обновления), `mcp-proxy.anthropic.com` и адреса телеметрии Datadog,
чтобы и они не уходили мимо VPN.

Проверено на настоящем Claude Code 2.1 в тестовой сети с «провайдером» (`wlan0`) и «VPN» (`wg0`):

| Режим | Что произошло |
|---|---|
| VPN выключен | все 31 попытка соединения отклонены фаерволом, до сервера не дошло ни одной |
| VPN выключен + заглушки hosts | `api.anthropic.com` → `::`, до сервера не дошло ни одной |
| VPN включён | соединения пошли через `wg0` и дошли до сервера |

Без VPN Claude Code не выдаёт ошибку сразу: он какое-то время переподключается, а потом пишет
`Unable to connect to API`. Если Claude Code настроен на Amazon Bedrock или Google Vertex, а не на API
Anthropic, эти адреса не блокируются.

### Настройка

Файл `/etc/claude-vpn-guard/config`. Если ваш VPN создаёт интерфейс с необычным именем (смотрите
`ip link` при включённом VPN), добавьте его в `VPN_IFACES` и перезапустите службу:

```bash
sudo systemctl restart claude-vpn-guard
```

Если ваш VPN работает как прокси без TUN-интерфейса (например, браузер настроен на SOCKS5-прокси), то
соединения через прокси пройдут: к Claude подключается сервер прокси, а не ваш компьютер. Если при этом
сайт не открывается, поставьте `HOSTS_BLOCK="no"` или включите в прокси-клиенте удалённый DNS (`socks5h`).

Временно отключить / включить:

```bash
sudo systemctl stop claude-vpn-guard
```

```bash
sudo systemctl start claude-vpn-guard
```

Удалить: `sudo apt remove claude-vpn-guard` или `sudo ./uninstall.sh`.

---

## Android

Сборка только под свежие Android: **15 и 16** (minSdk 35, targetSdk 36). На более старых версиях APK не
установится. Если нужен Android 14, поменяйте `minSdk = 35` на `34` в `android/app/build.gradle.kts`.

### Установка

1. Скачайте `claude-vpn-guard-android.apk` и установите (разрешите установку из браузера/файлового менеджера).
2. Откройте **Claude VPN Guard** → **Включить службу** → «Скачанные приложения» → **Claude VPN Guard** → включить.
3. Если переключатель серый с надписью «Ограниченная настройка» (так Android поступает со всеми
   приложениями не из Play Маркета): в приложении нажмите **О приложении** → **⋮** в правом верхнем углу →
   **Разрешить ограниченные настройки**, затем повторите шаг 2.
4. Нажмите **Выбрать VPN-приложение** и выберите свой VPN — тогда кнопка «Открыть VPN» на экране
   блокировки будет сразу запускать его.

### Что блокируется

- приложение Claude (`com.anthropic.claude`);
- `claude.ai`, `claude.com`, `anthropic.com` и их поддомены в браузерах: Chrome, Firefox, Яндекс, Samsung,
  Edge, Brave, Opera, Vivaldi, DuckDuckGo и любые другие установленные браузеры (определяются автоматически).
  Проверяется строка адреса, а не содержимое страниц, поэтому ссылка на claude.ai в поиске или статье
  блокировку не вызовет;
- если VPN отключится, пока Claude открыт, — блокировка сработает сразу.

### Ограничения

- Приложение смотрит, что открыто на экране, но не фильтрует сетевой трафик: фоновые запросы Claude
  (например, push-уведомления) оно не останавливает. Полностью перекрыть сеть без root на Android
  нельзя — системный VPN-слот может занимать только одно приложение, и он нужен вашему VPN.
- «VPN» для Android — любое приложение, создавшее VPN-подключение. Локальные «VPN» вроде AdGuard,
  RethinkDNS или Blokada тоже будут считаться включённым VPN.

### Подпись APK

Без настроенных секретов GitHub Actions подписывает APK временным debug-ключом, который меняется при каждой
сборке, — поэтому для обновления придётся удалить старую версию. Чтобы обновления ставились поверх,
создайте свой ключ:

```bash
keytool -genkeypair -v -keystore release.jks -alias guard -keyalg RSA -keysize 4096 -validity 36500
```

и добавьте в **Settings → Secrets and variables → Actions** репозитория секреты:
`KEYSTORE_BASE64` (вывод `base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`guard`),
`KEY_PASSWORD`.

### Сборка вручную

Нужны JDK 17+ и Android SDK с платформой 36:

```bash
cd android && ./gradlew assembleRelease
```

APK: `android/app/build/outputs/apk/release/app-release.apk`.
