import { describe } from './settings.js';

// исходный адрес передаётся как есть после «?url=» (правило redirect подставляет его целиком)
const marker = '?url=';
const href = location.href;
const target = href.includes(marker) ? href.slice(href.indexOf(marker) + marker.length) : '';
const isClaudeUrl = /^https?:\/\/([^/]*\.)?(claude\.ai|claude\.com|anthropic\.com|claudeusercontent\.com|claude\.site)([:/?#]|$)/i;

const title = document.getElementById('title');
const text = document.getElementById('text');
document.getElementById('url').textContent = target;

let leaving = false;

function show(status) {
  const d = describe(status);
  title.textContent = d.title;
  title.className = d.ok ? 'ok' : 'bad';
  text.textContent = d.text;
  if (d.ok && !leaving) {
    leaving = true;
    location.replace(isClaudeUrl.test(target) ? target : 'https://claude.ai/');
  }
}

async function check(force) {
  const status = await chrome.runtime.sendMessage({ type: force ? 'check' : 'status' });
  show(status);
}

document.getElementById('retry').addEventListener('click', () => check(true));
check(false).then(() => check(true));
setInterval(() => check(true), 3000);
