import { describe } from './settings.js';

function show(status) {
  const d = describe(status);
  const title = document.getElementById('title');
  title.textContent = d.title;
  title.className = d.ok ? 'ok' : 'bad';
  document.getElementById('text').textContent = d.text;
  document.getElementById('checked').textContent = status?.checkedAt
    ? `Проверено: ${new Date(status.checkedAt).toLocaleTimeString('ru')}`
    : '';
}

async function check(force) {
  show(await chrome.runtime.sendMessage({ type: force ? 'check' : 'status' }));
}

document.getElementById('retry').addEventListener('click', () => check(true));
document.getElementById('options').addEventListener('click', () => chrome.runtime.openOptionsPage());
check(false).then(() => check(true));
