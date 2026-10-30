import { DEFAULT_SETTINGS, countryName, describe } from './settings.js';
import { SUPPORTED_COUNTRIES } from './countries.js';

const alsoBlock = document.getElementById('alsoBlock');
const checkUrls = document.getElementById('checkUrls');
const saved = document.getElementById('saved');

function fill(settings) {
  alsoBlock.value = settings.alsoBlock.join(', ');
  checkUrls.value = settings.checkUrls.join('\n');
}

async function load() {
  const { settings } = await chrome.storage.local.get('settings');
  fill({ ...DEFAULT_SETTINGS, ...(settings || {}) });
  const status = await chrome.runtime.sendMessage({ type: 'check' });
  const d = describe(status);
  const el = document.getElementById('status');
  el.textContent = `${d.title}. ${d.text}`;
  el.className = d.ok ? 'ok' : 'bad';
}

async function save() {
  const codes = alsoBlock.value
    .split(/[\s,;]+/)
    .map((c) => c.trim().toUpperCase())
    .filter((c) => /^[A-Z]{2}$/.test(c));
  const urls = checkUrls.value
    .split('\n')
    .map((u) => u.trim())
    .filter((u) => /^https?:\/\//.test(u));
  if (!urls.length) {
    saved.textContent = 'Нужен хотя бы один адрес для проверки.';
    return;
  }
  // свои адреса проверки требуют разрешения на доступ к сайту
  const origins = [...new Set(urls.map((u) => `${new URL(u).origin}/*`))];
  const granted = await chrome.permissions.request({ origins }).catch(() => false);
  if (!granted && !(await chrome.permissions.contains({ origins }))) {
    saved.textContent = 'Без разрешения на доступ к этим адресам проверка работать не будет.';
    return;
  }
  await chrome.storage.local.set({ settings: { alsoBlock: codes, checkUrls: urls } });
  fill({ alsoBlock: codes, checkUrls: urls });
  saved.textContent = 'Сохранено.';
  setTimeout(load, 500);
}

document.getElementById('save').addEventListener('click', save);
document.getElementById('reset').addEventListener('click', async () => {
  await chrome.storage.local.remove('settings');
  fill(DEFAULT_SETTINGS);
  saved.textContent = 'Восстановлены настройки по умолчанию.';
});

document.getElementById('countries').textContent = [...SUPPORTED_COUNTRIES]
  .map(countryName)
  .sort((a, b) => a.localeCompare(b, 'ru'))
  .join(', ');

load();
