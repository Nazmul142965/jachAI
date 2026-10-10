// The floating AI Tutor: a small chat window behind a round button that can sit anywhere.
//
// What it is for. Anyone signed in can ask it about their studies, in English or Bangla, from
// any screen. It is not tied to an organization or to an exam result.
//
// What it is not allowed to be. It is OFF during an exam. Hiding it here is a courtesy; the
// server refuses to answer while the person has an attempt in progress, and that is the rule.
// This file mirrors that refusal so the person is not offered something that will be refused.
//
// Movable. Drag the button, or focus it and use the arrow keys (dragging alone would leave out
// anyone who cannot drag). It snaps to the nearer side edge and the place is remembered.
//
// Safe output. Replies are built from DOM nodes. Nothing the model says is ever put through
// innerHTML, so a reply cannot inject markup or script.
import { t, getLocale } from '/i18n.js';

const POSITION_KEY = 'jachaiAssistantPosition';
const TURNS_KEY = 'jachaiAssistantTurns';
const KEPT_TURNS = 20;
const SENT_TURNS = 6;
const MARGIN = 16;
const BUTTON = 56;
const STATUS_EVERY_MS = 60000;

const el = (tag, className = '', text = '') => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
};
const icon = (name) => {
  const node = el('span', 'ic');
  node.dataset.ic = name;
  node.setAttribute('aria-hidden', 'true');
  return node;
};
const store = {
  read(key, fallback) {
    try { return JSON.parse(sessionStorage.getItem(key)) ?? fallback; } catch { return fallback; }
  },
  write(key, value) { try { sessionStorage.setItem(key, JSON.stringify(value)); } catch { /* not remembered; still works */ } },
  drop(key) { try { sessionStorage.removeItem(key); } catch { /* nothing to drop */ } }
};
const place = {
  read() { try { return JSON.parse(localStorage.getItem(POSITION_KEY)); } catch { return null; } },
  write(value) { try { localStorage.setItem(POSITION_KEY, JSON.stringify(value)); } catch { /* not remembered */ } }
};

// ---------------------------------------------------------------------------- rich text
// A small, safe subset: paragraphs, bullet and numbered lists, **bold**, `code`, ``` fences.
function inline(tag, text, className) {
  const node = el(tag, className);
  let last = 0;
  for (const match of text.matchAll(/(\*\*[^*]+\*\*|`[^`]+`)/g)) {
    if (match.index > last) node.append(text.slice(last, match.index));
    const token = match[0];
    node.append(token.startsWith('**') ? el('strong', '', token.slice(2, -2)) : el('code', '', token.slice(1, -1)));
    last = match.index + token.length;
  }
  if (last < text.length) node.append(text.slice(last));
  return node;
}
function blocks(text) {
  const out = [];
  let list = null;
  for (const raw of text.split('\n')) {
    const line = raw.trimEnd();
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);
    const numbered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    if (bullet || numbered) {
      const kind = bullet ? 'ul' : 'ol';
      if (!list || list.kind !== kind) { list = { kind, node: el(kind) }; out.push(list.node); }
      list.node.append(inline('li', (bullet || numbered)[1]));
      continue;
    }
    list = null;
    if (!line.trim()) continue;
    const heading = /^#{1,3}\s+(.*)$/.exec(line);
    out.push(heading ? inline('p', heading[1], 'rich-heading') : inline('p', line));
  }
  return out;
}
export function renderRich(text) {
  const fragment = document.createDocumentFragment();
  text.split(/```[a-zA-Z0-9+#-]*\n?/).forEach((part, index) => {
    if (index % 2 === 1) {
      const pre = el('pre');
      pre.append(el('code', '', part.replace(/\n$/, '')));
      fragment.append(pre);
    } else {
      blocks(part).forEach((block) => fragment.append(block));
    }
  });
  return fragment;
}

// ---------------------------------------------------------------------------- the widget
export function createAssistant({ request }) {
  const state = {
    signedIn: false, exam: false, serverExam: false, open: false, busy: false, unseen: false,
    status: null, statusAt: 0, timer: null,
    turns: store.read(TURNS_KEY, []), lock: null,
    position: place.read() || { side: 'right', y: Math.max(MARGIN, innerHeight - BUTTON - 24) }
  };

  // ---- structure -----------------------------------------------------------
  const root = el('div', 'assistant');
  root.hidden = true;
  const fab = el('button', 'assistant-fab');
  fab.type = 'button';
  fab.setAttribute('aria-expanded', 'false');
  fab.setAttribute('aria-controls', 'assistant-window');
  fab.append(icon('sparkle'), el('span', 'assistant-fab-dot'));
  fab.querySelector('.assistant-fab-dot').hidden = true;

  const win = el('section', 'assistant-window');
  win.id = 'assistant-window';
  win.setAttribute('role', 'dialog');
  win.setAttribute('aria-modal', 'false');
  win.hidden = true;

  const header = el('header', 'assistant-head');
  const avatar = el('span', 'chat-avatar assistant-avatar'); avatar.append(icon('sparkle'));
  const titles = el('div', 'assistant-titles');
  const title = el('strong'); const subtitle = el('small');
  titles.append(title, subtitle);
  const freshButton = el('button', 'assistant-tool'); freshButton.type = 'button'; freshButton.append(icon('refresh'));
  const minimizeButton = el('button', 'assistant-tool'); minimizeButton.type = 'button'; minimizeButton.append(icon('minus'));
  header.append(avatar, titles, freshButton, minimizeButton);

  const thread = el('div', 'assistant-thread');
  thread.setAttribute('role', 'log');
  thread.setAttribute('aria-live', 'polite');
  const suggestions = el('div', 'assistant-suggestions');

  const form = el('form', 'assistant-composer');
  const input = el('textarea');
  input.rows = 1; input.maxLength = 1000; input.required = true;
  const send = el('button', 'assistant-send'); send.type = 'submit'; send.append(icon('send'));
  form.append(input, send);
  const foot = el('p', 'assistant-foot');

  win.append(header, thread, suggestions, form, foot);
  root.append(fab, win);
  document.body.append(root);

  // ---- text that depends on the language ----------------------------------
  function paintStatic() {
    title.textContent = t('AI Tutor');
    fab.setAttribute('aria-label', t('AI Tutor') + ' — ' + t('Drag to move, or use the arrow keys'));
    fab.title = t('AI Tutor');
    win.setAttribute('aria-label', t('AI Tutor'));
    freshButton.setAttribute('aria-label', t('New chat')); freshButton.title = t('New chat');
    minimizeButton.setAttribute('aria-label', t('Minimize')); minimizeButton.title = t('Minimize');
    input.placeholder = t('Ask anything about your studies…');
    input.setAttribute('aria-label', t('Your message'));
    send.setAttribute('aria-label', t('Send')); send.title = t('Send');
    paintSubtitle();
    paintSuggestions();
    paintFoot();
  }
  function paintSubtitle() {
    const left = state.status?.remainingToday;
    subtitle.textContent = state.lock === 'limit' ? t('Back tomorrow')
      : left == null ? t('Ask me anything')
        : t('{n} messages left today', { n: left });
  }
  function paintFoot() {
    foot.textContent = t('AI can make mistakes. Check anything important. Switched off during exams.');
  }
  const SUGGESTIONS = ['Explain Java interfaces in simple words', 'Make me a one-week study plan',
    'How should I prepare for a test?', 'How do I join an organization on JachAI?'];
  function paintSuggestions() {
    suggestions.replaceChildren();
    if (state.turns.length || state.lock) return;
    for (const text of SUGGESTIONS) {
      const chip = el('button', 'assistant-chip', t(text)); chip.type = 'button';
      chip.addEventListener('click', () => submit(t(text)));
      suggestions.append(chip);
    }
  }

  // ---- the thread ----------------------------------------------------------
  function bubble(kind, content, rich) {
    const node = el('div', `bubble ${kind === 'me' ? 'mine' : 'theirs'}${kind === 'error' ? ' error' : ''}`);
    const body = el('div', 'bubble-text');
    if (rich) { body.classList.add('rich'); body.append(renderRich(content)); } else { body.textContent = content; }
    node.append(body);
    return node;
  }
  function paintThread() {
    thread.replaceChildren();
    thread.append(bubble('ai', t('Hi! I am your AI Tutor. Ask me about anything you are studying, in English or বাংলা. I am switched off during exams.'), false));
    for (const turn of state.turns) thread.append(bubble(turn.role === 'LEARNER' ? 'me' : 'ai', turn.content, turn.role !== 'LEARNER'));
    scrollDown();
  }
  const scrollDown = () => { thread.scrollTop = thread.scrollHeight; };
  function remember() {
    state.turns = state.turns.slice(-KEPT_TURNS);
    store.write(TURNS_KEY, state.turns);
  }
  function typing() {
    const node = el('div', 'bubble theirs typing');
    node.setAttribute('aria-label', t('The AI Tutor is typing'));
    for (let i = 0; i < 3; i++) node.append(el('span', 'dot'));
    thread.append(node);
    scrollDown();
    return node;
  }

  // ---- locking -------------------------------------------------------------
  const LOCKS = {
    off: 'The AI Tutor is not switched on for this site yet.',
    limit: 'You have used your AI Tutor messages for today. They come back tomorrow.'
  };
  function applyLock() {
    const locked = Boolean(state.lock);
    input.disabled = locked || state.busy;
    send.disabled = locked || state.busy;
    form.classList.toggle('locked', locked);
    if (locked) {
      input.placeholder = t(LOCKS[state.lock] || LOCKS.off);
    } else {
      input.placeholder = t('Ask anything about your studies…');
    }
    paintSubtitle();
    paintSuggestions();
  }

  // ---- showing and hiding --------------------------------------------------
  const examOn = () => state.exam || state.serverExam;
  function syncVisibility() {
    const visible = state.signedIn && !examOn();
    root.hidden = !visible;
    if (!visible) { setOpen(false, { focus: false }); }
    if (visible) { applyPosition(); placeWindow(); }
  }
  function setOpen(next, { focus = true } = {}) {
    state.open = next && !root.hidden;
    win.hidden = !state.open;
    fab.setAttribute('aria-expanded', String(state.open));
    root.classList.toggle('open', state.open);
    if (state.open) {
      state.unseen = false;
      fab.querySelector('.assistant-fab-dot').hidden = true;
      paintThread();
      placeWindow();
      refreshStatus({ force: false });
      if (focus) input.focus({ preventScroll: true });
    } else if (focus) {
      fab.focus({ preventScroll: true });
    }
  }

  // ---- position: drag, arrow keys, snap -----------------------------------
  const clamp = (value, low, high) => Math.min(Math.max(value, low), high);
  function applyPosition() {
    const x = state.position.side === 'left' ? MARGIN : innerWidth - BUTTON - MARGIN;
    const y = clamp(state.position.y, MARGIN, Math.max(MARGIN, innerHeight - BUTTON - MARGIN));
    root.style.left = `${x}px`; root.style.top = `${y}px`;
  }
  function placeWindow() {
    if (!state.open) return;
    if (innerWidth <= 600) { win.style.cssText = ''; win.classList.add('sheet'); return; }
    win.classList.remove('sheet');
    const rect = root.getBoundingClientRect();
    // Open toward the side with more room, and give the window only the height that room allows,
    // so it never runs off the screen or sits on top of the button that opened it.
    const roomAbove = rect.top - MARGIN - 12;
    const roomBelow = innerHeight - rect.bottom - MARGIN - 12;
    const above = roomAbove >= roomBelow;
    const width = Math.min(380, innerWidth - 2 * MARGIN);
    const height = Math.max(Math.min(560, above ? roomAbove : roomBelow), Math.min(320, innerHeight - 2 * MARGIN));
    const top = clamp(above ? rect.top - height - 12 : rect.bottom + 12, MARGIN, innerHeight - height - MARGIN);
    const left = clamp(state.position.side === 'left' ? rect.left : rect.right - width, MARGIN, innerWidth - width - MARGIN);
    win.style.width = `${width}px`; win.style.height = `${height}px`;
    win.style.top = `${top - rect.top}px`; win.style.left = `${left - rect.left}px`;
  }
  function setXY(x, y) {
    root.style.left = `${clamp(x, MARGIN, innerWidth - BUTTON - MARGIN)}px`;
    root.style.top = `${clamp(y, MARGIN, innerHeight - BUTTON - MARGIN)}px`;
  }
  function snap() {
    const rect = root.getBoundingClientRect();
    state.position = { side: rect.left + BUTTON / 2 < innerWidth / 2 ? 'left' : 'right', y: rect.top };
    place.write(state.position);
    applyPosition(); placeWindow();
  }
  let drag = null;
  let justDragged = false;
  fab.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    const rect = root.getBoundingClientRect();
    drag = { x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, moved: false };
    fab.setPointerCapture(event.pointerId);
  });
  fab.addEventListener('pointermove', (event) => {
    if (!drag) return;
    const dx = event.clientX - drag.x; const dy = event.clientY - drag.y;
    if (!drag.moved && Math.hypot(dx, dy) > 6) drag.moved = true;
    if (drag.moved) { root.classList.add('dragging'); setXY(drag.left + dx, drag.top + dy); placeWindow(); }
  });
  const endDrag = () => {
    if (!drag) return;
    const moved = drag.moved;
    drag = null;
    root.classList.remove('dragging');
    if (moved) { justDragged = true; setTimeout(() => { justDragged = false; }, 0); snap(); }
  };
  fab.addEventListener('pointerup', endDrag);
  fab.addEventListener('pointercancel', endDrag);
  fab.addEventListener('click', () => { if (!justDragged) setOpen(!state.open); });
  // Arrow keys are the way to move the button for anyone who cannot drag it. The snap to a side
  // edge waits until the keys stop: snapping after every press pulled the button straight back to
  // the edge it had just left, so it could never cross to the other side.
  let snapTimer = null;
  fab.addEventListener('keydown', (event) => {
    const step = { ArrowLeft: [-40, 0], ArrowRight: [40, 0], ArrowUp: [0, -40], ArrowDown: [0, 40] }[event.key];
    if (!step) return;
    event.preventDefault();
    const rect = root.getBoundingClientRect();
    setXY(rect.left + step[0], rect.top + step[1]);
    placeWindow();
    clearTimeout(snapTimer);
    snapTimer = setTimeout(snap, 500);
  });
  window.addEventListener('resize', () => { applyPosition(); placeWindow(); });

  // ---- talking -------------------------------------------------------------
  function explain(error) {
    switch (error.code) {
      case 'ASSISTANT_UNAVAILABLE_DURING_EXAM':
        state.serverExam = true; syncVisibility(); return null;
      case 'AI_NOT_CONFIGURED': case 'ASSISTANT_UNAVAILABLE':
        state.lock = 'off'; return LOCKS.off;
      case 'ASSISTANT_LIMIT_REACHED':
        state.lock = 'limit'; return LOCKS.limit;
      case 'ASSISTANT_PLATFORM_CAPACITY':
        return 'The AI Tutor is very busy today. Please try again later.';
      case 'RATE_LIMITED':
        return 'You are sending messages very fast. Wait a few seconds and try again.';
      default:
        return error.status && error.status < 500 && error.status !== 429 ? error.message
          : 'The AI Tutor could not answer just now. Please try again in a minute.';
    }
  }
  async function submit(text) {
    const message = (text ?? input.value).trim();
    if (!message || state.busy || state.lock || examOn()) return;
    state.busy = true;
    state.turns.push({ role: 'LEARNER', content: message });
    remember();
    input.value = ''; input.style.height = '';
    suggestions.replaceChildren();
    thread.append(bubble('me', message, false));
    applyLock();
    const wait = typing();
    try {
      const history = state.turns.slice(0, -1).slice(-SENT_TURNS)
        .map((turn) => ({ role: turn.role, content: turn.content.slice(0, 800) }));
      const answer = await request('/assistant/chat', { method: 'POST', body: { message, history } });
      wait.remove();
      state.turns.push({ role: 'TUTOR', content: answer.reply });
      remember();
      thread.append(bubble('ai', answer.reply, true));
      if (state.status) state.status.remainingToday = Math.max(0, state.status.remainingToday - 1);
      if (!state.open) { state.unseen = true; fab.querySelector('.assistant-fab-dot').hidden = false; }
    } catch (error) {
      wait.remove();
      const friendly = explain(error);
      if (friendly) {
        const failed = bubble('error', t(friendly), false);
        if (!state.lock) {
          const retry = el('button', 'assistant-retry', t('Try again')); retry.type = 'button';
          retry.addEventListener('click', () => {
            // The question stays in the thread; send it again without adding it twice.
            state.turns.pop(); remember(); failed.remove(); thread.lastElementChild?.remove(); submit(message);
          });
          failed.append(retry);
        }
        thread.append(failed);
      }
    } finally {
      state.busy = false;
      applyLock();
      scrollDown();
      if (state.open && !state.lock) input.focus({ preventScroll: true });
    }
  }
  form.addEventListener('submit', (event) => { event.preventDefault(); submit(); });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); submit(); }
    if (event.key === 'Escape') { event.stopPropagation(); setOpen(false); }
  });
  input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = `${Math.min(input.scrollHeight, 120)}px`; });
  win.addEventListener('keydown', (event) => { if (event.key === 'Escape') setOpen(false); });
  minimizeButton.addEventListener('click', () => setOpen(false));
  freshButton.addEventListener('click', () => {
    state.turns = []; store.drop(TURNS_KEY);
    state.lock = state.lock === 'limit' ? 'limit' : null;
    paintThread(); applyLock(); input.focus({ preventScroll: true });
  });

  // ---- what the server says ------------------------------------------------
  async function refreshStatus({ force = true } = {}) {
    if (!state.signedIn) return;
    if (!force && Date.now() - state.statusAt < 30000) return;
    try {
      const status = await request('/assistant/status');
      state.status = status; state.statusAt = Date.now();
      state.serverExam = Boolean(status.examInProgress);
      if (!status.configured) state.lock = 'off';
      else if (status.remainingToday <= 0) state.lock = 'limit';
      else if (state.lock === 'off' || state.lock === 'limit') state.lock = null;
      syncVisibility(); applyLock();
    } catch { /* the widget simply stays as it was; the next poll tries again */ }
  }
  function startPolling() {
    stopPolling();
    state.timer = setInterval(() => { if (document.visibilityState === 'visible') refreshStatus(); }, STATUS_EVERY_MS);
  }
  function stopPolling() { clearInterval(state.timer); state.timer = null; }
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refreshStatus(); });

  paintStatic();
  applyLock();

  return {
    setSignedIn(flag) {
      state.signedIn = Boolean(flag);
      if (state.signedIn) { syncVisibility(); refreshStatus(); startPolling(); } else { stopPolling(); syncVisibility(); }
    },
    /** An attempt is open on this page. Hide at once; the server is asked again when it ends. */
    setExam(flag) {
      state.exam = Boolean(flag);
      syncVisibility();
      if (!state.exam) refreshStatus();
    },
    /** Signing out forgets the conversation: it was the person's, not the next one's. */
    reset() {
      state.signedIn = false; state.exam = false; state.serverExam = false; state.status = null;
      state.turns = []; state.lock = null; state.busy = false;
      store.drop(TURNS_KEY); stopPolling(); syncVisibility(); paintThread(); applyLock();
    },
    relabel() { paintStatic(); if (state.open) paintThread(); applyLock(); }
  };
}
