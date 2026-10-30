// Claude VPN Guard для Chrome.
//
// Браузер не знает, включён ли в системе VPN, поэтому расширение проверяет, из какой
// страны вы выходите в интернет (Cloudflare /cdn-cgi/trace — без обращения к Claude).
// Если страна не входит в список, где Claude доступен, все запросы к Claude/Anthropic
// отклоняются ещё до отправки (declarativeNetRequest), а вкладки с Claude переходят на
// страницу «Claude заблокирован». Пока результат проверки неизвестен — тоже блокируем.

import { SUPPORTED_COUNTRIES } from './countries.js';
import { DEFAULT_SETTINGS } from './settings.js';

const CLAUDE_DOMAINS = ['claude.ai', 'claude.com', 'anthropic.com', 'claudeusercontent.com', 'claude.site'];
const CLAUDE_URL_REGEX =
  '^https?://([^/]*\\.)?(claude\\.ai|claude\\.com|anthropic\\.com|claudeusercontent\\.com|claude\\.site)([:/?#].*)?$';
const CLAUDE_TAB_PATTERNS = CLAUDE_DOMAINS.flatMap((d) => [`*://${d}/*`, `*://*.${d}/*`]);


const RULE_REDIRECT = 1;
const RULE_BLOCK = 2;
const CHECK_TIMEOUT_MS = 5000;
const FRESH_MS = 8000;
const ALARM = 'check';

let inflight = null;

async function getSettings() {
  const { settings } = await chrome.storage.local.get('settings');
  return { ...DEFAULT_SETTINGS, ...(settings || {}) };
}

async function getStatus() {
  const { status } = await chrome.storage.session.get('status');
  return status || null;
}

function parseTrace(text) {
  const out = {};
  for (const line of text.split('\n')) {
    const i = line.indexOf('=');
    if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return out;
}

async function fetchTrace(url) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), CHECK_TIMEOUT_MS);
  try {
    const res = await fetch(url, { cache: 'no-store', credentials: 'omit', signal: ctrl.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const trace = parseTrace(await res.text());
    if (!/^[A-Z0-9]{2}$/.test(trace.loc || '')) throw new Error('нет поля loc');
    return { ip: trace.ip || '', country: trace.loc, via: url };
  } finally {
    clearTimeout(timer);
  }
}

function decide(country, settings) {
  if (settings.alsoBlock.includes(country)) return 'blocked_user';
  return SUPPORTED_COUNTRIES.has(country) ? 'ok' : 'unsupported';
}

async function runCheck() {
  const settings = await getSettings();
  let result = null;
  let lastError = '';
  for (const url of settings.checkUrls) {
    try {
      result = await fetchTrace(url);
      break;
    } catch (e) {
      lastError = `${url}: ${e.message || e}`;
    }
  }
  const status = result
    ? { ...result, verdict: decide(result.country, settings), checkedAt: Date.now() }
    : { verdict: 'error', error: lastError, checkedAt: Date.now() };
  await chrome.storage.session.set({ status });
  await applyBlocking(status.verdict !== 'ok');
  return status;
}

/** Проверка с защитой от лишних запросов: свежий результат берётся из кэша. */
async function check(force = false) {
  if (inflight) return inflight;
  if (!force) {
    const status = await getStatus();
    if (status && Date.now() - status.checkedAt < FRESH_MS) return status;
  }
  inflight = runCheck().finally(() => {
    inflight = null;
  });
  return inflight;
}

async function applyBlocking(blocked) {
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const isBlocked = existing.some((r) => r.id === RULE_REDIRECT);
  if (blocked !== isBlocked) {
    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: [RULE_REDIRECT, RULE_BLOCK],
      addRules: blocked ? blockingRules() : [],
    });
  }
  await setBadge(blocked);
  if (blocked) await redirectOpenTabs();
}

function blockingRules() {
  return [
    {
      // вкладки с Claude → страница «Claude заблокирован» (адрес сохраняется, чтобы вернуться)
      id: RULE_REDIRECT,
      priority: 2,
      action: {
        type: 'redirect',
        redirect: { regexSubstitution: `${chrome.runtime.getURL('blocked.html')}?url=\\0` },
      },
      condition: { regexFilter: CLAUDE_URL_REGEX, resourceTypes: ['main_frame'] },
    },
    {
      // все остальные запросы к Claude/Anthropic (API, WebSocket, картинки, iframe) — отклонить
      id: RULE_BLOCK,
      priority: 1,
      action: { type: 'block' },
      condition: { requestDomains: CLAUDE_DOMAINS, excludedResourceTypes: ['main_frame'] },
    },
  ];
}

async function redirectOpenTabs() {
  const tabs = await chrome.tabs.query({ url: CLAUDE_TAB_PATTERNS });
  const page = chrome.runtime.getURL('blocked.html');
  await Promise.all(
    tabs.map((tab) =>
      chrome.tabs.update(tab.id, { url: `${page}?url=${tab.url}` }).catch(() => {}),
    ),
  );
}

async function setBadge(blocked) {
  await chrome.action.setBadgeBackgroundColor({ color: blocked ? '#C62828' : '#2E7D32' });
  await chrome.action.setBadgeText({ text: blocked ? '✕' : 'VPN' });
}

// ---- события ----

async function startup() {
  // Пока не проверили — считаем, что VPN нет (fail-closed)
  await applyBlocking(true);
  await chrome.alarms.create(ALARM, { periodInMinutes: 0.5 });
  await check(true);
}

chrome.runtime.onInstalled.addListener(startup);
chrome.runtime.onStartup.addListener(startup);

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM) check(true);
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.settings) check(true);
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type === 'tick') {
    // открыта страница Claude — проверяем почаще, чем раз в 30 с
    check(false).then(sendResponse);
    return true;
  }
  if (msg?.type === 'check') {
    check(true).then(sendResponse);
    return true;
  }
  if (msg?.type === 'status') {
    getStatus().then(sendResponse);
    return true;
  }
  return false;
});
