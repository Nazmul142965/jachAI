// The public pages (Academy, Talent, Pricing, How it works, How it's built, reset, verify, certificate) are
// plain pages, not the app. They load this so the language the person chose in the app, or here, applies
// to them too, and so the EN | বাংলা switch in the header works on every page.
import { applyLanguage, getLanguage, setLanguage } from '/i18n.js';

function paint() {
  for (const option of document.querySelectorAll('#lang-switch [data-lang]')) {
    const active = option.dataset.lang === getLanguage();
    option.classList.toggle('active', active);
    option.setAttribute('aria-pressed', String(active));
  }
}

document.addEventListener('jachai:language', () => { applyLanguage(); paint(); });
document.getElementById('lang-switch')?.addEventListener('click', (event) => {
  const option = event.target.closest('[data-lang]');
  if (option && option.dataset.lang !== getLanguage()) setLanguage(option.dataset.lang);
});
applyLanguage();
paint();
