// Пока открыта страница Claude, раз в 10 секунд просим фон перепроверить подключение,
// чтобы при отключении VPN страница заблокировалась быстро.
(() => {
  const tick = () => chrome.runtime.sendMessage({ type: 'tick' }).catch(() => {});
  tick();
  setInterval(tick, 10_000);
})();
