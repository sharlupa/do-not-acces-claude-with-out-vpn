export const DEFAULT_SETTINGS = {
  // страны, из которых тоже не пускать, даже если Claude там доступен (например, ваша)
  alsoBlock: [],
  // где узнавать страну подключения (формат Cloudflare /cdn-cgi/trace: строки key=value, нужен loc=)
  checkUrls: [
    'https://www.cloudflare.com/cdn-cgi/trace',
    'https://1.1.1.1/cdn-cgi/trace',
    'https://one.one.one.one/cdn-cgi/trace',
  ],
};

/** Название страны по-русски: «Россия (RU)». */
export function countryName(code) {
  if (!code) return '—';
  try {
    const name = new Intl.DisplayNames(['ru'], { type: 'region' }).of(code);
    return name && name !== code ? `${name} (${code})` : code;
  } catch {
    return code;
  }
}

/** Человекочитаемое описание результата проверки. */
export function describe(status) {
  if (!status) return { ok: false, title: 'Проверяю подключение…', text: '' };
  switch (status.verdict) {
    case 'ok':
      return {
        ok: true,
        title: 'VPN работает — Claude доступен',
        text: `Вы выходите в интернет из страны: ${countryName(status.country)}, IP ${status.ip}.`,
      };
    case 'unsupported':
      return {
        ok: false,
        title: 'Claude заблокирован — включите VPN',
        text: `Сейчас вы выходите в интернет из страны: ${countryName(status.country)}, IP ${status.ip}. Claude там недоступен. Включите VPN (или смените сервер) — страница откроется сама.`,
      };
    case 'blocked_user':
      return {
        ok: false,
        title: 'Claude заблокирован — включите VPN',
        text: `Страна подключения ${countryName(status.country)} у вас в списке запрещённых (настройки расширения). Включите VPN — страница откроется сама.`,
      };
    default:
      return {
        ok: false,
        title: 'Не удалось проверить подключение',
        text: `Пока не ясно, включён ли VPN, Claude заблокирован. ${status.error || ''}`,
      };
  }
}
