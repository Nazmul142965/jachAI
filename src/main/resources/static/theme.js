/* JachAI theme control.
 *
 * Injects a three-state choice (follow the system, light, dark) into the page
 * header and remembers it. The attribute it sets, data-theme on <html>, is the
 * only thing theme.css reads; "system" means no attribute, so the OS media
 * query applies.
 *
 * The colours are applied before this file runs, by the small inline script in
 * each page head. This file only draws the control and handles the click, so a
 * slow or blocked module never leaves the page in the wrong theme.
 */

const STORAGE_KEY = 'jachaiTheme';
const MODES = ['system', 'light', 'dark'];

const ICONS = {
  // A monitor, for "whatever the operating system is set to".
  system: '<rect x="2.5" y="3.5" width="13" height="9" rx="1.5"/><path d="M6.5 15.5h5M9 12.5v3"/>',
  sun: '<circle cx="9" cy="9" r="3.2"/><path d="M9 1.5v1.8M9 14.7v1.8M16.5 9h-1.8M3.3 9H1.5M14.3 3.7l-1.3 1.3M5 13l-1.3 1.3M14.3 14.3L13 13M5 5L3.7 3.7"/>',
  moon: '<path d="M15.2 10.8A6.6 6.6 0 0 1 7.2 2.8a6.6 6.6 0 1 0 8 8Z"/>'
};

const LABELS = {
  system: 'Match the system theme',
  light: 'Use the light theme',
  dark: 'Use the dark theme'
};

function readMode() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return MODES.includes(stored) ? stored : 'system';
  } catch (_) {
    // Private windows and blocked site data both throw here. Falling back to
    // the system theme is the same thing a first-time visitor gets.
    return 'system';
  }
}

function applyMode(mode) {
  if (mode === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', mode);
  try {
    if (mode === 'system') localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, mode);
  } catch (_) { /* the choice still applies for this page view */ }
}

function build() {
  const host = document.querySelector('.topbar');
  if (!host || host.querySelector('.theme-toggle')) return;

  const group = document.createElement('div');
  group.className = 'theme-toggle';
  group.setAttribute('role', 'group');
  group.setAttribute('aria-label', 'Colour theme');

  const buttons = MODES.map((mode) => {
    const button = document.createElement('button');
    const icon = mode === 'light' ? 'sun' : mode === 'dark' ? 'moon' : 'system';
    button.type = 'button';
    button.title = LABELS[mode];
    button.setAttribute('aria-label', LABELS[mode]);
    button.innerHTML = `<svg viewBox="0 0 18 18" aria-hidden="true" focusable="false">${ICONS[icon]}</svg>`;
    button.addEventListener('click', () => select(mode));
    group.appendChild(button);
    return { mode, button };
  });

  function select(mode) {
    applyMode(mode);
    buttons.forEach((entry) => entry.button.setAttribute('aria-pressed', String(entry.mode === mode)));
  }

  select(readMode());

  // The sign-out button owns the right edge of the workspace header, so the
  // control goes before it rather than at the end of the row.
  const trailing = host.querySelector('.button');
  if (trailing) host.insertBefore(group, trailing);
  else host.appendChild(group);
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
else build();
