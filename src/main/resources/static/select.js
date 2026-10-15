// A nicer drop-down for every <select> in the workspace.
//
// The native list cannot be styled, and it looks different in every browser. This builds a button and a list that
// match the rest of the interface, and keeps the real <select> in the page (hidden, but still there). The real
// select stays the source of truth: forms, validation, `.value`, `change` events and the code that fills it with
// options all keep working. Keyboard: Enter, Space or the arrows open it, the arrows move, Enter chooses,
// Escape closes, and typing a letter jumps to a matching option.

const enhanced = new WeakSet();
let openMenu = null;

const valueDescriptor = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
const indexDescriptor = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'selectedIndex');

function labelTextOf(select) {
  const label = select.closest('label') || (select.id && document.querySelector(`label[for="${select.id}"]`));
  if (!label) return '';
  let text = '';
  for (const node of label.childNodes) {
    if (node.nodeType === Node.TEXT_NODE) text += node.textContent;
    else if (node.nodeType === Node.ELEMENT_NODE && !node.querySelector?.('select, .dd') && !node.matches?.('select, .dd')) text += node.textContent;
  }
  return text.replace(/\s+/g, ' ').trim();
}

function enhance(select) {
  if (enhanced.has(select) || select.multiple || select.size > 1) return;
  if (select.getAttribute('aria-hidden') === 'true' || select.classList.contains('sr-only')) return;
  enhanced.add(select);

  const root = document.createElement('div');
  root.className = 'dd';
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'dd-button';
  button.setAttribute('aria-haspopup', 'listbox');
  button.setAttribute('aria-expanded', 'false');
  const text = document.createElement('span');
  text.className = 'dd-text';
  button.append(text);
  const list = document.createElement('ul');
  list.className = 'dd-list';
  list.setAttribute('role', 'listbox');
  list.hidden = true;
  list.tabIndex = -1;
  const listId = `dd-${Math.random().toString(36).slice(2, 9)}`;
  list.id = listId;
  button.setAttribute('aria-controls', listId);

  select.before(root);
  root.append(button, list, select);
  select.classList.add('dd-native');
  select.tabIndex = -1;
  select.setAttribute('aria-hidden', 'true');

  let active = -1;
  let typed = '';
  let typedAt = 0;

  const options = () => [...select.options];
  const accessibleName = () => select.getAttribute('aria-label') || labelTextOf(select) || select.title || '';

  function refresh() {
    const chosen = select.options[select.selectedIndex];
    const shown = chosen ? chosen.textContent.trim() : '';
    text.textContent = shown || ' ';
    root.classList.toggle('is-placeholder', !chosen || chosen.value === '');
    button.disabled = select.disabled;
    const name = accessibleName();
    button.setAttribute('aria-label', name ? `${name}: ${shown || '—'}` : (shown || 'Choose'));
    if (!list.hidden) build();
  }

  function build() {
    list.replaceChildren();
    options().forEach((option, index) => {
      const item = document.createElement('li');
      item.id = `${listId}-${index}`;
      item.setAttribute('role', 'option');
      item.className = 'dd-option';
      item.textContent = option.textContent.trim() || ' ';
      const selected = index === select.selectedIndex;
      item.setAttribute('aria-selected', String(selected));
      if (selected) item.classList.add('is-selected');
      if (option.disabled) item.setAttribute('aria-disabled', 'true');
      item.addEventListener('pointerdown', (event) => event.preventDefault());
      item.addEventListener('click', () => { if (!option.disabled) choose(index); });
      item.addEventListener('pointermove', () => highlight(index, false));
      list.append(item);
    });
    highlight(active, false);
  }

  function highlight(index, scroll = true) {
    active = index;
    [...list.children].forEach((item, i) => item.classList.toggle('is-active', i === index));
    const item = list.children[index];
    if (item) {
      button.setAttribute('aria-activedescendant', item.id);
      if (scroll) item.scrollIntoView({ block: 'nearest' });
    } else button.removeAttribute('aria-activedescendant');
  }

  function move(step) {
    const all = options();
    let next = active;
    for (let guard = 0; guard < all.length; guard += 1) {
      next = (next + step + all.length) % all.length;
      if (!all[next].disabled) break;
    }
    highlight(next);
  }

  function place() {
    root.classList.remove('dd-up');
    const rect = button.getBoundingClientRect();
    const room = window.innerHeight - rect.bottom;
    const need = Math.min(list.scrollHeight, 280) + 12;
    if (room < need && rect.top > room) root.classList.add('dd-up');
  }

  function open() {
    if (select.disabled || !list.hidden) return;
    if (openMenu && openMenu !== api) openMenu.close();
    openMenu = api;
    active = Math.max(0, select.selectedIndex);
    list.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    root.classList.add('is-open');
    build();
    place();
    highlight(active);
  }

  function close(focusButton = false) {
    if (list.hidden) return;
    list.hidden = true;
    button.setAttribute('aria-expanded', 'false');
    button.removeAttribute('aria-activedescendant');
    root.classList.remove('is-open', 'dd-up');
    if (openMenu === api) openMenu = null;
    if (focusButton) button.focus({ preventScroll: true });
  }

  function choose(index) {
    if (index !== select.selectedIndex) {
      indexDescriptor.set.call(select, index);
      refresh();
      select.dispatchEvent(new Event('input', { bubbles: true }));
      select.dispatchEvent(new Event('change', { bubbles: true }));
    }
    close(true);
  }

  button.addEventListener('click', () => (list.hidden ? open() : close(true)));
  button.addEventListener('keydown', (event) => {
    const isOpen = !list.hidden;
    switch (event.key) {
      case 'ArrowDown': case 'ArrowUp':
        event.preventDefault();
        if (!isOpen) open(); else move(event.key === 'ArrowDown' ? 1 : -1);
        break;
      case 'Home': if (isOpen) { event.preventDefault(); highlight(0); } break;
      case 'End': if (isOpen) { event.preventDefault(); highlight(options().length - 1); } break;
      case 'Enter': case ' ':
        if (isOpen) { event.preventDefault(); choose(active); }
        else if (event.key === ' ') { event.preventDefault(); open(); }
        break;
      case 'Escape': if (isOpen) { event.preventDefault(); event.stopPropagation(); close(true); } break;
      case 'Tab': close(); break;
      default:
        if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
          const now = Date.now();
          typed = now - typedAt > 700 ? event.key.toLowerCase() : typed + event.key.toLowerCase();
          typedAt = now;
          const hit = options().findIndex((option) => !option.disabled && option.textContent.trim().toLowerCase().startsWith(typed));
          if (hit >= 0) { if (list.hidden) { indexDescriptor.set.call(select, hit); refresh(); select.dispatchEvent(new Event('change', { bubbles: true })); } else highlight(hit); }
        }
    }
  });
  button.addEventListener('blur', () => { if (!list.hidden) setTimeout(() => { if (document.activeElement !== button) close(); }, 0); });

  // Code elsewhere sets `.value` and `.selectedIndex` directly. That fires no event, so watch for it.
  Object.defineProperty(select, 'value', {
    configurable: true, enumerable: true,
    get() { return valueDescriptor.get.call(select); },
    set(next) { valueDescriptor.set.call(select, next); refresh(); }
  });
  Object.defineProperty(select, 'selectedIndex', {
    configurable: true, enumerable: true,
    get() { return indexDescriptor.get.call(select); },
    set(next) { indexDescriptor.set.call(select, next); refresh(); }
  });
  // Options are replaced, translated or disabled by other code. Keep the button in step.
  new MutationObserver(refresh).observe(select, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['disabled', 'label'] });
  select.addEventListener('change', refresh);
  select.form?.addEventListener('reset', () => setTimeout(refresh, 0));
  // Selecting a <label> focuses its control; send that focus to the visible button.
  select.addEventListener('focus', () => button.focus({ preventScroll: true }));

  const api = { close };
  refresh();
}

function scan(root = document) {
  if (root.matches?.('select')) enhance(root);
  root.querySelectorAll?.('select').forEach(enhance);
}

document.addEventListener('pointerdown', (event) => {
  if (openMenu && !event.target.closest?.('.dd')) openMenu.close();
});
window.addEventListener('resize', () => openMenu?.close());
window.addEventListener('scroll', (event) => { if (openMenu && !event.target.closest?.('.dd-list')) openMenu.close(); }, true);

new MutationObserver((records) => {
  for (const record of records) for (const added of record.addedNodes) if (added.nodeType === Node.ELEMENT_NODE) scan(added);
}).observe(document.documentElement, { childList: true, subtree: true });

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => scan());
else scan();
