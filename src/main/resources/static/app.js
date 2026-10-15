import { t, applyLanguage, setLanguage, getLanguage, getLocale } from '/i18n.js';
import { createAssistant } from '/assistant.js';
const $ = (selector) => document.querySelector(selector);
// Sent with a refresh so the server uses the session cookie. Only this site's own script can
// add a custom header to a same-origin request, which is what makes the cookie safe to honour.
let assistant = null; // the floating AI Tutor, created once the page is ready
const SESSION_MARKER = { 'X-JachAI-Session': '1' };
const SESSION_HINT = 'jachaiSessionHint';
const sessionHint = {
  get() { try { return localStorage.getItem(SESSION_HINT) === '1'; } catch { return false; } },
  set() { try { localStorage.setItem(SESSION_HINT, '1'); } catch { /* storage blocked: no restore, no harm */ } },
  clear() { try { localStorage.removeItem(SESSION_HINT); } catch { /* nothing to clear */ } }
};
const state = { accessToken: null, refreshToken: null, organizations: [], organization: null,
  platformAdmin: false, platformUserId: null, platformOrganizationId: null,
  platformLoadToken: 0, platformTargetUserId: null, platformUserLoadToken: 0,
  questions: [], campaigns: [], academyCourses: [], academyBatches: [],
  emailInvitationCampaignId: null, emailInvitationLoadToken: 0,
  activeAttempt: null, activeReviewAttemptId: null, reviewLoadToken: 0,
  tutorHistory: new Map(),
  selectedQuestionIds: new Set(),
  editingAssessment: null,
  editingQuestion: null,
  timer: null, activeConversationId: null, messageRefresh: null,
  messageRefreshBusy: false, messagePage: 0, messageLoadToken: 0,
  batchWorkspaceCourses: [], batchWorkspaceBatches: [], batchWorkspaceCourseId: null,
  batchWorkspaceBatchId: null, batchWorkspaceAssessmentId: null, batchWorkspaceAssessmentPage: 0,
  batchWorkspaceToken: 0, talentCampaigns: [], campaignWorkspaceId: null,
  campaignWorkspaceCandidateId: null, campaignWorkspaceCandidates: [],
  campaignWorkspaceToken: 0, campaignWorkspacePage: 0,
  detailAssignment: null, detailAssignmentId: null, assessmentDetailToken: 0,
  certificateDesign: null,
  versionsAssessment: null, versionsToken: 0,
  orderDetailId: null, orderDetailToken: 0,
  practiceQuestions: [], practiceAnswers: [], practiceRequestId: null, practiceRemaining: 0,
  homeAction: null, homeLoadToken: 0, profile: null,
  view: 'overview' };
const views = ['start', 'account', 'support', 'practice', 'overview', 'workspace-access', 'members', 'questions', 'assessments', 'academy', 'academy-batch', 'messages',
  'assign', 'review', 'campaigns', 'talent-campaign', 'attempts', 'assessment-detail', 'certificates', 'billing', 'platform', 'platform-tenants', 'platform-admins'];

// A screen is one product area. Several of them hold more than one view, shown
// as tabs within the screen, so that the sidebar lists six or seven places
// rather than thirteen. The views themselves are unchanged: a screen is a
// grouping and a route, not a new container.
const SCREENS = {
  start: { title: 'Home', subtitle: 'Your learning and work in one place', views: [['start', 'Home']] },
  account: { title: 'Profile & settings', subtitle: 'Your identity, sign-in and plan', views: [['account', 'Profile & settings']] },
  support: { title: 'Support', subtitle: 'Get help from the JachAI support team', views: [['support', 'Support']] },
  practice: { title: 'Practice', subtitle: 'Learn at your own pace', views: [['practice', 'Practice']] },
  overview: { title: 'Overview', subtitle: '', views: [['overview', 'Overview']] },
  'workspace-access': { title: 'My organizations', subtitle: 'Join or create another organization',
    views: [['workspace-access', 'My organizations']] },
  members: { title: 'Members', subtitle: 'People in this organization', views: [['members', 'Members']] },
  'assessment-studio': {
    title: 'Create tests',
    subtitle: 'Write questions, publish tests, review results',
    // One tab per stage of the lifecycle: author a question, publish an
    // assessment, assign it, review what came back.
    views: [['questions', 'Question bank'], ['assessments', 'Assessments'],
      ['assign', 'Assign'], ['review', 'Review & release']]
  },
  academy: {
    title: 'Courses',
    subtitle: 'Courses, batches and learner progress',
    views: [['academy', 'Courses & batches'], ['academy-batch', 'Batch workspace']]
  },
  talent: {
    title: 'Campaigns',
    subtitle: 'Invite candidates, compare them and decide',
    views: [['campaigns', 'All campaigns'], ['talent-campaign', 'Campaign workspace']]
  },
  messages: { title: 'Messages', subtitle: 'Talk with your instructor or learners', views: [['messages', 'Messages']] },
  'my-assessments': {
    title: 'My tests',
    subtitle: 'Your tests, attempts and results',
    views: [['attempts', 'Assignments'], ['assessment-detail', 'Assessment detail']]
  },
  certificates: { title: 'Certificates', subtitle: 'Your issued certificates', views: [['certificates', 'Certificates']] },
  billing: {
    title: 'Billing',
    subtitle: 'Plan, orders and usage',
    views: [['billing', 'Plan & usage']]
  },
  platform: {
    title: 'Platform',
    subtitle: 'Administration and support',
    views: [['platform', 'Overview'], ['platform-tenants', 'Organizations'], ['platform-admins', 'Administrators']]
  }
};
const screenOfView = (view) => Object.keys(SCREENS)
  .find((key) => SCREENS[key].views.some(([id]) => id === view));
// Screens the signed-in person may open, filled in when the workspace loads.
let availableScreens = [];
const invitationFromLink = new URLSearchParams(location.search).get('invitation');
const memberInvitationFromLink = new URLSearchParams(location.search).get('memberInvitation');
const billingReturn = new URLSearchParams(location.search).get('billing');
if (invitationFromLink) {
  sessionStorage.setItem('jachaiPendingInvitation', invitationFromLink);
  history.replaceState(null, '', location.pathname);
}
if (memberInvitationFromLink) {
  sessionStorage.setItem('jachaiPendingMemberInvitation', memberInvitationFromLink);
  history.replaceState(null, '', location.pathname);
}
// ---------------------------------------------------------------------------------------
// Coming back from a payment gateway.
//
// Checkout opens in a NEW tab so this signed-in tab keeps its session, because the access token
// lives only in this tab's memory. The gateway then sends the person to /?billing=... in that
// same new tab, which is a fresh page with no token, and used to greet them with a sign-in form.
// That is not a sign-out, but it looks exactly like one.
//
// So the returning tab first tells any signed-in tab of this site that the payment came back,
// and that tab does the work: it comes forward, shows Billing, re-reads it from the server, and
// says what happened. Only the OUTCOME crosses between tabs. No token, no order id, no
// secret, and nothing is written to storage: a BroadcastChannel keeps nothing.
//
// A redirect never confirms a payment. The outcome here only decides the wording; what Billing
// shows afterwards is whatever the server says.
const BILLING_OUTCOMES = {
  verified: 'You are back from the practice payment page. Press Check payment below to see the confirmed result.',
  canceled: 'You cancelled the payment. Nothing was charged. The order below shows its status.',
  failed: 'The payment did not go through. Nothing was charged. The order below shows its status.'
};
const normalizeBillingOutcome = (value) => (value === 'cancelled' ? 'canceled' : value);
const billingChannel = 'BroadcastChannel' in window ? new BroadcastChannel('jachai-billing') : null;
let billingHandoff = Promise.resolve(false);
if (billingReturn) {
  const outcome = normalizeBillingOutcome(billingReturn);
  // Drop the gateway's query string and name the screen, whatever happens next.
  history.replaceState(null, '', `${location.pathname}#/billing`);
  billingHandoff = new Promise((resolve) => {
    if (!billingChannel || !Object.hasOwn(BILLING_OUTCOMES, outcome)) { resolve(false); return; }
    document.body.dataset.restoring = 'true';
    document.body.dataset.restoringLabel = 'Returning to your payment…';
    const finish = (handedOver) => {
      clearTimeout(timer);
      billingChannel.removeEventListener('message', onMessage);
      delete document.body.dataset.restoring; delete document.body.dataset.restoringLabel;
      resolve(handedOver);
    };
    const onMessage = (event) => { if (event.data?.type === 'billing-ack') finish(true); };
    // Nobody answered: this is the only tab, or the original was closed. Carry on as a normal page
    // load, which restores the session if there is one and says what came back either way.
    const timer = setTimeout(() => finish(false), 1200);
    billingChannel.addEventListener('message', onMessage);
    billingChannel.postMessage({ type: 'billing-return', outcome });
  }).then((handedOver) => {
    if (handedOver) showBillingHandoffDone(outcome);
    else queueMicrotask(() => notice(`${BILLING_OUTCOMES[outcome] || `Sandbox checkout returned "${billingReturn}".`}`
      + ' Coming back to this page does not prove you paid. Sign in and open Billing to see the confirmed status.'));
    return handedOver;
  });
}
// The returning tab has done its job. Close it if the browser allows (it does for a tab a script
// opened), and say plainly where to go if it does not.
function showBillingHandoffDone(outcome) {
  const panel = document.createElement('main');
  panel.className = 'handoff';
  panel.setAttribute('role', 'status');
  const heading = document.createElement('h1');
  heading.textContent = outcome === 'verified' ? 'Payment page closed' : 'Back to JachAI';
  const text = document.createElement('p');
  text.textContent = 'Your original JachAI tab has been updated with the result. You can close this tab.';
  const close = document.createElement('button');
  close.type = 'button'; close.className = 'button primary'; close.textContent = 'Close this tab';
  close.addEventListener('click', () => window.close());
  panel.append(heading, text, close);
  document.body.append(panel);
  document.querySelector('#auth')?.classList.add('hidden');
  window.close();
}

function node(tag, text = '', className = '') {
  const element = document.createElement(tag);
  if (text) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function button(text, action, className = 'button secondary small') {
  const element = node('button', text, className);
  element.type = 'button';
  element.addEventListener('click', () => run(action));
  return element;
}
function notice(message, error = false) {
  const area = $('#notice');
  area.textContent = error ? `Error: ${message}` : message;
  area.classList.toggle('error', error);
  area.classList.remove('hidden');
  window.clearTimeout(notice.timeout);
  // Twelve seconds for an error and seven for a confirmation were long enough that a
  // banner from one action was still on screen during the next, which reads as the new
  // action having failed. An error stays longer than a confirmation because it is worth
  // reading; neither outlives the work that produced it. A click dismisses either.
  notice.timeout = window.setTimeout(() => area.classList.add('hidden'), error ? 6000 : 3500);
  if (!notice.dismissable) {
    notice.dismissable = true;
    area.addEventListener('click', () => {
      window.clearTimeout(notice.timeout);
      area.classList.add('hidden');
    });
    area.title = 'Click to dismiss';
  }
}
async function run(action) {
  try { await action(); } catch (error) { notice(error.message || 'Something went wrong.', true); }
}
function ownOrg(path) {
  if (!state.organization) throw new Error('Choose an organization first.');
  return `/organizations/${encodeURIComponent(state.organization.id)}${path}`;
}
const ATTEMPT_MARKER = 'jachaiActiveAttempt';
function rememberAttempt(assignmentId, attempt) {
  try {
    sessionStorage.setItem(ATTEMPT_MARKER, JSON.stringify({
      organizationId: state.organization?.id || null,
      assignmentId, attemptId: attempt.id, deadline: attempt.deadline
    }));
  } catch (_) { /* private-mode storage is optional */ }
}
function forgetAttempt() {
  try { sessionStorage.removeItem(ATTEMPT_MARKER); } catch (_) { /* ignore */ }
}
function pendingAttempt() {
  try {
    const raw = sessionStorage.getItem(ATTEMPT_MARKER);
    return raw ? JSON.parse(raw) : null;
  } catch (_) { return null; }
}
function focusPanel(panel) {
  panel.setAttribute('tabindex', '-1');
  panel.focus({ preventScroll: true });
}
const roleLabel = (role) => String(role).replaceAll('_', ' ').toLowerCase();
const roleTitle = (role) => roleLabel(role).replace(/\b\w/g, (letter) => letter.toUpperCase());
// What a person is called in the interface. "Owner / Org Admin" is a description of a
// permission, not a name anyone uses for themselves.
const ROLE_NAMES = { ORG_ADMIN: 'Admin', INSTRUCTOR: 'Instructor', LEARNER: 'Learner',
  RECRUITER: 'Recruiter', CANDIDATE: 'Candidate', REVIEWER: 'Reviewer' };
const myRoleName = (organization) => t(organization.owner ? 'Owner'
  : ROLE_NAMES[organization.myRole] || roleTitle(organization.myRole));
const when = (value) => (value ? new Date(value).toLocaleString() : 'not recorded');
let pendingRequests = 0;
// Free hosting puts the server to sleep when nobody uses it, and the first request after that can take up to a
// minute. After four seconds of waiting, say so, instead of leaving a blank page that looks broken.
let wakeTimer = null;
function updateWakeHint() {
  const waiting = pendingRequests > 0;
  if (waiting && !wakeTimer) {
    wakeTimer = window.setTimeout(() => {
      let hint = document.getElementById('wake-hint');
      if (!hint) {
        hint = document.createElement('div');
        hint.id = 'wake-hint'; hint.className = 'wake-hint'; hint.setAttribute('role', 'status');
        document.body.append(hint);
      }
      hint.textContent = t('Waking up the server. The free hosting plan sleeps when nobody is using it, so the first visit can take up to a minute.');
      hint.classList.remove('hidden');
    }, 4000);
  } else if (!waiting && wakeTimer) {
    window.clearTimeout(wakeTimer); wakeTimer = null;
    document.getElementById('wake-hint')?.classList.add('hidden');
  }
}
function updateBusyIndicator() {
  updateWakeHint();
  document.body.setAttribute('aria-busy', pendingRequests > 0 ? 'true' : 'false');
  const indicator = $('#busy');
  if (!indicator) return;
  indicator.classList.toggle('hidden', pendingRequests === 0);
  indicator.textContent = pendingRequests === 0 ? '' : 'Working…';
}
function fieldErrors(form, error) {
  for (const element of form.querySelectorAll('[aria-invalid]')) {
    element.removeAttribute('aria-invalid');
    const note = element.closest('label')?.querySelector('.field-error');
    if (note) note.remove();
  }
  const details = error?.details;
  if (!details || typeof details !== 'object') return false;
  let marked = false;
  for (const [field, message] of Object.entries(details)) {
    const input = form.elements?.[field];
    if (!input) continue;
    marked = true;
    input.setAttribute('aria-invalid', 'true');
    (input.closest('label') || input.parentElement || form).append(node('span', message, 'field-error'));
  }
  return marked;
}
function explainAiFailure(error) {
  switch (error?.code) {
    case 'PLAN_FEATURE_UNAVAILABLE':
      return 'AI is not part of this workspace plan. Write the question manually, or ask an administrator about the plan.';
    case 'AI_QUOTA_EXCEEDED':
      return 'The AI quota for today is used up. It resets daily and manual authoring still works.';
    case 'AI_QUOTA_UNAVAILABLE':
      return 'AI is unavailable because the quota service (Redis) cannot be reached. This deployment fails closed instead of skipping the quota.';
    case 'AI_UNAVAILABLE':
      return 'The AI provider could not complete the request. The operator can check the sanitized server error category; write the question manually meanwhile.';
    case 'AI_NOT_CONFIGURED':
      return 'No AI provider is enabled on this deployment. Write the question manually or ask the operator to configure one.';
    case 'AI_PROVIDER_RATE_LIMIT':
      return 'The provider is limiting requests. This is not your personal practice limit; try later.';
    case 'AI_PROVIDER_AUTH':
      return 'The AI provider key or its permissions need operator attention. Trying again will not fix the account configuration.';
    case 'AI_PROVIDER_BILLING':
      return 'The AI provider account needs billing attention from the operator. This is not your personal allowance.';
    case 'AI_PROVIDER_CONFIGURATION':
      return 'The selected AI model or request configuration needs operator attention.';
    case 'AI_PROVIDER_UNAVAILABLE':
      return 'The AI provider timed out or is unavailable. Try again later.';
    case 'AI_INVALID_OUTPUT':
      return 'The AI provider returned content JachAI could not use. Try again, or write the question manually.';
    case 'AI_QUESTION_TYPE_UNSUPPORTED':
      return 'AI drafting supports single-choice and multiple-choice questions only.';
    case 'TUTOR_NOT_READY':
      return 'Tutoring is available only after the result has been graded and released.';
    default:
      return null;
  }
}
async function request(path, { method = 'GET', body, auth = true, retry = true, optional404 = false,
  extraHeaders = {} } = {}) {
  const headers = { Accept: 'application/json', ...extraHeaders };
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  if (auth && state.accessToken) headers.Authorization = `Bearer ${state.accessToken}`;
  pendingRequests += 1; updateBusyIndicator();
  let response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method, headers, body: body === undefined ? undefined : (body instanceof FormData ? body : JSON.stringify(body)), cache: 'no-store'
    });

  } catch (_) {
    throw new Error('Could not reach JachAI. Check your connection and try again.');
  } finally {
    pendingRequests -= 1; updateBusyIndicator();
  }
  if (response.status === 401 && auth && retry && state.refreshToken) {
    try {
      const renewed = await request('/auth/refresh', {
        method: 'POST', body: { refreshToken: state.refreshToken }, auth: false, retry: false,
        extraHeaders: SESSION_MARKER
      });
      state.accessToken = renewed.accessToken;
      state.refreshToken = renewed.refreshToken;
      return request(path, { method, body, auth, retry: false, optional404, extraHeaders });
    } catch (_) { showSignedOut(); throw new Error('Your session expired. Please sign in again.'); }
  }
  if (response.status === 401 && auth) {
    // Without a refresh token the shell must not stay open with every action failing.
    showSignedOut();
    throw new Error('Your session is no longer valid. Please sign in again.');
  }
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : null; } catch (_) { data = null; }
  if (response.status === 404 && optional404) return null;
  if (!response.ok) {
    const failure = new Error(response.status === 403
      ? (data?.message ? `Not allowed: ${data.message}` : 'Your role does not allow this action.')
      : (data?.message || `Request failed (${response.status}).`));
    failure.status = response.status;
    failure.code = data?.code || null;
    failure.details = data?.details || null;
    throw failure;
  }
  return data;
}
// The signed-in person's name, for the header and the Home greeting. A demo sign-in
// reaches a screen before anything has asked for it, so it is fetched once, here, and
// shared by every caller that arrives while the request is still in flight.
let profileRequest = null;
async function ensureProfile() {
  if (state.displayName) return;
  profileRequest ??= request('/auth/me').then((me) => {
    state.displayName = me.displayName;
    updateAccountMenu(me);
  }).catch(() => { /* the greeting falls back to Welcome */ }).finally(() => { profileRequest = null; });
  await profileRequest;
}
function updateAccountMenu(profile) {
  const image = $('#header-avatar');
  const fallback = $('#header-avatar-fallback');
  const name = $('#header-account-name');
  if (image) {
    image.src = profile.avatarDataUrl || '';
    image.alt = `${profile.displayName} profile image`;
    image.classList.toggle('hidden', !profile.avatarDataUrl);
  }
  if (fallback) {
    fallback.classList.toggle('hidden', !!profile.avatarDataUrl);
    fallback.textContent = (profile.displayName || '?').trim().charAt(0).toUpperCase();
  }
  if (name) name.textContent = profile.displayName;
  state.displayName = profile.displayName;
}
function initAccountMenu() {
  $('#account-menu-toggle')?.addEventListener('click', () => {
    const panel = $('#account-menu-panel');
    if (!panel) return;
    const open = panel.classList.toggle('hidden');
    $('#account-menu-toggle').setAttribute('aria-expanded', String(!open));
  });
  $('#account-menu-profile')?.addEventListener('click', () => {
    $('#account-menu-panel')?.classList.add('hidden');
    showScreen('account');
  });
  $('#account-menu-support')?.addEventListener('click', () => {
    $('#account-menu-panel')?.classList.add('hidden');
    showScreen('support');
  });
  $('#account-menu-logout')?.addEventListener('click', () => $('#logout')?.click());
}
document.addEventListener('DOMContentLoaded', initAccountMenu);
// An attempt starting or ending. The AI Tutor goes off at once while one is open (the server
// refuses it too, which is the real rule), and a message badge is not a distraction to offer
// in the middle of an exam.
function setActiveAttempt(attempt) {
  state.activeAttempt = attempt;
  assistant?.setExam(Boolean(attempt));
  void refreshUnread();
}
const SIGNED_OUT_TITLE = document.title;
function showSignedOut() {
  assistant?.reset();
  sessionHint.clear();
  window.clearInterval(state.timer);
  stopMessageRefresh();
  state.accessToken = null; state.refreshToken = null; state.organization = null; state.organizations = [];
  state.platformAdmin = false; state.platformUserId = null; state.platformOrganizationId = null;
  state.platformTargetUserId = null; state.platformLoadToken++; state.platformUserLoadToken++;
  state.activeAttempt = null; state.activeConversationId = null; state.messagePage = 0;
  state.practiceQuestions = []; state.practiceAnswers = []; state.practiceRequestId = null;
  state.practiceRemaining = 0; state.practiceGenerationRemaining = 0; state.homeAction = null;
  state.homeLoadToken++;
  state.profile = null; state.displayName = null;
  state.tutorHistory.clear();
  state.activeReviewAttemptId = null;
  state.emailInvitationCampaignId = null;
  state.emailInvitationLoadToken++;
  state.selectedQuestionIds.clear();
  state.editingAssessment = null;
  state.editingQuestion = null;
  state.versionsAssessment = null;
  state.versionsToken++;
  state.orderDetailId = null;
  state.orderDetailToken++;
  state.reviewLoadToken++;
  state.messageLoadToken++;
  $('#auth').classList.remove('hidden'); $('#app').classList.add('hidden');
  $('#logout').classList.add('hidden');
  $('#account-menu').classList.add('hidden');
  $('#account-menu-panel')?.classList.add('hidden');
  $('#account-menu-toggle')?.setAttribute('aria-expanded', 'false');
  setInAppLinks(false);
  // The sign-in page should look like the sign-in page. Leaving the last screen's address behind
  // (for example #/billing) was wrong to read, and a different role signing in next could land on a
  // screen it may not open.
  history.replaceState(null, '', `${location.pathname}${location.search}#auth`);
  document.querySelector('.topbar .brand')?.setAttribute('href', '/');
  document.title = SIGNED_OUT_TITLE;
  loadDemoAccess();
}

// Inject SVG icons for password toggles and other UI elements
function injectIcons() {
  if (document.getElementById('jachai-icons')) return;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.id = 'jachai-icons';
  svg.style.display = 'none';
  svg.innerHTML = `
    <symbol id="icon-eye" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
      <circle cx="12" cy="12" r="3"/>
    </symbol>
    <symbol id="icon-eye-off" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
      <line x1="1" y1="1" x2="23" y2="23"/>
    </symbol>
  `;
  document.body.appendChild(svg);
}

// Password visibility toggle utility
function initPasswordToggles(root = document) {
  for (const field of root.querySelectorAll('.password-field')) {
    const input = field.querySelector('input[type="password"], input[type="text"]');
    const toggle = field.querySelector('.password-toggle');
    if (!input || !toggle) continue;
    if (toggle.dataset.bound) continue;
    toggle.dataset.bound = 'true';
    toggle.addEventListener('click', () => {
      const isPassword = input.type === 'password';
      input.type = isPassword ? 'text' : 'password';
      toggle.setAttribute('aria-label', isPassword ? 'Hide password' : 'Show password');
      toggle.setAttribute('aria-pressed', isPassword ? 'true' : 'false');
      toggle.querySelector('use')?.setAttribute('href', isPassword ? '#icon-eye-off' : '#icon-eye');
    });
  }
}

// Initialize icons and password toggles on DOM ready
document.addEventListener('DOMContentLoaded', () => {
  injectIcons();
  initPasswordToggles();
});

// Re-initialize password toggles after dynamic content loads
function reinitPasswordToggles() {
  injectIcons();
  initPasswordToggles();
}

// ---------------------------------------------------------------------------
// Leaving the page signs you out.
//
// The access token lives in a JS variable and nothing else, which is what keeps
// it out of localStorage and out of a cookie. The cost is that any full page
// load ends the session. Every link in the header and the footer was a plain
// href, so clicking the JachAI mark, or Plans, or How it works, silently
// signed a person out and dropped them back on the login screen.
//
// While signed in: the brand mark becomes the Overview, and the public pages
// open in a new tab so this one — and the session in it — survives. While
// signed out the links behave as ordinary links again.
// ---------------------------------------------------------------------------
function setInAppLinks(signedInNow) {
  document.querySelector('.topbar .topnav')?.classList.toggle('hidden', signedInNow);
  const headerContext = document.querySelector('.topbar-note');
  if (headerContext) headerContext.textContent = signedInNow
    ? 'Signed in · choose a workspace below' : '';
  const brand = document.querySelector('.topbar .brand');
  if (brand) {
    // The href itself changes, not just the click handler. A handler alone
    // leaves href="/" armed: if it has not bound yet, or throws, the browser
    // still performs a full document load and the in-memory token is gone.
    // Pointing the anchor at a hash means even an unhandled activation is a
    // same-document navigation, which the router picks up and which cannot
    // end the session. Keyboard Enter and middle-click get the same guarantee,
    // because they are the browser following the href rather than our handler.
    // Signed in, the logo stays where the person is: the href is the CURRENT screen, which is
    // still a same-document target. It used to throw them to Overview, which read as being
    // moved away from what they were doing. writeRoute keeps it in step as they navigate.
    brand.setAttribute('href', signedInNow ? (location.hash || '#/start') : '/');
    brand.setAttribute('aria-label', signedInNow ? t('JachAI — top of this page') : 'JachAI home');
    if (!brand.dataset.bound) {
      brand.dataset.bound = 'true';
      brand.addEventListener('click', (event) => {
        if (!state.accessToken) return;
        // Let a modifier-click open a tab as the browser normally would.
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.button > 0) return;
        event.preventDefault();
        // Stay on this screen and refresh it: the data is fetched again, like a reload would,
        // but without a document load, so the in-memory session survives.
        window.scrollTo({ top: 0 });
        if (state.view) run(() => showView(state.view));
        else $('#page-title')?.focus({ preventScroll: true });
      });
    }
  }
  for (const link of document.querySelectorAll('.topbar .topnav a, .workspace-foot a')) {
    const external = (link.getAttribute('href') || '').startsWith('/');
    if (!external) continue;
    if (signedInNow) {
      link.target = '_blank';
      link.rel = 'noopener';
      link.title = 'Opens in a new tab so you stay signed in here';
    } else {
      link.removeAttribute('target');
      link.removeAttribute('rel');
      link.removeAttribute('title');
    }
  }
}
async function signedIn(tokens) {
  state.accessToken = tokens.accessToken;
  state.refreshToken = tokens.refreshToken;
  sessionHint.set();
  assistant?.setSignedIn(true);
  $('#account-menu-panel')?.classList.add('hidden');
  $('#account-menu-toggle')?.setAttribute('aria-expanded', 'false');
  $('#auth').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#logout').classList.add('hidden');
  $('#account-menu').classList.remove('hidden');
  setInAppLinks(true);
  let invitationMessage = 'Signed in.';
  const pending = sessionStorage.getItem('jachaiPendingInvitation');
  if (pending) {
    try {
      const assignment = await request('/talent/email-invitations/accept',
        { method: 'POST', body: { token: pending } });
      sessionStorage.removeItem('jachaiPendingInvitation');
      state.organization = { id: assignment.organizationId };
      invitationMessage = 'Invitation accepted. Your assessment is under My assignments.';
    } catch (error) {
      invitationMessage = `Signed in, but the invitation was not accepted: ${error.message}`;
    }
  }
  const pendingMember = sessionStorage.getItem('jachaiPendingMemberInvitation');
  if (pendingMember) {
    try {
      const accepted = await request('/organization-invitations/accept',
        { method: 'POST', body: { token: pendingMember } });
      sessionStorage.removeItem('jachaiPendingMemberInvitation');
      state.organization = { id: accepted.organizationId };
      invitationMessage = `Joined ${accepted.organizationId}. Open the workspace to continue.`;
    } catch (error) {
      invitationMessage = `Signed in, but the workspace invitation was not accepted: ${error.message}`;
    }
  }
  await loadOrganizations();
  const inProgress = pendingAttempt();
  notice(inProgress
    ? `${invitationMessage} An assessment attempt is still in progress — open My assessments and choose Resume.`
    : invitationMessage);
}
let demoWorkspace = '';
async function loadOrganizations(preferredId = state.organization?.id) {
  const previous = preferredId;
  // Two independent questions: ask them together instead of one after the other, so a slow connection
  // waits for one round trip here, not two.
  const [platformAccess, organizations] = await Promise.all([request('/platform/me'), request('/organizations')]);
  state.platformAdmin = platformAccess.platformAdmin;
  state.platformUserId = platformAccess.userId;
  state.organizations = organizations;
  const select = $('#organization-select');
  select.replaceChildren();
  for (const organization of state.organizations) {
    const option = node('option', organization.name);
    option.value = organization.id;
    select.append(option);
  }
  // A demo role opens in the workspace its button names, not just the first one listed.
  const demoHome = demoWorkspace && state.organizations.find((item) => item.type === demoWorkspace);
  demoWorkspace = '';
  state.organization = state.organizations.find((item) => item.id === previous) || demoHome
    || state.organizations[0] || null;
  if (state.organization) select.value = state.organization.id;
  await setOrganization();
}
async function setOrganization() {
  state.homeLoadToken++;
  state.selectedQuestionIds.clear();
  state.editingAssessment = null;
  state.editingQuestion = null;
  hideAssessmentVersions();
  hideOrderDetail();
  $('#question-form')?.reset();
  updateQuestionEditor();
  $('#assessment-form')?.reset();
  updateAssessmentEditor();
  $('#recent-assignment')?.remove();
  state.tutorHistory.clear();
  setActiveAttempt(null);
  state.certificateDesign = null;
  $('#certificate-display-panel').classList.add('hidden');
  state.activeReviewAttemptId = null;
  state.reviewLoadToken++;
  state.emailInvitationCampaignId = null;
  state.emailInvitationLoadToken++;
  window.clearInterval(state.timer);
  stopMessageRefresh();
  state.activeConversationId = null;
  state.messagePage = 0;
  state.messageLoadToken++;
  for (const view of views) $(`#${view}-view`).classList.add('hidden');
  $('#attempt-panel').classList.add('hidden');
  $('#result-panel').classList.add('hidden');
  $('#attempt-review-detail').classList.add('hidden');
  $('#review-output').classList.add('hidden');
  $('#review-output').textContent = '';
  $('#feedback-form')?.reset();
  $('#feedback-output').textContent = '';
  $('#feedback-output').classList.add('hidden');
  $('#feedback-audit-list')?.replaceChildren();
  $('#feedback-audit-pagination')?.replaceChildren();
  $('#review-certificate-id').value = '';
  $('#certificate-audit-pagination').replaceChildren();
  $('#campaign-candidates-panel').classList.add('hidden');
  $('#email-invitations-panel').classList.add('hidden');
  $('#conversation-panel').classList.add('hidden');
  $('#start-view').classList.add('hidden');
  $('#organization-select').classList.toggle('hidden', !state.organization);
  $('.select-label[for="organization-select"]').classList.toggle('hidden', !state.organization);
  $('#sidebar-context').textContent = t(state.organization ? 'Your organization' : 'Your account');
  $('#sidebar-foot').textContent = state.organization
    ? "Your work is saved to your organization's workspace."
    : 'Practice is yours. Join an Academy or Talent workspace when you receive an invitation or code.';
  $('#workspace-role').textContent = state.organization
    ? `${t(state.organization.type === 'ACADEMY' ? 'Academy' : 'Talent')} · ${myRoleName(state.organization)}`
    : '';
  $('#workspace-role').classList.toggle('hidden', !state.organization);
  $('#page-role-context').textContent = state.organization
    ? `${t('Your role here:')} ${myRoleName(state.organization)}`
    : '';
  const headerContext = document.querySelector('.topbar-note');
  if (headerContext) headerContext.textContent = state.organization
    ? `${state.organization.name} · ${myRoleName(state.organization)}`
    : 'Your personal learning space';
  const nav = $('#workspace-nav'); nav.replaceChildren();
  if (!state.organization) {
    availableScreens = ['start', 'practice', 'account', 'support', ...(state.platformAdmin ? ['platform'] : [])];
    for (const [key, title] of [['start', 'Home'], ['practice', 'Practice'],
      ...(state.platformAdmin ? [['platform', 'Platform']] : [])]) {
      nav.append(navButton(key, title));
    }
    const route = readRoute();
    await showScreen(route && availableScreens.includes(route.screenKey) ? route.screenKey : 'start', route?.viewKey);
    return;
  }
  const role = state.organization.myRole;
  $('#plan-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  $('#tenant-support-audit-panel')?.classList.toggle('hidden', role !== 'ORG_ADMIN');
  $('#usage-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  $('#organization-settings-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  $('#billing-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  const academyFeedbackStaff = state.organization.type === 'ACADEMY'
    && ['ORG_ADMIN', 'INSTRUCTOR'].includes(role);
  $('#academy-feedback-panel').classList.toggle('hidden', !academyFeedbackStaff);
  if (academyFeedbackStaff) ensureFeedbackAuditPanel();
  $('#academy-staff-panel').classList.toggle('hidden', !['ORG_ADMIN', 'INSTRUCTOR'].includes(role));
  $('#certificate-design-panel').classList.toggle('hidden', !(state.organization.type === 'ACADEMY' && role === 'ORG_ADMIN'));
  $('#academy-learner-panel').classList.toggle('hidden', role !== 'LEARNER');
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  const academy = state.organization.type === 'ACADEMY';
  const talent = state.organization.type === 'TALENT';
  // The sidebar lists screens, not views. Grouping is by intent so Academy and
  // Talent read as related but distinct products.
  const groups = [];
  const group = (heading, items) => { if (items.length) groups.push([heading, items]); };
  group('Personal', [['start', 'Home'], ['practice', 'Practice'], ['workspace-access', 'My organizations']]);
  group('This organization', [
    ['overview', 'Overview'],
    ...(role === 'ORG_ADMIN' ? [['members', 'Members'], ['billing', 'Billing']] : [])
  ]);
  group('Tests', staff ? [['assessment-studio', 'Create tests']] : []);
  if (academy) {
    group('Learning', [
      ...(['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER'].includes(role)
        ? [['academy', role === 'LEARNER' ? 'My batches' : 'Courses']] : []),
      ...(['INSTRUCTOR', 'LEARNER', 'ORG_ADMIN'].includes(role) ? [['messages', 'Messages']] : []),
      ...(role === 'LEARNER' ? [['certificates', 'Certificates']] : [])
    ]);
  }
  if (talent) group('Hiring', staff ? [['talent', 'Campaigns']] : []);
  group('My work', ['LEARNER', 'CANDIDATE'].includes(role)
    ? [['my-assessments', 'My tests']] : []);
  group('Platform admin', state.platformAdmin ? [['platform', 'Platform']] : []);

  // A learner sees only their own batch list, never the staff batch workspace,
  // so that screen drops its second tab rather than showing a forbidden one.
  SCREENS.academy.title = role === 'LEARNER' ? 'My batches' : 'Courses';
  SCREENS.academy.views = ['ORG_ADMIN', 'INSTRUCTOR'].includes(role)
    ? [['academy', 'Courses & batches'], ['academy-batch', 'Batch workspace']]
    : [['academy', 'My batches']];

  availableScreens = groups.flatMap(([, items]) => items.map(([key]) => key));
  // The sections list exists now, so the control that opens it can appear.
  $('#nav-toggle').hidden = false;
  setNavCollapsed(true);
  for (const [heading, items] of groups) {
    if (heading) { const label = node('p', t(heading), 'nav-heading'); label.dataset.en = heading; nav.append(label); }
    for (const [key, title] of items) {
      nav.append(navButton(key, title));
    }
  }
  // A route in the URL is honoured only if this person's role actually has
  // that screen; otherwise the link silently becomes the overview rather than
  // opening a section the sidebar does not offer.
  const route = readRoute();
  if (route && availableScreens.includes(route.screenKey)) {
    await showScreen(route.screenKey, route.viewKey);
  } else {
    await showScreen(['LEARNER', 'CANDIDATE'].includes(role) ? 'start' : 'overview');
  }
  void refreshUnread();
}
// The account home is not tied to one tenant. Personal practice stays private;
// workspace assignments and staff work remain inside their own organization.
async function loadHome() {
  const loadToken = ++state.homeLoadToken;
  const accessToken = state.accessToken;
  const role = state.organization?.myRole;
  const participant = role === 'LEARNER' || role === 'CANDIDATE';
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  const academy = state.organization?.type === 'ACADEMY';
  const tutorParticipant = ['LEARNER', 'CANDIDATE'].includes(role);
  const organization = state.organization;
  $('#ai-tutor-entry').classList.toggle('hidden',
    !tutorParticipant);
  $('#home-messages-entry').classList.toggle('hidden',
    !(academy && ['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER'].includes(role)));
  $('#home-role').textContent = organization
    ? `${t(academy ? 'Academy' : 'Talent')} · ${organization.name} · ${myRoleName(organization)}`
    : t('Personal account');
  await ensureProfile();
  updateOnboardingIntent();
  const firstName = (state.displayName || '').trim().split(/\s+/)[0];
  $('#onboarding-title').textContent = firstName ? `${t('Hello')}, ${firstName}` : t('Welcome');
  $('#home-intro').textContent = t(!organization
    ? 'You can practice right away. A join code or an invitation connects you to an organization.'
    : participant
      ? 'Here is your work, and what to do next.'
      : 'Here is what needs attention in this organization.');
  $('#start-view .start-details').classList.toggle('hidden', !!organization);
  $('#start-join strong').textContent = t(organization ? 'Join another organization' : 'Join an organization');
  $('#start-organize strong').textContent = t(organization ? 'Start another organization' : 'Run tests');
  const requests = [
    request('/practice/usage'),
    request('/practice/answers/mine?page=0'),
    request('/practice/questions'),
    participant ? request(ownOrg('/assignments/mine?page=0')) : Promise.resolve(null),
    staff ? request(ownOrg('/attempts/review/pending?page=0')) : Promise.resolve(null),
    staff ? request(ownOrg(academy ? '/questions?status=DRAFT&page=0' : '/talent/campaigns?page=0'))
      : Promise.resolve(null)
  ];
  const [usageResult, answersResult, questionsResult, assignmentsResult, reviewResult, areaResult] = await Promise.allSettled(requests);
  if (loadToken !== state.homeLoadToken || accessToken !== state.accessToken
      || state.organization?.id !== organization?.id || state.view !== 'start') return;
  const usage = usageResult.status === 'fulfilled' ? usageResult.value : null;
  const answers = answersResult.status === 'fulfilled' ? rowsOf(answersResult.value) : [];
  const questions = questionsResult.status === 'fulfilled' ? questionsResult.value : [];
  const assignments = assignmentsResult.status === 'fulfilled' ? rowsOf(assignmentsResult.value) : [];
  const pendingReviews = reviewResult.status === 'fulfilled' ? totalOf(reviewResult.value, 0) : null;
  const areaCount = areaResult.status === 'fulfilled' ? totalOf(areaResult.value, 0) : null;
  const context = $('#home-context');
  context.replaceChildren();
  context.append(node('p', usage
    ? t('{q} AI questions and {a} answers left today', { q: usage.generationRemaining, a: usage.remaining })
    : t('Practice allowance unavailable right now'), 'home-fact'));
  context.append(node('p', state.organizations.length === 0
    ? t('You have not joined an organization yet')
    : t(state.organizations.length === 1 ? '1 organization' : '{n} organizations', { n: state.organizations.length }), 'home-fact'));
  if (answersResult.status === 'fulfilled') context.append(node('p', answers.length
    ? t(answers.length === 1 ? '1 recent practice answer' : '{n} recent practice answers', { n: answers.length })
    : t('No practice answers yet'), 'home-fact'));
  if (staff && pendingReviews !== null) context.append(node('p', t('{n} to review', { n: pendingReviews }), 'home-fact'));
  renderHomeWork({ organization, participant, staff, academy, assignmentsResult, reviewResult,
    areaResult, assignments, pendingReviews, areaCount, questionsResult, questions });

  let title = t('Start with a skill you want to strengthen');
  let detail = t('Choose a topic or try a starter question. Personal practice never changes an official test result.');
  let label = t('Open practice');
  let action = () => showScreen('practice');
  const lastMiss = answers.find((answer) => !answer.correct);
  const missedQuestion = questions.find((question) => question.id === lastMiss?.questionId);
  if (missedQuestion) {
    title = t('Revisit {topic}', { topic: missedQuestion.topic });
    detail = t('A recent answer needs review. Try another question on this topic and check the explanation.');
    label = t('Practice this topic');
    action = async () => {
      await showScreen('practice');
      const topic = $('#practice-generate-form [name="topic"]');
      topic.value = missedQuestion.topic;
      topic.focus();
    };
  }
  if (participant && assignments.length) {
    title = t('Check your assigned tests');
    detail = assignments.length === 1
      ? t('You have 1 test waiting. Open it to check when it is available.')
      : t('You have {n} tests waiting. Open them to check when they are available.', { n: assignments.length });
    label = t('My tests');
    action = () => showScreen('my-assessments');
  } else if (staff && pendingReviews > 0) {
    title = `Review ${pendingReviews} submitted attempt${pendingReviews === 1 ? '' : 's'}`;
    detail = 'Manual grading and result release belong to your current workspace, not your personal practice.';
    label = 'Review attempts';
    action = () => showScreen('assessment-studio', 'review');
  } else if (staff && academy && areaCount > 0) {
    title = `Review ${areaCount} draft question${areaCount === 1 ? '' : 's'}`;
    detail = 'Approve suitable questions before they can be used in a published test.';
    label = 'Open question bank';
    action = () => showScreen('assessment-studio', 'questions');
  } else if (staff && !academy && areaCount === 0) {
    title = 'Start your first hiring campaign';
    detail = 'A campaign connects a published test to candidate invitations and review.';
    label = 'Open campaigns';
    action = () => showScreen('talent');
  } else if (state.organization && !participant) {
    title = state.organization.type === 'ACADEMY' ? 'Move your Academy forward' : 'Move your hiring campaign forward';
    detail = 'Open the workspace overview for work that needs your attention and direct links to the next step.';
    label = 'Workspace overview';
    action = () => showScreen('overview');
  }
  $('#home-next-title').textContent = title;
  $('#home-next-detail').textContent = detail;
  state.homeAction = action;
  const next = $('#home-next-action');
  next.textContent = label;
  next.disabled = false;
}
function homeWorkRow(title, detail, status, actionLabel, action) {
  const row = node('div', '', 'home-work-row');
  const copy = node('div', '', 'home-work-copy');
  copy.append(node('strong', title), node('p', detail, 'muted'));
  row.append(copy, node('span', status, 'home-work-status'),
    button(actionLabel, action, 'button secondary small home-work-action'));
  return row;
}
function renderHomeWork({ organization, participant, staff, academy, assignmentsResult, reviewResult,
  areaResult, assignments, pendingReviews, areaCount, questionsResult, questions }) {
  const list = $('#home-work-list');
  const description = $('#home-work-description');
  const rows = [];
  if (participant) {
    description.textContent = t('Assignments in {org}. Personal practice is separate.', { org: organization.name });
    if (assignmentsResult.status !== 'fulfilled') {
      rows.push(node('p', t('Assignments could not be loaded. Open My tests to retry.'), 'home-work-empty'));
    } else {
      for (const assignment of assignments.slice(0, 3)) {
        const from = Date.parse(assignment.availableFrom);
        const until = Date.parse(assignment.availableUntil);
        const now = Date.now();
        const status = now < from ? 'Upcoming' : now >= until ? 'Closed' : 'Available';
        const detail = Number.isFinite(until)
          ? `${t(status === 'Upcoming' ? 'Opens {when}' : status === 'Closed' ? 'Closed {when}' : 'Closes {when}',
            { when: new Date(status === 'Upcoming' ? from : until).toLocaleString(getLocale()) })}${assignment.durationMinutes ? ` · ${t('{n} min', { n: assignment.durationMinutes })}` : ''}`
          : t('Open to see the instructions and when it is available');
        rows.push(homeWorkRow(assignment.assessmentTitle || t('Assigned test'), detail,
          t(status), t('Open test'), () => openAssessmentDetail(assignment)));
      }
      if (!assignments.length) rows.push(node('p', t('No assignments in this workspace yet. An organizer can invite you; personal practice is ready now.'), 'home-work-empty'));
    }
  } else if (staff) {
    description.textContent = `Items needing attention in ${organization.name}.`;
    if (reviewResult.status === 'fulfilled') {
      for (const review of rowsOf(reviewResult.value).slice(0, 3)) {
        const grading = review.percentage == null;
        rows.push(homeWorkRow(review.assessmentTitle || 'Test result',
          `${grading ? 'Manual grading needed' : 'Ready for release'} · Submitted ${relative(review.submittedAt)}`,
          grading ? 'Grade' : 'Release', 'Open review', async () => {
            await showScreen('assessment-studio', 'review');
            await showStaffReview(review.attemptId);
          }));
      }
    } else rows.push(node('p', 'The review queue could not be loaded. Open Create tests to retry.', 'home-work-empty'));
    if (academy && areaResult.status === 'fulfilled' && areaCount > 0) {
      rows.push(homeWorkRow('Draft questions', `${areaCount} awaiting review in this workspace`,
        'Draft', 'Open question bank', () => showScreen('assessment-studio', 'questions')));
    }
    if (!academy && areaResult.status === 'fulfilled' && areaCount > 0) {
      rows.push(homeWorkRow('Hiring campaigns', `${areaCount} campaign${areaCount === 1 ? '' : 's'} in this workspace`,
        'Active work', 'Open campaigns', () => showScreen('talent')));
    }
    if (reviewResult.status === 'fulfilled' && pendingReviews === 0 && rows.length === 0) {
      rows.push(node('p', academy ? 'Nothing awaits review. You can build a test or invite learners.'
        : 'Nothing awaits review. Open your campaigns to invite candidates.', 'home-work-empty'));
    }
  } else {
    description.textContent = t('Your private practice is ready. You do not need an organization.');
    if (questionsResult.status === 'fulfilled' && questions.length) {
      rows.push(homeWorkRow(t('Continue personal practice'),
        t(questions.length === 1 ? '1 question available to this account' : '{n} questions available to this account', { n: questions.length }),
        t('Ready'), t('Open practice'), () => showScreen('practice')));
    } else if (questionsResult.status !== 'fulfilled') {
      rows.push(node('p', t('Practice history could not be loaded. Open Practice to retry.'), 'home-work-empty'));
    } else rows.push(homeWorkRow(t('Start personal practice'), t('Choose a topic or try a starter question.'),
      t('Ready'), t('Open practice'), () => showScreen('practice')));
  }
  list.replaceChildren(...rows);
}
$('#home-next-action').addEventListener('click', () => run(() => state.homeAction?.()));
$('#home-account-action').addEventListener('click', () => run(() => showScreen('account')));
$('#ai-tutor-entry-action').addEventListener('click', () => run(() => showScreen('my-assessments')));
$('#home-messages-entry-action').addEventListener('click', () => run(() => showScreen('messages')));
$('#support-ticket-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.currentTarget;
    const values = new FormData(form);
    await request('/support/tickets', {
      method: 'POST',
      body: {
        category: values.get('category'),
        subject: values.get('subject'),
        description: values.get('description'),
        organizationId: values.get('organizationId') || null
      }
    });
    form.reset();
    await loadSupport();
    notice('Support ticket created. Replies will appear here and by email when SMTP is configured.');
  });
});

// The picture sits beside the name inside the profile card; with no picture it shows the first letter.
function paintIdentityAvatar(profile) {
  const image = $('#account-avatar');
  const letter = $('#account-avatar-letter');
  if (!image || !letter) return;
  const source = profile?.avatarDataUrl || '';
  image.src = source;
  image.classList.toggle('hidden', !source);
  letter.classList.toggle('hidden', !!source);
  letter.textContent = ((profile?.displayName || '?').trim().charAt(0) || '?').toUpperCase();
}
async function loadAccount() {
  const admin = state.organization?.myRole === 'ORG_ADMIN';
  const [profileResult, usageResult, planResult] = await Promise.allSettled([
    request('/auth/me'), request('/practice/usage'),
    admin ? request(ownOrg('/billing/entitlements')) : Promise.resolve(null)
  ]);
  const identity = $('#account-identity');
  identity.replaceChildren();
  if (profileResult.status === 'fulfilled') {
    const profile = profileResult.value;
    state.profile = profile;
    $('#account-profile-form').classList.remove('hidden');
    identity.append(node('p', profile.displayName, 'account-name'),
      node('p', profile.email, 'account-email'),
      node('p', profile.emailVerified ? 'Email verified' : 'Email not verified', 'account-verification'));
    $('#account-profile-form [name="displayName"]').value = profile.displayName;
    updateAccountMenu(profile);
    paintIdentityAvatar(profile);
    // The hidden identifier beside the password fields. A password manager needs to
    // know which account a new password belongs to, so it carries the real email
    // rather than sitting empty purely to satisfy the browser's warning.
    const passwordFormUser = $('#account-password-form [name="username"]');
    if (passwordFormUser) passwordFormUser.value = profile.email;
    $('#account-login-method').textContent = `${profile.passwordSet ? 'Password sign-in enabled' : 'No password set'}${profile.googleLinked ? ' · Google linked' : ''}.`;
    $('#account-password-form').classList.toggle('hidden', !profile.passwordSet);
    $('#account-reset-text').textContent = profile.passwordSet
      ? 'Forgot your current password? We will email you a link to choose a new one.'
      : 'You have no password yet, for example because you signed up with Google. We will email you a link to set one.';
    $('#account-reset-button').textContent = profile.passwordSet
      ? 'Email me a reset link' : 'Email me a link to set a password';
  } else {
    identity.append(node('p', 'Profile could not be loaded. Reopen this page to retry.', 'muted'));
    $('#account-profile-form').classList.add('hidden');
    $('#account-password-form').classList.add('hidden');
    $('#account-login-method').textContent = 'Sign-in methods unavailable right now.';
  }
  const plan = $('#account-plan');
  plan.replaceChildren();
  if (usageResult.status === 'fulfilled') {
    const usage = usageResult.value;
    plan.append(node('h3', 'Personal practice · Free'),
      node('p', `${usage.generated} of ${usage.generationLimit} AI questions created today (UTC)`));
    const generated = document.createElement('progress');
    generated.max = usage.generationLimit; generated.value = usage.generated;
    generated.setAttribute('aria-label', 'AI questions used today');
    plan.append(generated, node('p', `${usage.used} of ${usage.limit} answers used today (UTC)`));
    const answered = document.createElement('progress');
    answered.max = usage.limit; answered.value = usage.used;
    answered.setAttribute('aria-label', 'Answers used today');
    plan.append(answered, node('p', 'Personal paid plans are not available yet. No payment is collected for personal practice.', 'muted'));
  } else {
    plan.append(node('p', 'Personal practice allowance could not be loaded. Reopen this page to retry.', 'muted'));
  }
  if (admin) {
    plan.append(node('h3', 'Current workspace plan'));
    if (planResult.status === 'fulfilled') {
      plan.append(node('p', `${state.organization.name}: ${planResult.value.tier}`),
        button('Open billing and limits', () => showScreen('billing'), 'button secondary small'));
    } else plan.append(node('p', 'Workspace plan could not be loaded right now.', 'muted'));
  } else if (state.organization) {
    plan.append(node('h3', 'Current workspace plan'),
      node('p', 'Your organization administrator manages this plan and its billing.', 'muted'));
  }
}

async function loadSupport() {
  const organization = state.organization;
  const select = $('#support-organization');
  if (select) {
    select.replaceChildren(new Option(t('Personal account'), ''));
    for (const entry of state.organizations || []) {
      select.append(new Option(`${entry.name} · ${entry.type}`, entry.id));
    }
    if (organization) select.value = organization.id;
  }
  await loadList('#support-tickets', async () => {
    const page = await request('/support/tickets?page=0');
    setList('#support-tickets', (page.content || []).map((ticket) => item(
      `${ticket.referenceNumber} · ${ticket.status}`,
      `${ticket.subject} · ${ticket.category}`)), 'No support tickets yet.');
  }, 'Support tickets could not be loaded.');
}

$('#account-profile-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.currentTarget;
    const control = form.querySelector('button[type="submit"]');
    control.disabled = true;
    try {
      const profile = await request('/auth/me', { method: 'PATCH', body: { displayName: new FormData(form).get('displayName') } });
      state.profile = profile;
      $('#account-identity .account-name').textContent = profile.displayName;
      notice('Display name saved.');
    } finally { control.disabled = false; }
  });
});

$('#account-avatar-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.currentTarget;
    const control = form.querySelector('button[type="submit"]');
    control.disabled = true;
    try {
      const image = form.querySelector('input[name="image"]').files[0];
      if (!image) throw new Error('Choose a JPEG or PNG image first.');
      const profile = await request('/auth/me/avatar', {
        method: 'POST', body: (() => { const data = new FormData(); data.append('image', image); return data; })()
      });
      state.profile = profile;
      paintIdentityAvatar(profile);
      updateAccountMenu(profile);
      form.reset();
      notice('Profile image saved in compressed form.');
    } finally { control.disabled = false; }
  });
});

$('#account-avatar-form input[name="image"]').addEventListener('change', (event) => {
  const file = event.currentTarget.files[0];
  if (!file || !/^image\/(png|jpeg)$/.test(file.type)) return;
  const reader = new FileReader();
  reader.onload = () => {
    paintIdentityAvatar({ ...(state.profile || {}), avatarDataUrl: String(reader.result) });
    notice('Preview only. Press Save image to keep it.');
  };
  reader.readAsDataURL(file);
});
$('#remove-avatar').addEventListener('click', () => run(async () => {
  await request('/auth/me/avatar', { method: 'DELETE' });
  if (state.profile) { paintIdentityAvatar({ ...state.profile, avatarDataUrl: null }); }
  if (state.profile) { state.profile = { ...state.profile, avatarDataUrl: null }; updateAccountMenu(state.profile); }
  notice('Profile image removed.');
}));

// The reset email is requested from here, so nobody has to leave the app and find a separate page.
$('#account-reset-button').addEventListener('click', (event) => {
  const control = event.currentTarget;
  const status = $('#account-reset-status');
  run(async () => {
    const email = state.profile?.email;
    if (!email) throw new Error('Your profile is still loading. Try again in a moment.');
    control.disabled = true;
    try {
      await request('/auth/request-password-reset', { method: 'POST', body: { email } });
      status.textContent = t('If your account is eligible, a link was sent to {email}. It can take a minute; check spam too.',
        { email });
      // One request a minute is plenty and keeps the server's reset limit from being used up by accident.
      window.setTimeout(() => { control.disabled = false; }, 60000);
    } catch (error) { control.disabled = false; throw error; }
  });
});
$('#account-password-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.currentTarget;
    const control = form.querySelector('button[type="submit"]');
    const values = Object.fromEntries(new FormData(form));
    if (values.newPassword !== values.confirmPassword) {
      throw new Error('The two new passwords are not the same. Type them again.');
    }
    delete values.confirmPassword;
    control.disabled = true;
    try {
      await request('/auth/change-password', { method: 'POST', body: values });
      form.reset();
      showSignedOut();
      notice('Password changed. Sign in again; other sessions have been revoked.');
    } finally { control.disabled = false; }
  });
});
// ===========================================================================
// Overview — the signed-in command centre.
//
// Everything here comes from an endpoint that already exists. Where a figure
// cannot be obtained the card says so rather than showing a zero: a zero and
// "we could not ask" mean different things to someone deciding what to do
// next, and this product's claim is that it does not overstate what it knows.
//
// Four bands, in the order a person needs them:
//   1. Needs you now   work that is actually blocked on this person
//   2. At a glance     counts, each one a link into the screen that owns it
//   3. Plan usage      recorded usage against the enforced limit (ORG_ADMIN)
//   4. Recent activity what has happened lately, and workspace facts
// ===========================================================================

const ROLE_TITLES = { ORG_ADMIN: 'Org Admin', INSTRUCTOR: 'Instructor', LEARNER: 'Learner',
  RECRUITER: 'Recruiter', CANDIDATE: 'Candidate' };

// A fetch that never throws: the caller decides what an absence means.
async function maybe(path) {
  try { return await request(ownOrg(path)); } catch (_) { return null; }
}
function rowsOf(page) { return (page && page.content) || []; }
function totalOf(page, fallback = null) {
  if (!page) return fallback;
  return page.totalElements ?? (page.content || []).length;
}
function relative(value) {
  if (!value) return 'no date recorded';
  const then = Date.parse(value);
  if (Number.isNaN(then)) return 'no date recorded';
  const minutes = Math.round((Date.now() - then) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days < 30 ? `${days} d ago` : new Date(then).toLocaleDateString();
}
function focusRow(title, meta, actionLabel, action, tone) {
  const row = item(title, meta, actionLabel ? [button(actionLabel, action)] : []);
  if (tone) row.querySelector('.item-actions').prepend(node('span', tone[0], `pill ${tone[1]}`));
  return row;
}

async function loadOverview() {
  const organization = state.organization;
  if (!organization) return;
  const role = organization.myRole;
  const academy = organization.type === 'ACADEMY';
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  const participant = ['LEARNER', 'CANDIDATE'].includes(role);

  $('#usage-summary-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  renderWorkspaceJourney(role, academy);

  await Promise.all([
    loadFocus(role, academy, staff, participant),
    loadGlance(role, academy, staff, participant),
    loadActivity(role, academy, staff, participant),
    loadWorkspaceFacts(organization, role),
    role === 'ORG_ADMIN' ? loadUsageSummary() : Promise.resolve()
  ]);
}

function renderWorkspaceJourney(role, academy) {
  const learner = role === 'LEARNER';
  const candidate = role === 'CANDIDATE';
  const steps = learner
    ? [['Join a batch', 'Your instructor enrolls you or sends an invitation.', 'academy'],
      ['Open an assignment', 'See its availability and instructions.', 'attempts'],
      ['Take the assessment', 'Save answers before the server deadline.', 'attempts'],
      ['See result & certificate', 'Results must be released; staff issue a certificate after a pass.', 'certificates']]
    : candidate
      ? [['Accept invitation', 'Use the recruiter link sent to your email.', 'attempts'],
        ['Take the assessment', 'Open your assignment and submit before its deadline.', 'attempts'],
        ['See result', 'The hiring team controls when the result is released.', 'attempts']]
      : academy
        ? [['Review questions', 'Create and approve questions for the bank.', 'questions'],
          ['Publish assessment', 'Choose approved questions and publish a version.', 'assessments'],
          ['Assign learners', 'Add members or a batch, then assign the assessment.', 'assign'],
          ['Review & release', 'Grade any manual answers and release the result.', 'review'],
          ['Issue certificate', 'A passing, released attempt can receive a public verification code.', 'review']]
        : [['Review questions', 'Create and approve questions for the bank.', 'questions'],
          ['Publish assessment', 'Choose approved questions and publish a version.', 'assessments'],
          ['Invite candidates', 'Create a campaign and send assessment invitations.', 'campaigns'],
          ['Compare & decide', 'Review results and integrity signals before recording a decision.', 'talent-campaign']];
  $('#journey-note').textContent = learner || candidate
    ? 'Your organization gives you access; the next step appears when an assignment is available.'
    : 'Follow these stages in order. Each step opens the screen where that work happens.';
  $('#journey-steps').replaceChildren(...steps.map(([title, detail, view]) => {
    const step = node('li', '', 'journey-step');
    step.append(node('strong', title), node('span', detail, 'muted'),
      button('Open', () => showView(view), 'button quiet small'));
    return step;
  }));
}

// --- 1. Needs you now ------------------------------------------------------

async function loadFocus(role, academy, staff, participant) {
  $('#focus-title').textContent = participant ? 'Your next step' : 'Next steps';
  $('#focus-note').textContent = participant
    ? 'Assignments open to you, newest first.'
    : 'What to work on next in this workspace.';
  await loadList('#focus-list', async () => {
    const entries = [];

    if (participant) {
      const mine = await maybe('/assignments/mine?page=0');
      if (mine === null) {
        setList('#focus-list', [], 'Your assignments could not be read just now.');
        return;
      }
      const now = Date.now();
      const resumable = pendingAttempt();
      for (const assignment of rowsOf(mine).slice(0, 4)) {
        const from = Date.parse(assignment.availableFrom);
        const until = Date.parse(assignment.availableUntil);
        const open = now >= from && now < until;
        const inProgress = resumable?.assignmentId === assignment.id;
        const title = assignment.assessmentTitle || 'Assessment assignment';
        const windowText = now < from ? `Opens ${new Date(from).toLocaleString()}`
          : !open ? `Closed ${new Date(until).toLocaleString()}`
          : `Open until ${new Date(until).toLocaleString()}`;
        const minutes = assignment.durationMinutes ? ` · ${assignment.durationMinutes} min` : '';
        const questions = assignment.questionCount ? ` · ${assignment.questionCount} questions` : '';
        entries.push(focusRow(inProgress ? `${title} — attempt in progress` : title,
          `${windowText}${minutes}${questions}`,
          'Open', () => openAssessmentDetail(assignment),
          inProgress ? ['In progress', 'warn'] : open ? ['Open now', 'ok'] : ['Closed', '']));
      }
      setList('#focus-list', entries,
        'No assignment has been given to you in this workspace yet.');
      return;
    }

    if (staff) {
      const pending = await maybe('/attempts/review/pending?page=0');
      for (const review of rowsOf(pending).slice(0, 4)) {
        // An attempt waiting to be graded has no score and no verdict yet. Reading
        // percentage and passed straight out printed "scored null%" and the badge
        // "Did not pass", which tells a grader this person failed before anyone has
        // read their answer — the one thing this screen must never say. The review
        // queue and the Talent comparison already say "pending" and "Awaiting
        // grading" for the same state; this row now matches them.
        const graded = review.percentage != null;
        entries.push(focusRow(
          `${review.assessmentTitle || 'Attempt'} — attempt ${review.attemptNumber}`,
          `${participantName(review)} · ${participantIdNote(review)}`
            + ` · ${graded ? `scored ${review.percentage}%` : 'not graded yet'}`
            + ` · submitted ${relative(review.submittedAt)}`,
          'Review & release', () => showView('review'),
          graded
            ? [review.passed ? 'Passed' : 'Did not pass', review.passed ? 'ok' : 'danger']
            : ['Awaiting grading', 'warn']));
      }
      const drafts = academy ? await maybe('/questions?status=DRAFT&page=0') : null;
      const draftCount = totalOf(drafts, 0);
      if (draftCount) {
        entries.push(focusRow('Questions awaiting approval',
          `${draftCount} draft question${draftCount === 1 ? '' : 's'}. A draft cannot be used in an assessment until it is approved.`,
          'Open question bank', () => showView('questions')));
      }
      const draftAssessments = await maybe('/assessments?status=DRAFT&page=0');
      const draftAssessmentCount = totalOf(draftAssessments, 0);
      if (draftAssessmentCount) {
        entries.push(focusRow('Assessments not yet published',
          `${draftAssessmentCount} draft${draftAssessmentCount === 1 ? '' : 's'}. A draft cannot be assigned until it is published.`,
          'Open studio', () => showView('assessments')));
      }
      if (!entries.length && pending === null && draftAssessments === null) {
        setList('#focus-list', [], 'The work queue could not be read just now.');
        return;
      }
    }

    if (staff && !entries.length) {
      const published = await maybe('/assessments?status=PUBLISHED&page=0');
      if (published !== null && totalOf(published, 0) === 0) {
        entries.push(focusRow('Build your first assessment',
          'Create questions, approve them, then publish an assessment before assigning it.',
          'Open question bank', () => showView('questions')));
      }
    }
    setList('#focus-list', entries,
      staff ? 'No action is waiting. Open Assessment Studio to create or review work.'
        : 'Nothing is waiting on you.');
  }, 'This queue could not be loaded.');
}

// --- 2. At a glance --------------------------------------------------------

async function loadGlance(role, academy, staff, participant) {
  $('#glance-note').textContent = 'Each count opens the screen that owns it.';
  await loadList('#overview-metrics', async () => {
    const jobs = [];
    const count = (path, label, view) => jobs.push(
      maybe(path).then((page) => ({ label, value: totalOf(page), view })));

    if (staff) {
      count('/attempts/review/pending?page=0', 'Waiting for release', 'review');
      count('/assessments?status=PUBLISHED&page=0', 'Published assessments', 'assessments');
      if (academy) {
        count('/questions?status=DRAFT&page=0', 'Questions to approve', 'questions');
        count('/academy/courses?page=0', 'Courses', 'academy');
      } else {
        count('/talent/campaigns?page=0', 'Campaigns', 'campaigns');
      }
      if (role === 'ORG_ADMIN') count('/members?page=0', 'Members', 'members');
    }
    if (participant) {
      count('/assignments/mine?page=0', 'Assignments', 'attempts');
      if (academy && role === 'LEARNER') count('/certificates/mine?page=0', 'Certificates', 'certificates');
    }
    if (['INSTRUCTOR', 'LEARNER'].includes(role)) {
      count('/messaging/conversations?page=0', 'Conversations', 'messages');
    }

    const metrics = await Promise.all(jobs);
    const cards = metrics.map(({ label, value, view }) => {
      const card = node('button', '', 'metric');
      card.type = 'button';
      // A null total means the request failed. Saying so beats printing a zero.
      const unknown = value === null;
      card.append(node('span', unknown ? '—' : String(value), 'metric-value'),
        node('span', label, 'metric-label'));
      if (unknown) {
        card.classList.add('metric-unknown');
        card.append(node('span', 'could not be read', 'metric-note'));
      }
      card.addEventListener('click', () => run(() => showView(view)));
      return card;
    });
    setList('#overview-metrics', cards, 'No counts apply to your role in this workspace.');
  }, 'Workspace counts could not be loaded.');
}

// --- 3. Plan usage ---------------------------------------------------------

// Recorded usage against the limit the server enforces. Both numbers come from
// the reconciliation report, which is the source the platform screen uses too,
// so this bar cannot disagree with the quota that actually blocks an action.
async function loadUsageSummary() {
  const host = $('#usage-summary');
  host.setAttribute('aria-busy', 'true');
  host.replaceChildren(node('p', 'Reading recorded usage…', 'empty'));
  const [report, plan] = await Promise.all([
    maybe('/billing/entitlements/usage-reconciliation'),
    maybe('/billing/entitlements')
  ]);
  host.setAttribute('aria-busy', 'false');
  if (!report || !plan) {
    host.replaceChildren(node('p', 'Recorded usage could not be read just now.', 'empty'));
    return;
  }
  const limits = [
    ['PUBLISHED_ASSESSMENT', 'Published assessments', plan.maxPublishedAssessments, 'Across this workspace'],
    ['INVITATION', 'Invitations', plan.monthlyInvitations, 'This month (UTC)'],
    ['CERTIFICATE', 'Certificates', plan.monthlyCertificates, 'This month (UTC)']
  ];
  host.replaceChildren(...limits.map(([metric, label, limit, period]) => {
    const entry = (report.entries || []).find((row) => row.metric === metric && !row.windowClosed);
    const used = entry?.recordedUsage ?? 0;
    const share = limit > 0 ? Math.max(0, Math.min(100, Math.round((used / limit) * 100))) : 0;
    const cell = node('div', '', 'usage-cell');
    const head = node('div', '', 'usage-head');
    head.append(node('span', label, 'usage-label'),
      node('span', `${used} / ${limit}`, 'usage-figure'));
    const bar = node('div', '', 'bar');
    const fill = node('div', '', 'bar-fill');
    fill.style.width = `${share}%`;
    if (share >= 90) fill.classList.add('bar-danger');
    else if (share >= 70) fill.classList.add('bar-warn');
    bar.append(fill);
    // The window, and any disagreement between counter and records, are facts
    // the server reported. They belong beside the bar, not hidden behind it.
    const note = entry?.status && entry.status !== 'MATCHES'
      ? `${period} · counter needs review`
      : period;
    cell.append(head, bar, node('p', `${share}% used · ${note}`, 'usage-note'));
    return cell;
  }));
}

// --- 4. Recent activity and workspace facts --------------------------------

async function loadActivity(role, academy, staff, participant) {
  $('#activity-title').textContent = participant ? 'Your record' : 'Recent activity';
  $('#activity-note').textContent = participant
    ? 'Certificates and assignments on your account.'
    : 'The newest records this workspace has produced.';
  await loadList('#activity-list', async () => {
    const entries = [];

    if (participant) {
      const certificates = academy && role === 'LEARNER' ? await maybe('/certificates/mine?page=0') : null;
      for (const certificate of rowsOf(certificates).slice(0, 3)) {
        entries.push(item(`Certificate ${certificate.verificationCode || certificate.id}`,
          `${certificate.status || 'ISSUED'} · issued ${relative(certificate.issuedAt)}`,
          [button('View', () => showView('certificates'))]));
      }
      const mine = await maybe('/assignments/mine?page=0');
      for (const assignment of rowsOf(mine).slice(0, 3)) {
        entries.push(item(assignment.assessmentTitle || 'Assessment assignment',
          `Window ${new Date(assignment.availableFrom).toLocaleDateString()}`
            + ` – ${new Date(assignment.availableUntil).toLocaleDateString()}`
            + ` · ${assignment.attemptsAllowed} attempt${assignment.attemptsAllowed === 1 ? '' : 's'} allowed`,
          [button('Open', () => openAssessmentDetail(assignment))]));
      }
    }

    if (staff) {
      if (academy) {
        const courses = await maybe('/academy/courses?page=0');
        for (const course of rowsOf(courses).slice(0, 3)) {
          entries.push(item(course.name, `Course · created ${relative(course.createdAt)}`,
            [button('Open workspace', () => showView('academy-batch'))]));
        }
      } else {
        const campaigns = await maybe('/talent/campaigns?page=0');
        for (const campaign of rowsOf(campaigns).slice(0, 3)) {
          entries.push(item(campaign.title || campaign.name || 'Campaign',
            `${campaign.open === false ? 'Closed' : 'Open'} · created ${relative(campaign.createdAt)}`,
            [button('Open workspace', () => showView('talent-campaign'))]));
        }
      }
      const assessments = await maybe('/assessments?page=0');
      for (const assessment of rowsOf(assessments).slice(0, 3)) {
        entries.push(item(assessment.title,
          `${assessment.status} · version ${assessment.latestVersion}`
            + ` · ${assessment.questionIds?.length ?? 0} questions`,
          [button('Open studio', () => showView('assessments'))]));
      }
    }

    setList('#activity-list', entries, 'Nothing has been recorded in this workspace yet.');
  }, 'Recent activity could not be loaded.');
}

async function loadWorkspaceFacts(organization, role) {
  const facts = [
    item(organization.name,
      `${organization.type === 'ACADEMY' ? 'Academy' : 'Talent'} workspace`
        + ` · you are ${ROLE_TITLES[role] || role}`
        + (organization.owner ? ' · you own this workspace' : ''))
  ];
  if (role === 'ORG_ADMIN') {
    const plan = await maybe('/billing/entitlements');
    facts.push(plan
      ? item(`${plan.tier} plan`,
        `${plan.maxPublishedAssessments} published assessments`
          + ` · ${plan.monthlyInvitations} invitations per month`
          + ` · ${plan.monthlyCertificates} certificates per month`
          + ` · ${plan.retentionDays}-day retention`)
      : item('Plan', 'Plan limits could not be read just now.'));
  }
  setList('#workspace-facts', facts, '');
}

// The mobile sections menu. Collapsed is the default below 900px, where the
// stylesheet hides the list; above it the attribute is ignored and the sidebar
// renders as it always has, so desktop behaviour is untouched.
function mobileNav() {
  return { toggle: $('#nav-toggle'), nav: $('#workspace-nav') };
}
function setNavCollapsed(collapsed) {
  const { toggle, nav } = mobileNav();
  if (!toggle || !nav) return;
  nav.dataset.collapsed = String(collapsed);
  toggle.setAttribute('aria-expanded', String(!collapsed));
}
// The control names the screen you are on, so the menu never costs you your place.
const SCREEN_ICONS = { start: 'home', practice: 'practice', 'workspace-access': 'building', overview: 'overview',
  members: 'members', 'assessment-studio': 'clipboard', academy: 'book', messages: 'message',
  certificates: 'award', 'my-assessments': 'check', talent: 'briefcase', billing: 'card', platform: 'shield' };
// The icon is decoration and the label carries the meaning, so the two are separate elements:
// a change of language rewrites the label and leaves the icon alone.
function navButton(key, title) {
  const button = node('button', ''); button.type = 'button';
  button.dataset.screen = key; button.dataset.en = title;
  const icon = node('span', '', 'ic'); icon.dataset.ic = SCREEN_ICONS[key] || 'overview';
  icon.setAttribute('aria-hidden', 'true');
  button.append(icon, node('span', t(title), 'nav-label'));
  button.addEventListener('click', () => run(() => showScreen(key)));
  return button;
}
// The original, signed-in tab: a payment came back in another tab. Come forward, show Billing,
// read it from the server, and say what happened. Only signed-in tabs answer, so a tab at the
// sign-in form never claims to have handled it.
billingChannel?.addEventListener('message', (event) => {
  if (event.data?.type !== 'billing-return' || !state.accessToken) return;
  const outcome = normalizeBillingOutcome(event.data.outcome);
  if (!Object.hasOwn(BILLING_OUTCOMES, outcome)) return;
  billingChannel.postMessage({ type: 'billing-ack' });
  window.focus();
  document.querySelector('#checkout-fallback')?.remove();
  run(async () => {
    await showScreen('billing');
    notice(`${t(BILLING_OUTCOMES[outcome])} ${t('A browser redirect cannot confirm a payment; the status shown here is the server\'s.')}`);
  });
});
function setNavToggleLabel(text) {
  const label = $('.nav-toggle-label');
  if (label) label.textContent = text || 'Menu';
}
$('#nav-toggle').addEventListener('click', () => {
  const { nav } = mobileNav();
  setNavCollapsed(nav.dataset.collapsed !== 'true');
});

// -- React Practice pilot ---------------------------------------------------
// Off unless the URL carries ?pilot=practice, so the production dashboard is
// untouched: there is no second navigation, no second route, and the module is
// never fetched. The existing Practice screen is still what everyone else gets.
//
// It mounts into the existing #practice-view, inside this page, for one reason:
// the access token lives in a variable here and nowhere else. A React app served
// at its own URL could not see it, and copying it into storage to work around
// that would be a security regression rather than a migration. So the pilot is
// handed `request` and never the token.
function pilotRequested() {
  return new URLSearchParams(location.search).get('pilot') === 'practice';
}
let pilotModule = null;
async function mountPracticePilot() {
  const host = $('#practice-view');
  if (!host) return false;
  try {
    if (!pilotModule) pilotModule = await import('/pilot/practice.js');
  } catch (error) {
    // The bundle is a build artefact and is not committed, so a checkout without
    // it is normal. Say so plainly and leave the existing screen in place.
    notice('The Practice pilot bundle is not built. Run: npm --prefix frontend run build');
    return false;
  }
  // Only clear the host the first time. Clearing it on every visit emptied the
  // container that React still held a root against, so coming back to Practice
  // showed nothing at all and reported no error.
  if (host.dataset.pilot !== 'practice') host.replaceChildren();
  host.dataset.pilot = 'practice';
  pilotModule.mount(host, {
    request: (path, init) => request(path, init),
    // The pilot changes a quota the rest of the page also shows.
    onUsageChanged: () => { void loadHome().catch(() => {}); }
  });
  return true;
}

// Open a screen, optionally at one of its tabs. This is what the sidebar, the
// overview shortcuts and the URL all go through.
async function showScreen(screenKey, viewKey) {
  const screen = SCREENS[screenKey];
  if (!screen) return;
  const tabs = screen.views;
  const target = tabs.some(([id]) => id === viewKey) ? viewKey : tabs[0][0];
  await showView(target);
}
function renderScreenTabs(screenKey, activeView) {
  const strip = $('#screen-tabs');
  const screen = SCREENS[screenKey];
  const tabs = screen ? screen.views : [];
  // A screen with one view needs no tab strip; the sidebar already named it.
  strip.classList.toggle('hidden', tabs.length < 2);
  if (tabs.length < 2) { strip.replaceChildren(); return; }
  strip.replaceChildren(...tabs.map(([id, label]) => {
    const tab = node('button', t(label), 'tab');
    tab.type = 'button';
    tab.setAttribute('role', 'tab');
    tab.dataset.view = id;
    const active = id === activeView;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-selected', String(active));
    tab.addEventListener('click', () => run(() => showView(id)));
    return tab;
  }));
}
// The route is the screen, then the tab when the screen has more than one.
// Keeping it in the hash rather than the path means a reload never asks the
// server for a page that does not exist.
function writeRoute(screenKey, viewKey) {
  const screen = SCREENS[screenKey];
  const route = screen && screen.views.length > 1 ? `#/${screenKey}/${viewKey}` : `#/${screenKey}`;
  if (location.hash !== route) history.replaceState(null, '', route);
  if (state.accessToken) document.querySelector('.topbar .brand')?.setAttribute('href', route);
}
function readRoute() {
  const parts = (location.hash || '').replace(/^#\/?/, '').split('/').filter(Boolean);
  if (!parts.length) return null;
  const [screenKey, viewKey] = parts;
  return SCREENS[screenKey] ? { screenKey, viewKey } : null;
}
// Paints the page head — scope line, title, subtitle — for one view. A function of its
// own because a change of language has to repaint it without reloading the screen,
// and a reload would end the session: the access token lives only in memory.
//
// Order of the three lines: where (the organization, or "Personal"), what (the title),
// why (the subtitle). The organization used to be repeated in all three places.
function paintPageHead(key) {
  const screenKey = screenOfView(key) || 'overview';
  const screen = SCREENS[screenKey];
  if (!screen) return;
  const personal = ['start', 'practice', 'account', 'workspace-access', 'platform'].includes(screenKey);
  const tabLabel = (screen.views.find(([id]) => id === key) || [, screen.title])[1];
  $('#page-title').textContent = t(screen.title);
  setNavToggleLabel(t(screen.title));
  document.title = screen.views.length > 1
    ? `${t(tabLabel)} · ${t(screen.title)} · JachAI`
    : `${t(screen.title)} · JachAI`;
  titleBadge();
  $('#page-subtitle').textContent = t(screen.subtitle);
  $('#page-scope').textContent = personal || !state.organization ? t('Personal') : state.organization.name;
  $('#page-role-context').classList.toggle('hidden', personal);
}
// A language change repaints what the person is looking at in place.
function relabelShell() {
  applyLanguage();
  assistant?.relabel();
  paintUnread();
  for (const control of $('#workspace-nav').querySelectorAll('[data-en]')) {
    (control.querySelector('.nav-label') || control).textContent = t(control.dataset.en);
  }
  const key = state.view;
  if (key) {
    paintPageHead(key);
    renderScreenTabs(screenOfView(key) || 'overview', key);
  }
  if (state.organization) {
    $('#sidebar-context').textContent = t('Your organization');
    $('#workspace-role').textContent = `${t(state.organization.type === 'ACADEMY' ? 'Academy' : 'Talent')} · ${myRoleName(state.organization)}`;
    $('#page-role-context').textContent = `${t('Your role here:')} ${myRoleName(state.organization)}`;
  }
  paintLanguageToggle();
  if (key === 'messages' && state.activeConversationId) loadConversationMessages().catch(() => {});
  if (key === 'messages') loadConversations().catch(() => {});
  if (key === 'start') loadHome().catch(() => {});
}
function paintLanguageToggle() {
  // Both languages are shown at once, the chosen one filled in: the person never has to guess
  // what a single button will switch to.
  for (const option of document.querySelectorAll('#lang-switch [data-lang]')) {
    const active = option.dataset.lang === getLanguage();
    option.classList.toggle('active', active);
    option.setAttribute('aria-pressed', String(active));
  }
}
document.addEventListener('jachai:language', relabelShell);
$('#lang-switch')?.addEventListener('click', (event) => {
  const option = event.target.closest('[data-lang]');
  if (option && option.dataset.lang !== getLanguage()) setLanguage(option.dataset.lang);
});
applyLanguage();
paintLanguageToggle();

async function showView(key) {
  if (key !== 'messages') stopMessageRefresh();
  state.view = key;
  const screenKey = screenOfView(key) || 'overview';
  for (const view of views) $(`#${view}-view`).classList.toggle('hidden', key !== view);
  for (const tab of $('#workspace-nav').querySelectorAll('button')) {
    const active = tab.dataset.screen === screenKey;
    tab.classList.toggle('active', active);
    if (active) tab.setAttribute('aria-current', 'true'); else tab.removeAttribute('aria-current');
  }
  renderScreenTabs(screenKey, key);
  $('#platform-band')?.classList.toggle('hidden', !key.startsWith('platform'));
  writeRoute(screenKey, key);
  const screen = SCREENS[screenKey];
  // Choosing a section closes the menu, so the content it opened is what you see.
  setNavCollapsed(true);
  paintPageHead(key);
  void ensureProfile();
  const heading = $('#page-title');
  heading.setAttribute('tabindex', '-1');
  heading.focus({ preventScroll: true });
  if (key.startsWith('platform')) { await loadPlatformView(); return; }
  if (key === 'start') { await loadHome(); return; }
  if (key === 'account') { await loadAccount(); return; }
  if (key === 'support') { await loadSupport(); return; }
  if (key === 'practice') { await loadPractice(); return; }
  if (key === 'overview') await loadOverview();
  if (key === 'workspace-access') loadWorkspaceAccess();
  if (key === 'overview' && state.organization?.myRole === 'ORG_ADMIN') {
    await loadOrganizationSettings();
    ensureTenantSupportAuditPanel();
    await loadList('#tenant-support-audit', () => loadTenantSupportAudit(0),
      'Platform support history could not be loaded.');
  }
  if (key === 'billing' && state.organization?.myRole === 'ORG_ADMIN') {
    await loadEntitlements();
    await loadList('#usage-report', () => loadUsageReconciliation(),
      'The quota check could not be run.');
  }
  if (key === 'members') {
    if (!state.organization) return;
    await loadList('#members-list', () => loadMembers(), 'Members could not be loaded.');
    await loadList('#join-code-list', () => loadJoinCodes(), 'Join codes could not be loaded.');
    attachFilter('#members-list', 'Filter members by name, email, role or ID');
  }
  if (key === 'questions') {
    if (!state.organization) return;
    await loadList('#questions-list', () => loadQuestions(), 'Questions could not be loaded.');
    attachFilter('#questions-list', 'Filter questions by topic, prompt or status');
  }
  if (key === 'assessments') {
    if (!state.organization) return;
    await loadList('#assessments-list', async () => {
      await loadApprovedQuestions(); await loadAssessments();
    }, 'Assessments could not be loaded.');
    attachFilter('#assessments-list', 'Filter assessments by title, status or release policy');
  }
  if (key === 'assign') {
    if (!state.organization) return;
    await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
  }
  if (key === 'review') {
    if (!state.organization) return;
    await loadList('#review-queue', () => loadReviewQueue(0), 'The review queue could not be loaded.');
    await loadOrganizationSettings();
  }
  if (key === 'academy') {
    if (!state.organization) return;
    await loadList('#courses-list', () => loadAcademyCatalog(), 'Academy courses could not be loaded.');
    attachFilter('#courses-list', 'Filter courses by name or description');
    if (state.organization.myRole === 'ORG_ADMIN') {
      await loadList('#certificate-design-preview', () => loadCertificateDesign(),
        'Certificate design could not be loaded.');
    }
  }
  if (key === 'academy-batch') await loadAcademyBatchWorkspace();
  if (key === 'messages') await loadMessaging();
  if (key === 'campaigns') {
    await loadList('#campaigns-list', async () => {
      await loadPublishedAssessments('#campaign-assessment', '#campaign-assessment-pagination');
      await loadCampaigns(); await loadTalentCandidates();
    }, 'Campaigns could not be loaded.');
    attachFilter('#campaigns-list', 'Filter campaigns by title or version');
  }
  if (key === 'talent-campaign') await loadTalentCampaignWorkspace();
  if (key === 'attempts') {
    await loadList('#assignments-list', () => loadAssignments(), 'Assignments could not be loaded.');
    attachFilter('#assignments-list', 'Filter assignments by availability or attempt count');
  }
  if (key === 'assessment-detail') await loadAssessmentDetail();
  if (key === 'certificates') {
    await loadList('#certificates-list', () => loadCertificates(), 'Certificates could not be loaded.');
    attachFilter('#certificates-list', 'Filter certificates by code, status or date');
  }
  // Re-initialize password toggles for any dynamically loaded forms
  reinitPasswordToggles();
}
async function loadEntitlements() {
  const plan = await request(ownOrg('/billing/entitlements'));
  const planCard = node('div', '', `plan-hero plan-hero--${String(plan.tier).toLowerCase()}`);
  planCard.append(node('span', plan.tier, 'plan-hero-badge'),
    node('strong', `${plan.tier} plan`, 'plan-hero-title'),
    node('p', `${state.organization.type} workspace · old messages and activity records are deleted after ${plan.retentionDays} days`, 'muted'));
  setList('#plan-details', [
    planCard,
    item('Assessments & invitations', `${plan.maxPublishedAssessments} published assessments · ${plan.monthlyInvitations} invitations per month`),
    item('AI & certificates', `${plan.dailyUserAiRequests} AI requests per user/day · ${plan.monthlyCertificates} certificates per month`)
  ], 'Plan details are unavailable.');
  const options = await request(ownOrg('/billing/options'));
  $('#start-demo-pay').disabled = !options.demoCheckoutAvailable;
  const checkout = $('#start-ssl-demo');
  checkout.disabled = !options.sslCommerzAvailable;
  $('#start-bkash-demo').disabled = !options.directBkashAvailable;
  const sandboxNote = options.sandboxOnly === false
    ? ' This site is not marked as practice-only. Do not enter real payment details.' : '';
  $('#billing-hint').textContent = options.demoCheckoutAvailable
    ? t('Upgrade to the TEAM plan for ৳{price} for 30 days. This is a demo: no real money is charged.',
      { price: options.demoTeamPriceBdt }) + sandboxNote
    : (options.sslCommerzAvailable || options.directBkashAvailable
    ? `Sandbox only · TEAM demo price: ৳${options.demoTeamPriceBdt} for 30 days. No real money is charged.`
    : 'Sandbox merchant credentials are not configured yet, so both demo checkout buttons stay disabled. No real money is charged.')
    + (options.sslCommerzAvailable ? '' : ' SSLCOMMERZ sandbox credentials are not configured.')
    + (options.directBkashAvailable ? '' : ' Direct bKash sandbox credentials are not configured.')
    + sandboxNote;
  await loadPaymentHistory();
}
async function loadPaymentHistory(pageNumber = 0) {
  const payments = await request(ownOrg(`/billing/orders?page=${pageNumber}`));
  setList('#payment-history', (payments.content || []).map((order) => {
    const actions = [];
    if (order.status === 'CHECKOUT_READY' || order.status === 'VERIFIED') {
      actions.push(button('Check payment', async () => {
        await request(ownOrg(`/billing/orders/${encodeURIComponent(order.id)}/reconcile`), { method: 'POST' });
        await loadEntitlements();
      }));
    }
    if (order.status === 'CREATED' || order.status === 'CHECKOUT_READY') {
      actions.push(button('Cancel checkout', async () => {
        if (!await confirmDialog('Cancel this unfinished sandbox checkout? No entitlement has been granted.')) return;
        await request(ownOrg(`/billing/orders/${encodeURIComponent(order.id)}/cancel`), { method: 'POST' });
        await loadPaymentHistory();
      }, 'button quiet small'));
    }
    actions.push(button('Details', () => showOrderDetail(order.id), 'button quiet small'));
    return item(
      `${order.provider === 'DEMO' ? 'Checkout' : order.provider} · ৳${order.amountBdt} · ${order.status}`,
      `${order.id} · ${new Date(order.createdAt).toLocaleString()}`,
      actions);
  }), 'No demo transactions yet.');
  renderPageControls('#payment-history-pagination', payments, loadPaymentHistory);
  if (state.orderDetailId
      && !(payments.content || []).some((order) => order.id === state.orderDetailId)) {
    hideOrderDetail();
  }
}
function hideOrderDetail() {
  state.orderDetailId = null;
  state.orderDetailToken += 1;
  $('#order-detail').classList.add('hidden');
}
async function showOrderDetail(orderId) {
  state.orderDetailId = orderId;
  const panel = $('#order-detail');
  panel.classList.remove('hidden');
  await loadOrderDetail();
  panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  $('#order-detail-close').focus({ preventScroll: true });
}
async function loadOrderDetail() {
  const orderId = state.orderDetailId;
  if (!orderId) return;
  state.orderDetailToken += 1;
  const token = state.orderDetailToken;
  await loadList('#order-detail-body', async () => {
    const order = await request(ownOrg(`/billing/orders/${encodeURIComponent(orderId)}`));
    if (token !== state.orderDetailToken) return;
    // The list already shows provider, amount and status. What it cannot show
    // is where the order stopped, so the detail is the lifecycle: the server
    // only grants access at the applied step, and a gap between these stamps
    // is exactly what "Check payment" is for.
    const stages = [
      ['Created', order.createdAt, 'Checkout was started in the sandbox.'],
      ['Verified with the provider', order.verifiedAt,
        'The provider confirmed the transaction to the server. A browser redirect alone never reaches this step.'],
      ['Applied to the plan', order.appliedAt,
        'Entitlements were granted. Until this is stamped, the plan has not changed.']
    ];
    if (order.status === 'CANCELLED') {
      stages.push(['Cancelled', order.createdAt, 'The unfinished checkout was cancelled; it cannot be reconciled or grant entitlement.']);
    }
    setList('#order-detail-body', [
      item(`${order.provider} · ৳${order.amountBdt}`, `${order.id} · status ${order.status}`),
      ...stages.map(([label, stamp, explanation]) => {
        const row = item(label, `${stamp ? when(stamp) : 'Not reached'} · ${explanation}`);
        row.querySelector('.item-actions')
          .append(node('span', stamp ? 'Done' : 'Pending', stamp ? 'pill ok' : 'pill warn'));
        return row;
      })
    ], 'This order could not be described.');
  }, 'The order detail could not be loaded.');
}
async function loadPlatformDashboard() {
  const view = $('#platform-view');
  let panel = $('#platform-dashboard');
  if (!panel) {
    panel = node('section', '', 'card panel platform-dashboard');
    panel.id = 'platform-dashboard';
    view.prepend(panel);
  }
  panel.replaceChildren(node('p', 'Loading platform overview…', 'muted'));
  try {
    const data = await request('/platform/dashboard');
    const heading = node('div', '', 'section-heading');
    const title = node('div');
    title.append(node('h2', 'Platform overview'),
      node('p', 'Practice payments are only tests, not real income.', 'muted'));
    heading.append(title);
    const metrics = node('div', '', 'platform-metrics');
    const metric = (label, value) => {
      const card = node('div', '', 'platform-metric');
      card.append(node('span', label, 'muted'), node('strong', value));
      return card;
    };
    const amount = `৳${Number(data.sandboxAppliedAmountBdt || 0).toLocaleString('en-BD', { maximumFractionDigits: 2 })}`;
    metrics.append(metric('Applied sandbox orders', String(data.sandboxAppliedOrders)),
      metric('Sandbox value · not revenue', amount),
      metric('Active TEAM workspaces', String(data.activeTeamWorkspaces)),
      metric('Open support tickets', String(data.openSupportTickets)));
    const activity = node('div', '', 'platform-activity');
    const months = node('div', '', 'platform-months');
    months.append(node('h3', 'Last six months'));
    for (const month of data.monthlySandboxOrders || []) {
      const row = node('p');
      row.append(node('span', month.month), node('strong', `${month.orders} orders`));
      months.append(row);
    }
    const alerts = node('div', '', 'platform-alerts');
    alerts.append(node('h3', 'Needs attention'));
    for (const [label, count] of [
      ['Failed emails', data.failedEmailJobs],
      ['Payments awaiting verification', data.paymentsAwaitingVerification],
      ['Open support tickets', data.openSupportTickets]
    ]) {
      const row = node('p');
      row.append(node('span', label), node('strong', String(count), Number(count) > 0 ? 'platform-count is-bad' : 'platform-count is-ok'));
      alerts.append(row);
    }
    activity.append(months, alerts);
    panel.replaceChildren(heading, metrics, activity);
  } catch (error) {
    panel.replaceChildren(node('p', 'Platform overview could not be loaded. Use Refresh to retry.', 'muted'));
    throw error;
  }
}
async function loadPlatformView() {
  if (!state.platformAdmin) throw new Error('Platform administrator access required.');
  ensurePlatformSupportPanel();
  await Promise.all([
    loadPlatformDashboard(),
    loadList('#platform-operations', () => loadPlatformOperations(), 'Operational counts could not be loaded.'),
    loadList('#platform-storage', () => loadPlatformStorage(), 'Storage metrics could not be loaded.'),
    loadList('#platform-organizations', () => loadPlatformOrganizations(0), 'Organizations could not be loaded.')
  ]);
  await loadSupportInbox();
  if (state.platformOrganizationId) {
    await inspectPlatformOrganization(state.platformOrganizationId);
  } else {
    setList('#platform-support-preview', [], 'Inspect an organization before opening a support ticket.');
    setList('#platform-support-audit', [], 'No organization selected.');
    $('#platform-support-audit-pagination').replaceChildren();
    setList('#platform-manual-plan', [], 'Inspect an organization above to see and change its manual TEAM grant.');
    setList('#platform-manual-audit', [], 'No organization selected.');
    $('#platform-manual-audit-pagination').replaceChildren();
    $('#platform-manual-revoke-submit').disabled = true;
  }
  async function loadSupportInbox() {
    const status = $('#support-inbox-status')?.value;
    const query = status ? `?status=${encodeURIComponent(status)}` : '';
    await loadList('#support-inbox-list', async () => {
      const page = await request(`/platform/support/tickets${query}`);
      setList('#support-inbox-list', (page.content || []).map((ticket) => item(
        `${ticket.referenceNumber} · ${ticket.status}`,
        `${ticket.subject} · ${ticket.category}`,
        [button('Open', () => run(() => openSupportTicket(ticket.id)), 'button secondary small')]
      )), 'No support tickets match this filter.');
    }, 'Support inbox could not be loaded.');
  }
  async function openSupportTicket(ticketId) {
    const detail = $('#support-inbox-detail');
    const [ticket, replyPage] = await Promise.all([
      request(`/platform/support/tickets/${encodeURIComponent(ticketId)}`),
      request(`/platform/support/tickets/${encodeURIComponent(ticketId)}/replies?page=0`)
    ]);
    detail.classList.remove('hidden');
    detail.replaceChildren(
      node('h3', `${ticket.referenceNumber} · ${ticket.status}`),
      node('p', `${ticket.subject} · ${ticket.category}`, 'muted'),
      node('p', ticket.description),
      node('div', '', 'item-list')
    );
    const replies = detail.querySelector('.item-list');
    (replyPage.content || []).forEach((reply) => replies.append(
      item(reply.publicReply ? 'Public reply' : 'Internal note',
        `${reply.authorUserId} · ${new Date(reply.createdAt).toLocaleString()}`,
        [node('p', reply.body)]
      )));
    const form = node('form', '', 'stack');
    form.innerHTML = `<label>Reply or internal note<textarea name="body" rows="4" maxlength="4000" required></textarea></label>
      <label><input type="checkbox" name="publicReply" checked> Send as public reply</label>
      <button class="button primary" type="submit">Add response</button>`;
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      run(async () => {
        const values = new FormData(form);
        await request(`/platform/support/tickets/${encodeURIComponent(ticketId)}/replies`, {
          method: 'POST', body: { body: values.get('body'), publicReply: values.has('publicReply') }
        });
        await openSupportTicket(ticketId);
        notice('Support response saved.');
      });
    });
    const statusForm = node('form', 'form-row');
    statusForm.innerHTML = `<label>Change status<select name="status"><option value="WORKING_ON_IT">Working on it</option><option value="WAITING_FOR_YOUR_REPLY">Waiting for reply</option><option value="RESOLVED">Resolved</option><option value="CLOSED">Closed</option></select></label><button class="button quiet" type="submit">Update status</button>`;
    statusForm.elements.status.value = ticket.status;
    statusForm.addEventListener('submit', (event) => {
      event.preventDefault();
      run(async () => {
        await request(`/platform/support/tickets/${encodeURIComponent(ticketId)}/status?status=${statusForm.elements.status.value}`, { method: 'PUT' });
        await loadSupportInbox();
        await openSupportTicket(ticketId);
        notice('Support ticket status updated.');
      });
    });
    detail.append(form, statusForm);
  }
  if (state.platformTargetUserId) await inspectPlatformUser(state.platformTargetUserId);
}
function ensurePlatformSupportPanel() {
  if ($('#platform-support-panel')) return;
  const panel = node('div', '', 'card panel');
  panel.id = 'platform-support-panel';
  panel.innerHTML = `<h2>Test details for a support ticket</h2>
    <p class="muted">Special access to the details of one test, never its questions. Every look is logged, and that organization's admins can see it. No questions, answers, attempts or learner details are shown.</p>
    <form id="platform-support-form" class="form-row">
      <label>Assessment ID<input name="assessmentId" maxlength="100" required autocomplete="off" placeholder="Exact assessment ID"></label>
      <label>Support ticket<input name="ticketReference" minlength="6" maxlength="80" required autocomplete="off" placeholder="Ticket reference"></label>
      <label>Reason<input name="reason" minlength="20" maxlength="500" required placeholder="Why access is needed"></label>
      <label><input name="acknowledgeTenantContent" type="checkbox" required> I acknowledge this accesses tenant metadata</label>
      <button class="button secondary" type="submit">Preview with audit</button>
    </form>
    <div id="platform-support-preview" class="item-list"></div>
    <div class="section-heading"><h3>Support access audit</h3><button id="platform-support-audit-refresh" class="button quiet small" type="button">Reload</button></div>
    <div id="platform-support-audit" class="item-list"></div><div id="platform-support-audit-pagination"></div>`;
  $('#platform-inspect-user').closest('.card').insertAdjacentElement('beforebegin', panel);
  const form = $('#platform-support-form');
  form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
    const id = state.platformOrganizationId;
    if (!id || $('#platform-organization-id').value.trim() !== id)
      throw new Error('Pick an organization first, then open a support ticket.');
    const values = new FormData(form);
    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true;
    beginList('#platform-support-preview');
    try {
      const preview = await request(`/platform/organizations/${encodeURIComponent(id)}/support/assessment-access`, {
        method: 'POST', body: {
          assessmentId: String(values.get('assessmentId') || '').trim(),
          ticketReference: String(values.get('ticketReference') || '').trim(),
          reason: String(values.get('reason') || '').trim(),
          acknowledgeTenantContent: values.get('acknowledgeTenantContent') === 'on'
        }
      });
      if (id !== state.platformOrganizationId || $('#platform-organization-id').value.trim() !== id) return;
      setList('#platform-support-preview', [item(`${preview.title} · ${preview.status}`,
        `Assessment ${preview.assessmentId} · version ${preview.latestVersion || 0} · ${preview.questionCount} question(s) · ${preview.durationMinutes} minutes · audit ${preview.accessEventId}`)],
      'No preview returned.');
      await loadPlatformSupportAudit(id, 0, state.platformLoadToken);
      notice('Done. The admins of that organization can see that you looked.');
    } catch (error) {
      if (id === state.platformOrganizationId)
        setList('#platform-support-preview', [], 'Preview unavailable. Check the ticket and try again.');
      fieldErrors(form, error);
      throw error;
    } finally { submit.disabled = false; }
  }); });
  $('#platform-support-audit-refresh').addEventListener('click', () => run(() => {
    const id = state.platformOrganizationId;
    if (!id) throw new Error('Inspect an organization first.');
    return loadPlatformSupportAudit(id, 0, state.platformLoadToken);
  }));
}
async function loadPlatformSupportAudit(id, pageNumber, token) {
  const page = await request(`/platform/organizations/${encodeURIComponent(id)}/support/assessment-access/audit?page=${pageNumber}`);
  if (token !== state.platformLoadToken) return;
  setList('#platform-support-audit', (page.content || []).map((event) => item(
    `${event.ticketReference} · ${when(event.occurredAt)}`,
    `Assessment ${event.assessmentId} · By ${event.actorUserId} · ${event.reason}`)),
  'No support previews recorded for this organization.');
  renderPageControls('#platform-support-audit-pagination', page,
    (nextPage) => loadPlatformSupportAudit(id, nextPage, token));
}
function ensureTenantSupportAuditPanel() {
  if ($('#tenant-support-audit-panel')) return;
  const panel = node('div', '', 'card panel');
  panel.id = 'tenant-support-audit-panel';
  panel.innerHTML = `<div class="section-heading"><div><h2>Support access history</h2>
    <p class="muted">Each time a platform admin looked at this workspace's test details, it is listed here. Nobody can remove an entry.</p></div>
    <button id="tenant-support-audit-refresh" class="button quiet small" type="button">Reload</button></div>
    <div id="tenant-support-audit" class="item-list"></div><div id="tenant-support-audit-pagination"></div>`;
  $('#overview-view').append(panel);
  $('#tenant-support-audit-refresh').addEventListener('click', () => run(() =>
    loadList('#tenant-support-audit', () => loadTenantSupportAudit(0),
      'Platform support history could not be loaded.')));
}
async function loadTenantSupportAudit(pageNumber) {
  const id = state.organization?.id;
  if (!id || state.organization.myRole !== 'ORG_ADMIN') return;
  const page = await request(`/organizations/${encodeURIComponent(id)}/support/assessment-access/audit?page=${pageNumber}`);
  if (state.organization?.id !== id) return;
  setList('#tenant-support-audit', (page.content || []).map((event) => item(
    `${event.ticketReference} · ${when(event.occurredAt)}`,
    `Assessment ${event.assessmentId} · Platform admin ${event.actorUserId} · ${event.reason}`)),
  'No platform support access has been recorded for this workspace.');
  renderPageControls('#tenant-support-audit-pagination', page, loadTenantSupportAudit);
}
function formatBytes(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value < 0) return 'not reported';
  const mb = value / 1048576;
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${Math.round(value / 1024)} KB`;
}
async function loadPlatformStorage() {
  const storage = await request('/platform/operations/storage');
  const rows = [
    item(`${storage.warning ? 'Warning: ' : ''}Estimated logical size ${formatBytes(storage.estimatedLogicalBytes)}`,
      `Data ${formatBytes(storage.dataBytes)} · Indexes ${formatBytes(storage.indexBytes)}`
        + ` · measured ${storage.generatedAt ? new Date(storage.generatedAt).toLocaleString() : 'unknown'}`),
    item('Warning size you set', `${formatBytes(storage.warningThresholdBytes)} · set by ATLAS_STORAGE_WARNING_BYTES`)
  ];
  if (storage.warning) {
    rows.push(item('Over the warning size',
      'Use this as a reminder to clear out old data. It does not mean a limit has been passed.'));
  }
  setList('#platform-storage', rows, 'Storage metrics are unavailable.');
}
async function loadPlatformOperations() {
  const summary = await request('/platform/operations/summary');
  const tile = (label, count) => {
    const card = node('div', '', `platform-tile ${count > 0 ? 'is-warn' : 'is-ok'}`);
    card.append(node('strong', String(count ?? 0), 'platform-tile-value'), node('span', label, 'platform-tile-label'),
      node('span', count > 0 ? 'Needs a look' : 'All clear', 'platform-tile-flag'));
    return card;
  };
  const grid = node('div', '', 'platform-tiles');
  grid.append(
    tile('Failed emails', summary.failedEmailJobs),
    tile('Emails stuck for over 15 minutes', summary.overdueEmailJobs),
    tile('Practice payments left open for over 24 hours', summary.oldOpenSandboxOrders),
    tile('Confirmed payments not yet applied after 5 minutes', summary.verifiedButUnappliedOrders),
    tile('Number corrections that did not finish', summary.usageRepairRunsWithoutCompletion));
  const stamp = node('p', summary.generatedAt
    ? `${t('Counts generated')}: ${new Date(summary.generatedAt).toLocaleString()}` : 'Counts generated: not reported', 'muted');
  setList('#platform-operations', [grid, stamp], 'Operational counts are unavailable.');
}
async function loadPlatformOrganizations(pageNumber = 0) {
  const page = await request(`/platform/organizations?page=${pageNumber}`);
  state.platformOrganizationNames = Object.fromEntries((page.content || []).map((organization) => [organization.id, organization.name]));
  setList('#platform-organizations', (page.content || []).map((organization) => item(
    organization.name,
    `${organization.type} · ${organization.id} · Owner ${organization.ownerUserId || 'not recorded'}`
      + ` · Created ${organization.createdAt ? new Date(organization.createdAt).toLocaleDateString() : 'unknown'}`,
    [button('Inspect', () => inspectPlatformOrganization(organization.id))])),
  'No organizations found.');
  renderPageControls('#platform-organizations-pagination', page, loadPlatformOrganizations);
  attachFilter('#platform-organizations', 'Filter organizations by name, type, ID or owner');
}
async function inspectPlatformOrganization(organizationId) {
  const id = organizationId?.trim();
  if (!id) throw new Error('Choose an organization first.');
  const token = ++state.platformLoadToken;
  state.platformOrganizationId = id;
  state.platformOrganizationName = state.platformOrganizationNames?.[id] || null;
  $('#platform-organization-id').value = id;
  $('#platform-repair-usage').disabled = true;
  $('#platform-manual-revoke-submit').disabled = true;
  setList('#platform-support-preview', [], 'To look, you need a new ticket and must confirm that you understand.');
  for (const selector of ['#platform-billing', '#platform-orders', '#platform-usage-report', '#platform-usage-audit',
    '#platform-manual-plan', '#platform-manual-audit', '#platform-support-audit']) {
    beginList(selector);
  }
  try {
    await Promise.all([
      loadPlatformBilling(id, token), loadPlatformOrders(id, 0, token),
      loadPlatformUsageReport(id, token), loadPlatformUsageAudit(id, 0, token),
      loadPlatformManualPlan(id, token), loadPlatformManualPlanAudit(id, 0, token),
      loadPlatformSupportAudit(id, 0, token)
    ]);
  } finally {
    if (token === state.platformLoadToken) $('#platform-repair-usage').disabled = false;
  }
}
async function loadPlatformBilling(id, token) {
  const billing = await request(`/platform/organizations/${encodeURIComponent(id)}/billing`);
  if (token !== state.platformLoadToken) return;
  setList('#platform-billing', billing.subscriptionPresent ? [
    item(`${billing.tier} · ${billing.status}`,
      `Period ends ${billing.currentPeriodEnd ? new Date(billing.currentPeriodEnd).toLocaleString() : 'not set'}`
        + ` · Subscription updated ${billing.updatedAt ? new Date(billing.updatedAt).toLocaleString() : 'unknown'}`),
    item('Last paid order', billing.lastPaidOrderId || 'None')
  ] : [item('No subscription record', 'The plan stays FREE until a practice payment is confirmed and applied.')],
  'No billing data.');
}
async function loadPlatformOrders(id, pageNumber, token) {
  const page = await request(`/platform/organizations/${encodeURIComponent(id)}/billing/orders?page=${pageNumber}`);
  if (token !== state.platformLoadToken) return;
  setList('#platform-orders', (page.content || []).map((order) => item(
    `${order.provider} · ${order.status} · ৳${order.amountBdt}`,
    `${order.id} · ${new Date(order.createdAt).toLocaleString()}`)), 'No demo orders found.');
  renderPageControls('#platform-orders-pagination', page,
    (nextPage) => loadPlatformOrders(id, nextPage, token));
}
// Tenant-scoped quota reconciliation. The platform admin has a wider version
// of this on PlatformAdminController with an audit trail; this one is scoped to
// the caller's own organization and reports only its own counters.
// What each server status means for a person, in everyday words: [colour, label, sentence].
const USAGE_STATUS = {
  IN_SYNC: ['ok', 'OK', 'Looks right.'],
  MATCHES: ['ok', 'OK', 'Looks right.'],
  BACKFILLED: ['ok', 'Corrected', 'The number was corrected.'],
  BACKFILL_REQUIRED: ['warn', 'Fix needed', 'This number looks too low. Press Correct the numbers.'],
  STRANDED: ['warn', 'Fix needed', 'This number looks too high. Press Correct the numbers.'],
  STRANDED_RELEASED: ['warn', 'Fix needed', 'This number looks too high. Press Correct the numbers.'],
  AMBIGUOUS: ['warn', 'Ask support', 'We cannot tell what is right. Please contact support.'],
  DEFERRED_RECENT_ACTIVITY: ['warn', 'Check later', 'Something just changed. Check again in a few minutes.'],
  DEFERRED_NO_ACTIVITY_MARKER: ['warn', 'Not sure', 'Some old records are missing, so we are not sure of this number.'],
  DEFERRED_UNDATED_RECORDS: ['warn', 'Not sure', 'Some old records are missing, so we are not sure of this number.'],
  DEFERRED_EXPIRED_INVITATIONS: ['warn', 'Not sure',
    'Old invitations were deleted, so we are not sure of this number. The real number may be a little higher.'],
  CONCURRENT_UPDATE: ['warn', 'Check again', 'Someone else changed this at the same time. Check again.'],
  DEFERRED_ERROR: ['danger', 'Check failed', 'Something went wrong while checking. Try again.'],
  OUTCOME_UNCONFIRMED: ['danger', 'Check again', 'We could not confirm the last correction. Press Check again.']
};
const USAGE_METRICS = {
  PUBLISHED_ASSESSMENT: 'Published tests', INVITATION: 'Invitations sent', CERTIFICATE: 'Certificates issued'
};
function usageWhen(window) {
  const month = /^(\d{4})-(\d{2})$/.exec(window || '');
  if (!month) return t('in total');
  return new Date(Date.UTC(Number(month[1]), Number(month[2]) - 1, 1))
    .toLocaleString(getLocale(), { month: 'long', year: 'numeric', timeZone: 'UTC' });
}
function renderUsageReport(report) {
  const entries = report.entries || [];
  $('#usage-generated').textContent = t('Checked {when} · {plan} plan',
    { when: when(report.generatedAt), plan: report.tier || '?' });
  setList('#usage-report', entries.map((entry) => {
    const [tone, label, sentence] = USAGE_STATUS[entry.status]
      || ['warn', 'Needs a look', 'This number may be wrong. Press Correct the numbers, or contact support.'];
    const metric = USAGE_METRICS[entry.metric] || String(entry.metric || '').replaceAll('_', ' ').toLowerCase();
    const row = item(
      `${t(metric)} · ${usageWhen(entry.window)}`,
      `${t('Used {used} of {limit}.', { used: entry.recordedUsage, limit: entry.planLimit })} ${t(sentence)}`);
    row.querySelector('.item-actions').append(node('span', t(label), `pill ${tone}`));
    return row;
  }), 'No quota discrepancies in the scanned windows.');

  const manual = $('#usage-manual');
  const steps = (report.manualSteps || []).map((step) => /invitation/i.test(step) && /TTL|expired/i.test(step)
    ? t('Invitations: old invitations were deleted, so we are not sure how many were sent this month. Do nothing unless the number looks wrong.')
    : step);
  manual.textContent = steps.length ? [t('We are not sure about this:'), ...steps].join('\n') : '';
  manual.classList.toggle('hidden', !steps.length);

  // Correcting is only offered when a number really is off.
  const FIXABLE = ['BACKFILL_REQUIRED', 'STRANDED', 'STRANDED_RELEASED'];
  const repairable = entries.some((entry) => FIXABLE.includes(entry.status) || (entry.status && !USAGE_STATUS[entry.status]));
  const repair = $('#usage-repair');
  repair.disabled = !repairable;
  repair.title = repairable ? 'Make the numbers match your records' : 'Nothing to correct';
}
async function loadUsageReconciliation() {
  renderUsageReport(await request(ownOrg('/billing/entitlements/usage-reconciliation')));
}
async function repairUsageReconciliation() {
  if (!await confirmDialog('Repair the quota counters for this workspace?\n\n'
      + 'This rewrites the recorded usage for the scanned windows to match what the stored records prove. '
      + 'It does not change your plan, issue a refund, or create a payment. '
      + 'The counters and the records are not updated together, so a report that comes back unconfirmed '
      + 'needs a fresh check rather than a second repair.')) return;
  const repair = $('#usage-repair');
  repair.disabled = true;
  const report = await request(ownOrg('/billing/entitlements/usage-reconciliation'), { method: 'POST' });
  renderUsageReport(report);
  notice(report.repaired
    ? 'Quota counters rewritten to match the stored records.'
    : 'The check ran and changed nothing. The counters already matched.');
}
async function loadPlatformUsageReport(id, token) {
  const report = await request(`/platform/organizations/${encodeURIComponent(id)}/billing/usage-reconciliation`);
  if (token !== state.platformLoadToken) return;
  setList('#platform-usage-report', (report.entries || []).map((entry) => item(
    `${entry.metric.replaceAll('_', ' ')} · ${entry.window} · ${entry.status.replaceAll('_', ' ')}`,
    `Recorded ${entry.recordedUsage}; proven ${entry.provenMinimumUsage}–${entry.provenMaximumUsage}; plan limit ${entry.planLimit}. ${entry.detail}`)),
  'No quota discrepancies in the scanned windows.');
  const manual = $('#platform-usage-manual');
  manual.textContent = (report.manualSteps || []).join('\n');
  manual.classList.toggle('hidden', !(report.manualSteps || []).length);
}
async function loadPlatformUsageAudit(id, pageNumber, token) {
  const page = await request(`/platform/organizations/${encodeURIComponent(id)}/billing/usage-reconciliation/audit?page=${pageNumber}`);
  if (token !== state.platformLoadToken) return;
  setList('#platform-usage-audit', (page.content || []).map((event) => item(
    `${event.stage.replaceAll('_', ' ')} · ${new Date(event.occurredAt).toLocaleString()}`,
    `Run ${event.runId} · Actor ${event.actorUserId}${event.changedBuckets == null ? '' : ` · ${event.changedBuckets} bucket(s) changed`}`)),
  'No platform repair runs recorded.');
  renderPageControls('#platform-usage-audit-pagination', page,
    (nextPage) => loadPlatformUsageAudit(id, nextPage, token));
}
async function inspectPlatformUser(userId) {
  const id = userId?.trim();
  if (!id) throw new Error('Enter an exact user ID first.');
  const token = ++state.platformUserLoadToken;
  // Clear the target first: a failed lookup must not leave the previous user's data on screen with
  // the role buttons still pointing at the new ID.
  state.platformTargetUserId = null;
  state.platformTargetUser = null;
  $('#platform-grant-role').disabled = true;
  $('#platform-revoke-role').disabled = true;
  beginList('#platform-user-summary');
  beginList('#platform-role-audit');
  try {
    const [user, role] = await Promise.all([
      request(`/platform/users/${encodeURIComponent(id)}`),
      request(`/platform/users/${encodeURIComponent(id)}/role`)
    ]);
    if (token !== state.platformUserLoadToken) return;
    state.platformTargetUserId = id;
    state.platformTargetUser = {
      id: user.id, displayName: user.displayName, email: user.email,
      platformAdmin: role.platformAdmin, bootstrapAdmin: role.bootstrapAdmin
    };
    $('#platform-user-id').value = id;
    setList('#platform-user-summary', [
      item(user.displayName, `${user.email} · ${user.id}`),
      item(role.platformAdmin ? 'Platform administrator' : 'No platform role',
        `${user.emailVerified ? 'Verified' : 'Unverified'} · ${user.disabled ? 'Disabled' : 'Active'}${role.bootstrapAdmin ? ' · Environment bootstrap' : ''}`)
    ], 'No user selected.');
    const canChange = id !== state.platformUserId && !role.bootstrapAdmin;
    $('#platform-grant-role').disabled = !canChange || role.platformAdmin || !user.emailVerified || user.disabled;
    $('#platform-revoke-role').disabled = !canChange || !role.platformAdmin;
    await loadPlatformRoleAudit(id, 0, token);
  } catch (error) {
    if (token === state.platformUserLoadToken) {
      setList('#platform-user-summary', [], 'No user selected.');
      setList('#platform-role-audit', [], 'No role changes recorded.');
      $('#platform-role-audit-pagination').replaceChildren();
    }
    throw error;
  }
}
async function loadPlatformRoleAudit(id, pageNumber, token) {
  const page = await request(`/platform/users/${encodeURIComponent(id)}/role/audit?page=${pageNumber}`);
  if (token !== state.platformUserLoadToken) return;
  setList('#platform-role-audit', (page.content || []).map((event) => item(
    `${event.action} · revision ${event.revision}`,
    `Target ${event.targetUserId || id} · By ${event.actorUserId} · ${new Date(event.occurredAt).toLocaleString()}`)),
  'No role changes recorded.');
  renderPageControls('#platform-role-audit-pagination', page,
    (nextPage) => loadPlatformRoleAudit(id, nextPage, token));
}
async function changePlatformRole(grant) {
  const id = state.platformTargetUserId;
  const target = state.platformTargetUser;
  if (!id || $('#platform-user-id').value.trim() !== id)
    throw new Error('Look up the exact user ID first, then change the role.');
  const identity = target
    ? `${target.displayName} <${target.email}>\nUser ID ${target.id}`
    : `User ID ${id}`;
  const currentRole = target?.platformAdmin ? 'platform administrator' : 'no platform role';
  const nextRole = grant ? 'platform administrator' : 'no platform role';
  if (!await confirmDialog(`${grant ? 'Grant' : 'Revoke'} platform administrator access?\n\n${identity}\n\nRole changes from ${currentRole} to ${nextRole}. The change is audited and applies immediately.`)) return;
  $('#platform-grant-role').disabled = true;
  $('#platform-revoke-role').disabled = true;
  try {
    await request(`/platform/users/${encodeURIComponent(id)}/role`,
      { method: 'PUT', body: { platformAdmin: grant } });
    await inspectPlatformUser(id);
    notice(`Platform administrator access ${grant ? 'granted' : 'revoked'}.`);
  } catch (error) {
    $('#platform-grant-role').disabled = false;
    $('#platform-revoke-role').disabled = false;
    throw error;
  }
}
async function loadPlatformManualPlan(id, token) {
  const plan = await request(`/platform/organizations/${encodeURIComponent(id)}/billing/manual-plan`);
  if (token !== state.platformLoadToken) return;
  const active = !!plan.active;
  setList('#platform-manual-plan', [
    item(active ? 'Manual TEAM is active' : 'No active manual TEAM grant',
      `Latest action: ${plan.latestAction ? roleLabel(plan.latestAction) : 'none recorded'}`
        + ` · revision ${plan.revision}` + (plan.changedAt ? ` · changed ${when(plan.changedAt)}` : '')),
    item('Expires', active ? when(plan.expiresAt)
      : plan.expiresAt ? `${when(plan.expiresAt)} (recorded, not currently active)` : 'No grant recorded'),
    item('Manual grant — not a payment',
      'No money changes hands, no payment order is marked paid, and an active paid TEAM subscription always takes precedence.'),
    item('Organization', id)
  ], 'No manual plan state is available.');
  $('#platform-manual-revoke-submit').disabled = !active;
}
async function loadPlatformManualPlanAudit(id, pageNumber, token) {
  const page = await request(`/platform/organizations/${encodeURIComponent(id)}/billing/manual-plan/audit?page=${pageNumber}`);
  if (token !== state.platformLoadToken) return;
  setList('#platform-manual-audit', (page.content || []).map((event) => item(
    `${event.action} · revision ${event.revision} · ${when(event.occurredAt)}`,
    `By ${event.actorUserId} · Expires ${when(event.expiresAt)} · Reason: ${event.reason}`)),
  'No manual plan changes recorded.');
  renderPageControls('#platform-manual-audit-pagination', page,
    (nextPage) => loadPlatformManualPlanAudit(id, nextPage, token));
}
function manualPlanTarget() {
  const id = state.platformOrganizationId;
  if (!id || $('#platform-organization-id').value.trim() !== id)
    throw new Error('Inspect the organization before changing its manual plan.');
  return id;
}
function manualPlanReason(form) {
  const reason = String(new FormData(form).get('reason') || '').trim();
  if (reason.length < 10 || reason.length > 500) throw new Error('Write a reason between 10 and 500 characters.');
  return reason;
}
function manualPlanLabel(id) {
  return state.platformOrganizationName ? `"${state.platformOrganizationName}" (${id})` : id;
}
async function submitManualPlanGrant() {
  const id = manualPlanTarget();
  const form = $('#platform-manual-grant-form');
  const rawExpiry = String(new FormData(form).get('expiresAt') || '');
  if (!rawExpiry) throw new Error('Choose an expiry inside the next 30 days.');
  const expiresAt = new Date(rawExpiry);
  if (Number.isNaN(expiresAt.getTime())) throw new Error('Enter a valid expiry date and time.');
  if (expiresAt.getTime() <= Date.now()) throw new Error('The expiry must be in the future.');
  if (expiresAt.getTime() > Date.now() + 30 * 86400000) throw new Error('A manual TEAM grant must end within 30 days.');
  const reason = manualPlanReason(form);
  if (!await confirmDialog(`Grant a manual TEAM plan to ${manualPlanLabel(id)}?\n\nFree plan, not a payment. No money is charged, no order is marked paid, and an active paid subscription still takes precedence.\nExpires ${expiresAt.toLocaleString()}\nReason: ${reason}`)) return;
  const control = $('#platform-manual-grant-submit');
  control.disabled = true;
  try {
    await request(`/platform/organizations/${encodeURIComponent(id)}/billing/manual-plan`,
      { method: 'POST', body: { expiresAt: expiresAt.toISOString(), reason } });
    form.reset();
    notice('Manual TEAM grant recorded and audited. This is not a payment and no order was marked paid.');
    await Promise.all([
      loadPlatformManualPlan(id, state.platformLoadToken),
      loadPlatformManualPlanAudit(id, 0, state.platformLoadToken),
      loadPlatformBilling(id, state.platformLoadToken),
      loadPlatformOperations()
    ]);
  } catch (error) {
    fieldErrors(form, error);
    throw error;
  } finally { control.disabled = false; }
}
async function submitManualPlanRevoke() {
  const id = manualPlanTarget();
  const form = $('#platform-manual-revoke-form');
  const reason = manualPlanReason(form);
  if (!await confirmDialog(`Revoke the active manual TEAM grant for ${manualPlanLabel(id)}?\n\nRevoking is audited. It cannot revoke a paid subscription. This is a manual grant, not a payment.\nReason: ${reason}`)) return;
  const control = $('#platform-manual-revoke-submit');
  control.disabled = true;
  try {
    await request(`/platform/organizations/${encodeURIComponent(id)}/billing/manual-plan/revoke`,
      { method: 'POST', body: { reason } });
    form.reset();
    notice('Manual TEAM grant revoked and audited. Paid subscriptions were not touched.');
    await Promise.all([
      loadPlatformManualPlan(id, state.platformLoadToken),
      loadPlatformManualPlanAudit(id, 0, state.platformLoadToken),
      loadPlatformOperations()
    ]);
  } catch (error) {
    fieldErrors(form, error);
    throw error;
  } finally { control.disabled = false; }
}
async function loadOrganizationSettings() {
  const settings = await request(ownOrg('/settings'));
  $('#organization-settings-form').elements.defaultResultReleasePolicy.value = settings.defaultResultReleasePolicy;
  const defaultOption = $('#assessment-form').elements.resultReleasePolicy.options[0];
  defaultOption.textContent = `Use organization default (${settings.defaultResultReleasePolicy === 'MANUAL' ? 'after staff release' : 'on submission'})`;
}
function item(title, meta, actions = []) {
  const wrapper = node('div', '', 'item');
  const main = node('div', '', 'item-main');
  main.append(node('span', title, 'item-title'), node('p', meta, 'item-meta'));
  const controls = node('div', '', 'item-actions');
  controls.append(...actions);
  wrapper.append(main, controls);
  return wrapper;
}
function setList(selector, rows, emptyMessage) {
  const target = $(selector);
  target.setAttribute('aria-busy', 'false');
  target.replaceChildren(...(rows.length ? rows : [node('p', emptyMessage, 'empty')]));
}
function beginList(selector, message = 'Loading…') {
  const target = $(selector);
  if (!target) return;
  target.setAttribute('aria-busy', 'true');
  target.replaceChildren(node('p', message, 'empty'));
}
async function loadList(selector, load, retryMessage = 'This list could not be loaded.') {
  beginList(selector);
  try {
    await load();
  } catch (error) {
    const target = $(selector);
    if (!target) throw error;
    target.setAttribute('aria-busy', 'false');
    target.replaceChildren(
      node('p', `${retryMessage} ${error.message}`, 'empty'),
      button('Retry', () => loadList(selector, load, retryMessage), 'button secondary small'));
  }
}
function attachFilter(selector, placeholder) {
  const target = $(selector);
  if (!target) return;
  const host = target.parentElement;
  if (!host) return;
  const apply = (input) => {
    const term = input.value.trim().toLowerCase();
    for (const row of target.querySelectorAll('.item')) {
      row.classList.toggle('hidden', term.length > 0 && !(row.textContent || '').toLowerCase().includes(term));
    }
  };
  const existing = host.querySelector(`input[data-filter-for="${selector}"]`);
  if (existing) { apply(existing); return; }
  const label = node('label', '', 'filter-row');
  const input = node('input');
  input.type = 'search';
  input.placeholder = placeholder;
  input.setAttribute('aria-label', placeholder);
  input.setAttribute('data-filter-for', selector);
  label.append('Filter this page', input);
  host.insertBefore(label, target);
  input.addEventListener('input', () => apply(input));
}
function confirmDialog(message) {
  return new Promise((resolve) => {
    const backdrop = node('div', '', 'modal-backdrop');
    const dialog = node('div', '', 'modal');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    const lines = String(message).split('\n').map((line) => line.trim()).filter((line) => line.length > 0);
    const title = lines[0] || 'Please confirm';
    const heading = node('h2', title);
    heading.id = 'confirm-dialog-title';
    dialog.setAttribute('aria-labelledby', heading.id);
    dialog.append(heading);
    for (const line of lines.slice(1)) dialog.append(node('p', line, 'muted'));
    const actions = node('div', '', 'modal-actions');
    const cancel = node('button', 'Cancel', 'button quiet');
    const confirm = node('button', 'Confirm', 'button primary');
    cancel.type = 'button'; confirm.type = 'button';
    const verb = title.match(/^(Revoke|Release|Close|Deactivate|Reactivate|Publish|Delete|Remove|Transfer|Change|Submit|Grant|Mark|Repair)\b/);
    if (verb) confirm.textContent = verb[1];
    if (verb && ['Revoke', 'Deactivate', 'Close', 'Delete', 'Remove'].includes(verb[1])) confirm.classList.add('danger-action');
    actions.append(cancel, confirm);
    dialog.append(actions);
    backdrop.append(dialog);
    const previouslyFocused = document.activeElement;
    const onKeydown = (event) => {
      if (event.key === 'Escape') { event.preventDefault(); close(false); return; }
      if (event.key !== 'Tab') return;
      const focusable = [...dialog.querySelectorAll('button')];
      const first = focusable[0]; const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const close = (result) => {
      document.removeEventListener('keydown', onKeydown, true);
      backdrop.remove();
      if (previouslyFocused && typeof previouslyFocused.focus === 'function') previouslyFocused.focus();
      resolve(result);
    };
    cancel.addEventListener('click', () => close(false));
    confirm.addEventListener('click', () => close(true));
    backdrop.addEventListener('click', (event) => { if (event.target === backdrop) close(false); });
    document.addEventListener('keydown', onKeydown, true);
    document.body.append(backdrop);
    confirm.focus();
  });
}
function renderPageControls(selector, page, onPage) {
  const target = $(selector); target.replaceChildren();
  if ((page.totalPages || 0) <= 1) return;
  const controls = node('div', '', 'form-row');
  const previous = button('Previous', () => onPage(page.number - 1));
  const next = button('Next', () => onPage(page.number + 1));
  previous.disabled = page.first; next.disabled = page.last;
  controls.append(previous, node('span', `Page ${page.number + 1} of ${page.totalPages}`, 'muted'), next);
  target.append(controls);
}
async function loadJoinCodes(pageNumber = 0) {
  const page = await request(ownOrg(`/join-codes?page=${pageNumber}`));
  setList('#join-code-list', (page.content || []).map((entry) => {
    const status = entry.revokedAt ? 'Revoked' : new Date(entry.expiresAt) <= new Date() ? 'Expired' : 'Active';
    const actions = [];
    if (status === 'Active') actions.push(button('Revoke', async () => {
      if (!await confirmDialog('Revoke this join code? Anyone who has it will no longer be able to join.')) return;
      await request(ownOrg(`/join-codes/${encodeURIComponent(entry.id)}`), { method: 'DELETE' });
      await loadJoinCodes(page.number);
      notice('Join code revoked.');
    }));
    return item(`${entry.role.replaceAll('_', ' ')} · ${status}`,
      `Expires ${new Date(entry.expiresAt).toLocaleString()} · Joined ${entry.redemptions} time(s)`, actions);
  }), 'No join codes yet.');
  renderPageControls('#join-code-pagination', page, loadJoinCodes);
}
async function loadMembers(pageNumber = 0) {
  const page = await request(ownOrg(`/members?page=${pageNumber}`));
  const members = page.content || [];
  $('#ownership-panel').classList.toggle('hidden', !state.organization.owner);
  const allRoles = state.organization.type === 'ACADEMY' ? ['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER']
    : ['ORG_ADMIN', 'RECRUITER', 'CANDIDATE'];
  const roles = state.organization.owner ? allRoles : allRoles.filter((role) => role !== 'ORG_ADMIN');
  $('#member-role').replaceChildren(...roles.map((role) => { const option = node('option', role.replaceAll('_', ' ')); option.value = role; return option; }));
  $('#invited-member-role').replaceChildren(...roles.map((role) => {
    const option = node('option', role.replaceAll('_', ' ')); option.value = role; return option;
  }));
  setList('#members-list', members.map((member) => {
    const actions = [];
    if (!member.owner && (state.organization.owner || member.role !== 'ORG_ADMIN')) {
      const select = node('select');
      for (const role of roles) {
        const option = node('option', role.replaceAll('_', ' ')); option.value = role;
        select.append(option);
      }
      select.value = member.role;
      select.setAttribute('aria-label', `Role for ${member.displayName}`);
      actions.push(select);
      if (member.active) {
        const save = button('Save role', async () => {
          if (select.value === member.role) { notice('That is already the current role.'); return; }
          if (!await confirmDialog(`Change ${member.displayName} from ${roleLabel(member.role)} to ${roleLabel(select.value)}?`)) return;
          save.disabled = true;
          try {
            await request(ownOrg(`/members/${encodeURIComponent(member.userId)}/role`),
              { method: 'PUT', body: { role: select.value } });
            await loadMembers(page.number); notice('Member role updated.');
          } catch (error) { save.disabled = false; throw error; }
        });
        const deactivate = button('Deactivate', async () => {
          if (!await confirmDialog(`Deactivate ${member.displayName} from this workspace?\n\nThey lose access until an admin reactivates them.`)) return;
          deactivate.disabled = true;
          try {
            await request(ownOrg(`/members/${encodeURIComponent(member.userId)}`), { method: 'DELETE' });
            await loadMembers(page.number); notice('Member deactivated.');
          } catch (error) { deactivate.disabled = false; throw error; }
        });
        actions.push(save, deactivate);
      } else {
        const reactivate = button('Reactivate', async () => {
          if (!await confirmDialog(`Reactivate ${member.displayName} with the role ${roleLabel(select.value)}?`)) return;
          reactivate.disabled = true;
          try {
            await request(ownOrg('/members'), { method: 'POST',
              body: { email: member.email, role: select.value } });
            await loadMembers(page.number); notice('Member reactivated.');
          } catch (error) { reactivate.disabled = false; throw error; }
        });
        actions.push(reactivate);
      }
    }
    const auditPanel = node('div', '', 'item-list');
    actions.push(button('History', () => loadMemberAudit(member.userId, 0, auditPanel)));
    const wrapper = node('div', '', 'stack');
    wrapper.append(item(member.displayName,
      `${member.email} · ${member.owner ? 'Owner · ' : ''}${member.role.replaceAll('_', ' ')} · ${member.active ? 'Active' : 'Inactive'} · User ID: ${member.userId}`,
      actions), auditPanel);
    return wrapper;
  }), 'No members yet.');
  renderPageControls('#members-pagination', page, loadMembers);
  await loadMemberInvitations(0);
  await loadOwnershipAudit(0);
}
async function loadMemberInvitations(pageNumber = 0) {
  const page = await request(ownOrg(`/invitations?page=${pageNumber}`));
  setList('#member-invitations-list', (page.content || []).map((invitation) => {
    const expired = new Date(invitation.expiresAt) <= new Date();
    const status = invitation.accepted ? 'Accepted' : invitation.revoked ? 'Revoked'
      : expired ? 'Expired' : 'Pending';
    const canRevoke = !invitation.accepted && !invitation.revoked && !expired
      && (invitation.role !== 'ORG_ADMIN' || state.organization.owner);
    const actions = [];
    if (canRevoke) {
      const revoke = button('Revoke', async () => {
        if (!await confirmDialog(`Revoke the invitation for ${invitation.email}?\n\nThe link stops working immediately.`)) return;
        revoke.disabled = true;
        try {
          await request(ownOrg(`/invitations/${encodeURIComponent(invitation.id)}`), { method: 'DELETE' });
          await loadMemberInvitations(page.number);
          notice('Invitation revoked.');
        } catch (error) { revoke.disabled = false; throw error; }
      });
      actions.push(revoke);
    }
    return item(invitation.email,
      `${invitation.role.replaceAll('_', ' ')} · ${status} · Expires ${new Date(invitation.expiresAt).toLocaleString()}`,
      actions);
  }), 'No recent invitations.');
  renderPageControls('#member-invitations-pagination', page, loadMemberInvitations);
}
async function loadOwnershipAudit(pageNumber) {
  const page = await request(ownOrg(`/ownership/audit?page=${pageNumber}`));
  setList('#ownership-audit', (page.content || []).map((entry) => item(
    `Owner: ${entry.previousOwnerUserId} → ${entry.ownerUserId}`,
    `By ${entry.actorUserId} · ${new Date(entry.occurredAt).toLocaleString()}`)),
  'No ownership transfer has been recorded for this workspace. Transfers appear here with who made them and when.');
  renderPageControls('#ownership-audit-pagination', page, loadOwnershipAudit);
}
async function loadMemberAudit(userId, pageNumber, target) {
  const page = await request(ownOrg(`/members/${encodeURIComponent(userId)}/audit?page=${pageNumber}`));
  const rows = (page.content || []).map((entry) => item(entry.action.replaceAll('_', ' '),
    `${entry.previousRole || 'No previous role'} → ${entry.role} · By ${entry.actorUserId} · ${new Date(entry.occurredAt).toLocaleString()}`));
  target.replaceChildren(...(rows.length ? rows : [node('p', 'No role history yet.', 'muted')]));
  if ((page.totalPages || 0) > 1) {
    const controls = node('div', '', 'form-row');
    const previous = button('Previous', () => loadMemberAudit(userId, page.number - 1, target));
    const next = button('Next', () => loadMemberAudit(userId, page.number + 1, target));
    previous.disabled = page.first; next.disabled = page.last;
    controls.append(previous, node('span', `Page ${page.number + 1} of ${page.totalPages}`, 'muted'), next);
    target.append(controls);
  }
}
async function loadQuestions(pageNumber = 0) {
  const page = await request(ownOrg(`/questions?page=${pageNumber}`));
  state.questions = page.content || [];
  setList('#questions-list', state.questions.map((question) => item(question.prompt,
    `${question.topic} · ${question.type.replaceAll('_', ' ')} · ${question.difficulty || 'Unrated'} · ${question.status}`,
    question.status === 'DRAFT' ? [button('Edit draft', () => editQuestion(question)), button('Approve', async () => {
      await request(ownOrg(`/questions/${encodeURIComponent(question.id)}/approve`), { method: 'POST' });
      if (state.editingQuestion?.id === question.id) resetQuestionEditor();
      notice('Question approved.'); await loadQuestions(page.number);
      if (state.view === 'assessments') await loadApprovedQuestions();
    })] : [])), 'No questions yet.');
  renderPageControls('#questions-pagination', page, loadQuestions);
}
async function loadApprovedQuestions(pageNumber = 0) {
  const page = await request(ownOrg(`/questions?status=APPROVED&page=${pageNumber}`));
  const choices = (page.content || []).map((question) => {
    const label = node('label', '', 'check');
    const input = node('input'); input.type = 'checkbox'; input.name = 'questionId'; input.value = question.id;
    input.checked = state.selectedQuestionIds.has(question.id);
    input.addEventListener('change', () => {
      if (input.checked && state.selectedQuestionIds.size >= 100) {
        input.checked = false; notice('An assessment can contain at most 100 questions.', true); return;
      }
      if (input.checked) state.selectedQuestionIds.add(question.id);
      else state.selectedQuestionIds.delete(question.id);
      updateSelectedQuestionCount();
    });
    label.append(input, node('span', `${question.topic} · ${question.difficulty || 'Unrated'} — ${question.prompt}`)); return label;
  });
  setList('#assessment-questions', choices, 'Approve a question before creating an assessment.');
  renderPageControls('#assessment-questions-pagination', page, loadApprovedQuestions);
  updateSelectedQuestionCount();
}
function updateSelectedQuestionCount() {
  $('#selected-question-count').textContent = `${state.selectedQuestionIds.size} selected (maximum 100)`;
}
function updateAssessmentEditor() {
  const form = $('#assessment-form');
  const editing = state.editingAssessment;
  form.closest('.panel').querySelector('h2').textContent = editing
    ? `${editing.status === 'DRAFT' ? 'Edit draft' : 'Publish new version'} · ${editing.title}`
    : 'Create an assessment';
  form.querySelector('button[type="submit"]').textContent = editing
    ? (editing.status === 'DRAFT' ? 'Save draft changes' : 'Publish new version')
    : 'Create draft assessment';
  let cancel = form.querySelector('[data-cancel-assessment-edit]');
  if (!cancel) {
    cancel = button('Cancel editing', () => {
      state.editingAssessment = null; state.selectedQuestionIds.clear(); form.reset();
      updateAssessmentEditor(); return loadApprovedQuestions();
    }, 'button quiet');
    cancel.dataset.cancelAssessmentEdit = 'true';
    form.append(cancel);
  }
  cancel.classList.toggle('hidden', !editing);
  const defaultOption = form.elements.resultReleasePolicy.options[0];
  defaultOption.disabled = !!editing;
}
async function editAssessment(assessment) {
  const form = $('#assessment-form');
  state.editingAssessment = assessment;
  state.selectedQuestionIds = new Set(assessment.questionIds || []);
  form.elements.title.value = assessment.title;
  form.elements.instructions.value = assessment.instructions || '';
  form.elements.durationMinutes.value = assessment.durationMinutes;
  form.elements.passingPercentage.value = assessment.passingPercentage;
  form.elements.resultReleasePolicy.value = assessment.resultReleasePolicy || '';
  updateAssessmentEditor();
  await loadApprovedQuestions();
  form.scrollIntoView({ behavior: 'smooth', block: 'start' });
  form.elements.title.focus({ preventScroll: true });
}
async function loadAssessments(pageNumber = 0) {
  const page = await request(ownOrg(`/assessments?page=${pageNumber}`));
  setList('#assessments-list', (page.content || []).map((assessment) => {
    const actions = [];
    if (assessment.status === 'DRAFT') {
      actions.push(button('Edit draft', () => editAssessment(assessment)));
      const publish = button('Publish', async () => {
        if (!await confirmDialog(`Publish "${assessment.title}"?\n\nPublishing freezes this version with ${assessment.questionIds.length} question(s) and a ${assessment.durationMinutes} minute limit, and adds it to the published list. A published version cannot be edited.`)) return;
        publish.disabled = true;
        try {
          await request(ownOrg(`/assessments/${encodeURIComponent(assessment.id)}/publish`), { method: 'POST' });
          if (state.editingAssessment?.id === assessment.id) {
            state.editingAssessment = null; state.selectedQuestionIds.clear();
            $('#assessment-form').reset(); updateAssessmentEditor(); await loadApprovedQuestions();
          }
          notice('Assessment published. It can now be assigned.');
          await loadAssessments(page.number);
          await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
        } catch (error) { publish.disabled = false; throw error; }
      });
      actions.push(publish);
    } else if (assessment.status === 'PUBLISHED') {
      actions.push(button('New version', () => editAssessment(assessment)));
      // Only a published assessment has frozen versions to look at; a draft
      // has never been frozen, so the history would always be empty.
      actions.push(button('Version history', () => showAssessmentVersions(assessment), 'button quiet small'));
    }
    return item(assessment.title,
      `${assessment.status} · ${assessment.durationMinutes} min · ${assessment.questionIds.length} questions · Release: ${assessment.resultReleasePolicy || 'IMMEDIATE'}`,
      actions);
  }), 'No assessments yet.');
  renderPageControls('#assessments-pagination', page, loadAssessments);
  // The open history belongs to one assessment. If that assessment is no
  // longer on the page being shown, the panel would describe something the
  // person can no longer see, so it closes.
  if (state.versionsAssessment
      && !(page.content || []).some((assessment) => assessment.id === state.versionsAssessment.id)) {
    hideAssessmentVersions();
  }
}
function hideAssessmentVersions() {
  state.versionsAssessment = null;
  state.versionsToken += 1;
  $('#assessment-versions').classList.add('hidden');
}
async function showAssessmentVersions(assessment) {
  state.versionsAssessment = assessment;
  const panel = $('#assessment-versions');
  panel.classList.remove('hidden');
  $('#assessment-versions-for').textContent =
    `Published versions of "${assessment.title}". Each one is frozen: assignments keep the version they were created with.`;
  await loadAssessmentVersions(0);
  panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  $('#assessment-versions-close').focus({ preventScroll: true });
}
async function loadAssessmentVersions(pageNumber = 0) {
  const assessment = state.versionsAssessment;
  if (!assessment) return;
  // Switching assessments while a slower request is still in flight would
  // otherwise let the older response overwrite the newer list.
  state.versionsToken += 1;
  const token = state.versionsToken;
  await loadList('#assessment-versions-list', async () => {
    const page = await request(
      ownOrg(`/assessments/${encodeURIComponent(assessment.id)}/versions?page=${pageNumber}`));
    if (token !== state.versionsToken) return;
    setList('#assessment-versions-list', (page.content || []).map((version) => {
      const questionCount = (version.questions || []).length;
      const release = version.resultReleasePolicy || 'IMMEDIATE';
      const row = item(`Version ${version.version}`,
        `${questionCount} question${questionCount === 1 ? '' : 's'} · ${version.durationMinutes} min`
          + ` · pass ${version.passingPercentage}% · release ${release}`
          + ` · published ${when(version.publishedAt)}`);
      if (version.version === assessment.latestVersion) {
        row.querySelector('.item-actions').append(node('span', 'Current', 'pill ok'));
      }
      const topics = [...new Set((version.questions || [])
        .map((question) => question.topic).filter(Boolean))];
      if (topics.length) {
        row.querySelector('.item-main')
          .append(node('p', `Topics: ${topics.join(', ')}`, 'item-meta'));
      }
      return row;
    }), 'This assessment has no published versions yet.');
    renderPageControls('#assessment-versions-pagination', page, loadAssessmentVersions);
  }, 'The version history could not be loaded.');
}
async function loadPublishedAssessments(selectSelector, paginationSelector, pageNumber = 0) {
  const page = await request(ownOrg(`/assessments?status=PUBLISHED&page=${pageNumber}`));
  const select = $(selectSelector);
  const previousSelection = select.value;
  select.replaceChildren(...(page.content || []).map((assessment) => {
    const option = node('option', assessment.title); option.value = assessment.id; return option;
  }));
  if ([...select.options].some((option) => option.value === previousSelection)) select.value = previousSelection;
  renderPageControls(paginationSelector, page,
    (nextPage) => loadPublishedAssessments(selectSelector, paginationSelector, nextPage));
}
async function loadReviewQueue(pageNumber) {
  const page = await request(ownOrg(`/attempts/review/pending?page=${pageNumber}`));
  const rows = (page.content || []).map((attempt) => {
    const actions = [button('Inspect answers', () => showStaffReview(attempt.attemptId))];
    if (attempt.percentage != null) {
      const release = button('Release result', async () => {
        const score = `${attempt.percentage}% · ${attempt.passed ? 'Passed' : 'Not passed'}`;
        if (!await confirmDialog(`Release this result to the participant?\n\n${attempt.assessmentTitle}\n${score}\nAttempt ${attempt.attemptId}\n\nReleasing queues a result email to the participant.`)) return;
        release.disabled = true;
        try {
          await request(ownOrg(`/attempts/${encodeURIComponent(attempt.attemptId)}/release`), { method: 'POST' });
          if (state.activeReviewAttemptId === attempt.attemptId) await showStaffReview(attempt.attemptId);
          notice('Result released. A result email is queued for the participant.');
          await loadReviewQueue(page.number);
        } catch (error) { release.disabled = false; throw error; }
      });
      actions.push(release);
    }
    return item(`${attempt.assessmentTitle} — ${participantName(attempt)}`,
      `${participantIdNote(attempt)} · Attempt ${attempt.attemptNumber} · ${attempt.percentage == null ? 'Manual grading pending' : `${attempt.percentage}% · ${attempt.passed ? 'Passed' : 'Not passed'}`} · Submitted ${new Date(attempt.submittedAt).toLocaleString()}`,
      actions);
  });
  setList('#review-queue', rows, 'No results are waiting for release. A submitted attempt appears here when it needs a grade, or when its assessment releases results only after staff say so.');
  $('#review-page-label').textContent = `Page ${page.number + 1} of ${Math.max(1, page.totalPages)}`;
  $('#review-prev').disabled = page.first;
  $('#review-next').disabled = page.last;
  $('#review-prev').onclick = () => run(() => loadReviewQueue(page.number - 1));
  $('#review-next').onclick = () => run(() => loadReviewQueue(page.number + 1));
}
async function showStaffReview(attemptId) {
  const organizationId = state.organization.id;
  const loadToken = ++state.reviewLoadToken;
  const review = await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/review`));
  if (state.organization?.id !== organizationId || loadToken !== state.reviewLoadToken) return;
  state.activeReviewAttemptId = review.attemptId;
  $('#review-attempt-id').value = review.attemptId;
  if ($('#feedback-form').elements.attemptId.value !== review.attemptId) {
    $('#feedback-audit-list')?.replaceChildren();
    $('#feedback-audit-pagination')?.replaceChildren();
  }
  $('#feedback-form').elements.attemptId.value = review.attemptId;
  $('#attempt-review-summary').textContent = `${review.assessmentTitle} · ${participantName(review)} · ${participantIdNote(review)} · ${review.percentage == null ? 'Score pending' : `${review.percentage}% · ${review.passed ? 'Passed' : 'Not passed'}`} · ${review.resultReleasedAt ? 'Released' : 'Not released'}`;
  const answerText = (question, ids) => {
    const selected = question.options.filter((option) => (ids || []).includes(option.id))
      .map((option) => `${option.id}: ${option.text}`);
    return selected.length ? selected.join('; ') : 'No answer';
  };
  setList('#attempt-review-questions', review.questions.map((question) => {
    if (question.type === 'SHORT_ANSWER') {
      const grade = question.correct == null ? 'Awaiting grade' : question.correct ? 'Correct' : 'Incorrect';
      const actions = question.textAnswer && question.correct == null && !review.resultReleasedAt
        ? [gradeControl(review.attemptId, question.questionId, true),
          gradeControl(review.attemptId, question.questionId, false)] : [];
      return item(question.prompt,
        `${question.topic} · ${grade} · Answer: ${question.textAnswer || 'No answer'}${question.gradedByUserId ? ` · Graded by ${question.gradedByUserId}` : ''}`, actions);
    }
    return item(question.prompt,
      `${question.topic} · ${question.correct ? 'Correct' : 'Incorrect'} · Chosen: ${answerText(question, question.selectedOptionIds)} · Answer key: ${answerText(question, question.correctOptionIds)}`);
  }),
  'No questions found in this published version.');
  const hasShortAnswers = review.questions.some((question) => question.type === 'SHORT_ANSWER');
  $('#manual-grade-audit-panel').classList.toggle('hidden', !hasShortAnswers);
  if (hasShortAnswers) await loadManualGradeAudit(review.attemptId);
  $('#attempt-review-detail').classList.remove('hidden');
  $('#attempt-review-detail').scrollIntoView({ behavior: 'smooth' });
}
async function loadManualGradeAudit(attemptId, pageNumber = 0) {
  const page = await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/review/grades/audit?page=${pageNumber}`));
  if (state.activeReviewAttemptId !== attemptId) return;
  setList('#manual-grade-audit', (page.content || []).map((entry) => item(
    `Question ${entry.questionId} · ${entry.correct ? 'Correct' : 'Incorrect'}`,
    `Graded by ${actorName(entry)} · ${actorIdNote(entry)} · ${new Date(entry.gradedAt).toLocaleString()}`)),
  'No answers graded yet.');
  renderPageControls('#manual-grade-audit-pagination', page,
    (nextPage) => loadManualGradeAudit(attemptId, nextPage));
}
function gradeControl(attemptId, questionId, correct) {
  const control = button(correct ? 'Mark correct' : 'Mark incorrect', async () => {
    if (!await confirmDialog(`Mark this answer ${correct ? 'correct' : 'incorrect'}?\n\nGrading is final and audited, and it changes the official score.`)) return;
    control.disabled = true;
    try {
      await gradeShortAnswer(attemptId, questionId, correct);
    } catch (error) {
      control.disabled = false;
      throw error;
    }
  });
  return control;
}
async function gradeShortAnswer(attemptId, questionId, correct) {
  await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/review/questions/${encodeURIComponent(questionId)}/grade`),
    { method: 'PUT', body: { correct } });
  await showStaffReview(attemptId);
  await loadReviewQueue(0);
  notice('Short answer graded.');
}
async function loadCampaigns(pageNumber = 0) {
  const page = await request(ownOrg(`/talent/campaigns?page=${pageNumber}`));
  state.campaigns = page.content || [];
  setList('#campaigns-list', state.campaigns.map((campaign) => {
    const actions = [button('Open workspace', () => openCampaignWorkspace(campaign.id)),
      button('Compare candidates', () => compareCandidates(campaign.id)),
      button('Hiring funnel', () => showTalentFunnel(campaign.id, campaign.title)),
      button('Email invitations', () => loadEmailInvitations(campaign.id, 0, campaign.title))];
    if (campaign.open) {
      const close = button('Close campaign', async () => {
        if (!await confirmDialog(`Close "${campaign.title}" to new invitations?\n\nExisting assignments stay usable until their own windows end.`)) return;
        close.disabled = true;
        try {
          await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaign.id)}/close`), { method: 'POST' });
          notice('Campaign closed.');
          await loadCampaigns(page.number);
        } catch (error) { close.disabled = false; throw error; }
      });
      actions.push(close);
    }
    return item(campaign.title,
      `${campaign.open ? 'Open for new invitations' : 'Closed to new invitations'}`
        + ` · Frozen version ${campaign.assessmentVersionId}`
        + (campaign.createdAt ? ` · Created ${new Date(campaign.createdAt).toLocaleDateString()}` : ''),
      actions);
  }), 'No campaigns yet.');
  renderPageControls('#campaigns-pagination', page, loadCampaigns);
  $('#invite-campaign').replaceChildren(...state.campaigns.filter((campaign) => campaign.open)
    .map((campaign) => { const option = node('option', campaign.title); option.value = campaign.id; return option; }));
  $('#email-invite-campaign').replaceChildren(...state.campaigns.filter((campaign) => campaign.open)
    .map((campaign) => { const option = node('option', campaign.title); option.value = campaign.id; return option; }));
}
// Bars for a list of [label, count, variant] stages. Each bar is the count as a share of `base`, and the
// number is printed beside it so the width is never the only way to read it.
function stageBars(stages, base) {
  const chart = node('div', '', 'funnel');
  for (const [label, value, variant] of stages) {
    const count = value ?? 0;
    const share = base ? Math.round((count / base) * 100) : 0;
    const row = node('div', '', 'funnel-row');
    const track = node('div', '', 'funnel-track');
    const fill = node('div', '', `funnel-fill ${variant || ''}`.trim());
    fill.style.width = `${base ? Math.min(100, Math.max(share, count ? 2 : 0)) : 0}%`;
    track.append(fill);
    row.append(node('span', label, 'funnel-label'), track,
      node('span', base ? `${count} · ${share}%` : String(count), 'funnel-value'));
    chart.append(row);
  }
  return chart;
}
async function showTalentFunnel(campaignId, campaignTitle) {
  $('#talent-funnel-panel').classList.remove('hidden');
  $('#talent-funnel-title').textContent = campaignTitle ? `Hiring funnel · ${campaignTitle}` : 'Hiring funnel';
  await loadList('#talent-funnel', async () => {
    const funnel = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/funnel`));
    const base = funnel.assignedCandidates || 0;
    const chart = stageBars([
      ['Email invitations', funnel.retainedEmailInvitations, ''],
      ['Accepted the invitation', funnel.retainedAcceptedEmailInvitations, ''],
      ['Got the test', funnel.assignedCandidates, 'funnel-base'],
      ['Submitted', funnel.submittedCandidates, ''],
      ['Graded', funnel.gradedCandidates, ''],
      ['Shortlisted', funnel.shortlistedCandidates, ''],
      ['Hired', funnel.hiredCandidates, 'funnel-pass']
    ], Math.max(base, funnel.retainedEmailInvitations || 0));
    const notes = node('div', '', 'funnel-notes');
    notes.append(node('p', `${funnel.rejectedCandidates ?? 0} candidate(s) were rejected.`, 'funnel-note'));
    notes.append(node('p', 'Email invitation counts only include invitations that have not expired yet, so they can look smaller than the number of people who got the test.', 'funnel-note'));
    if (!base) notes.append(node('p', 'No candidate has been given this test yet.', 'funnel-note warn-note'));
    chart.append(notes);
    setList('#talent-funnel', [chart], 'No funnel numbers yet.');
  }, 'The hiring funnel could not be loaded.');
  $('#talent-funnel-panel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function loadEmailInvitations(campaignId, pageNumber = 0, campaignTitle = null) {
  const organizationId = state.organization?.id;
  const loadToken = ++state.emailInvitationLoadToken;
  state.emailInvitationCampaignId = campaignId;
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/email-invitations?page=${pageNumber}`));
  if (loadToken !== state.emailInvitationLoadToken || organizationId !== state.organization?.id) return;
  if (campaignTitle) $('#email-invitations-title').textContent = `Email invitations · ${campaignTitle}`;
  setList('#email-invitations-list', (page.content || []).map((invitation) => {
    const status = invitation.accepted ? 'Accepted'
      : new Date(invitation.expiresAt) <= new Date() ? 'Expired' : 'Pending';
    return item(invitation.email,
      `${status} · Link expires ${new Date(invitation.expiresAt).toLocaleString()}`);
  }), 'No retained email invitations for this campaign.');
  renderPageControls('#email-invitations-pagination', page,
    (nextPage) => loadEmailInvitations(campaignId, nextPage));
  $('#email-invitations-panel').classList.remove('hidden');
}
async function loadTalentCandidates(pageNumber = 0) {
  const page = await request(ownOrg(`/members/candidates?page=${pageNumber}`));
  const select = $('#invite-candidate');
  const previousSelection = select.value;
  select.replaceChildren(...(page.content || []).map((candidate) => {
    const option = node('option', `${candidate.displayName} · ${candidate.email}`);
    option.value = candidate.userId; return option;
  }));
  if ([...select.options].some((option) => option.value === previousSelection))
    select.value = previousSelection;
  if (!select.options.length) {
    const empty = node('option', 'No candidates on this page');
    empty.value = ''; empty.disabled = true;
    select.append(empty);
  }
  renderPageControls('#invite-candidate-pagination', page, loadTalentCandidates);
}
$('#candidate-lookup-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const email = new FormData(event.currentTarget).get('email').trim();
  const candidate = await request(ownOrg(`/members/candidates/lookup?email=${encodeURIComponent(email)}`));
  const option = node('option', `${candidate.displayName} · ${candidate.email}`);
  option.value = candidate.userId;
  $('#invite-candidate').replaceChildren(option);
  $('#invite-candidate-pagination').replaceChildren();
  notice('Candidate found in this organization.');
}); });
$('#candidate-lookup-clear').addEventListener('click', () => run(async () => {
  $('#candidate-lookup-form').reset();
  await loadTalentCandidates();
}));
async function loadIntegrityEvents(attemptId, pageNumber, target) {
  const page = await request(ownOrg(`/talent/attempts/${encodeURIComponent(attemptId)}/integrity/events?page=${pageNumber}`));
  const rows = (page.content || []).map((event) => node('p',
    `${event.type.replaceAll('_', ' ').toLowerCase()}${event.questionId ? ` · question ${event.questionId}` : ''} · ${new Date(event.receivedAt).toLocaleString()}`,
    'item-meta'));
  target.replaceChildren(node('p',
    `${page.totalElements} recorded event(s) — these signals are client-reported, cannot be verified, and are not proof of misconduct.`,
    'muted'), ...rows);
  if (!rows.length) target.append(node('p', 'No raw events retained.', 'item-meta'));
  const pager = node('div', '', 'item-actions');
  const previous = button('Previous', () => loadIntegrityEvents(attemptId, page.number - 1, target));
  const next = button('Next', () => loadIntegrityEvents(attemptId, page.number + 1, target));
  previous.disabled = page.first; next.disabled = page.last;
  pager.append(previous, next);
  target.append(pager);
}
function talentCandidateName(candidate) {
  return candidate.candidateDisplayName?.trim() || `Candidate ${candidate.candidateUserId}`;
}
async function compareCandidates(campaignId, pageNumber = 0) {
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates?page=${pageNumber}`));
  const rows = (page.content || []).map((candidate) => {
    const info = item(talentCandidateName(candidate),
      candidate.attemptId
        ? `Latest attempt: ${candidate.attemptId} · ${candidate.percentage == null ? 'Awaiting manual grading · Review unavailable' : `${candidate.percentage}% · ${candidate.passed ? 'Passed' : 'Not passed'} · ${candidate.decision || 'Not reviewed'}`}`
        : 'No submitted attempt yet.');
    if (!candidate.attemptId) return info;
    let reviewForm = null;
    if (candidate.percentage != null) {
      reviewForm = node('form', '', 'form-row');
      const decisionLabel = node('label', 'Decision'); const select = node('select');
      for (const decision of ['HOLD', 'SHORTLISTED', 'HIRED', 'REJECTED']) {
        const option = node('option', decision.replaceAll('_', ' ')); option.value = decision;
        select.append(option);
      }
      select.value = candidate.decision || 'HOLD'; decisionLabel.append(select);
      const noteLabel = node('label', 'Reviewer note'); const noteInput = node('input');
      noteInput.maxLength = 1000; noteInput.value = candidate.reviewerNote || '';
      noteLabel.append(noteInput);
      const save = node('button', 'Save review', 'button secondary small'); save.type = 'submit';
      reviewForm.append(decisionLabel, noteLabel, save);
      reviewForm.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
        await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates/${encodeURIComponent(candidate.candidateUserId)}/review`),
          { method: 'PUT', body: { attemptId: candidate.attemptId, decision: select.value, note: noteInput.value } });
        notice('Review saved.'); await compareCandidates(campaignId, page.number);
      }); });
    }
    const integrityPanel = node('div', '', 'item-list');
    const integrityButton = button('Integrity signals', async () => {
      const report = await request(ownOrg(`/talent/attempts/${encodeURIComponent(candidate.attemptId)}/integrity`));
      const counts = Object.entries(report.eventCounts || {})
        .map(([type, count]) => `${type.replaceAll('_', ' ').toLowerCase()}: ${count}`).join(' · ');
      integrityPanel.replaceChildren(
        node('p', `${report.totalEvents} recorded events · ${report.tabSwitchCount || 0} tab leaves · ${report.clientReportedRapidAnswerIntervals} rapid answer intervals`, 'item-meta'),
        node('p', counts || 'No signals recorded.'),
        node('p', report.tabSwitchLimitReached
          ? 'Three tab leaves reached; review the attempt before making a decision.'
          : report.interpretation, 'muted'));
    });
    const eventsPanel = node('div', '', 'item-list');
    const eventsButton = button('Event log', () => loadIntegrityEvents(candidate.attemptId, 0, eventsPanel));
    const auditPanel = node('div', '', 'item-list');
    const auditButton = button('Review history', () => run(() => loadReviewAudit(
      campaignId, candidate.candidateUserId, 0, auditPanel)));
    const wrapper = node('div', '', 'card panel');
    wrapper.append(info);
    if (reviewForm) wrapper.append(reviewForm);
    wrapper.append(integrityButton, integrityPanel, eventsButton, eventsPanel, auditButton, auditPanel); return wrapper;
  });
  const summary = node('div', '', 'card panel');
  summary.append(node('h3', 'At a glance'),
    node('p', 'Latest submitted attempt per invited candidate. Decisions are visible to Talent staff only.', 'muted'));
  const head = node('div', '', 'compare-row compare-head');
  head.append(node('span', 'Candidate'), node('span', 'Latest score'),
    node('span', 'Outcome'), node('span', 'Decision'));
  summary.append(head);
  for (const candidate of page.content || []) {
    const row = node('div', '', 'compare-row');
    row.append(node('span', talentCandidateName(candidate)));

    // Comparing is this screen's whole job, so the score gets a bar as well as
    // a number. A candidate with no submitted attempt, or one not yet graded,
    // gets no bar at all rather than a zero-width one that reads as a zero.
    const scoreCell = node('div', '', 'score-cell');
    if (candidate.attemptId && candidate.percentage != null) {
      const bar = node('div', '', 'bar score-bar');
      const fill = node('div', '', `bar-fill ${candidate.passed ? 'bar-pass' : 'bar-fail'}`);
      fill.style.width = `${Math.max(0, Math.min(100, candidate.percentage))}%`;
      bar.append(fill);
      scoreCell.append(node('span', `${candidate.percentage}%`, 'score-figure'), bar);
    } else {
      scoreCell.append(node('span', candidate.attemptId ? 'Awaiting grading' : 'No submitted attempt', 'score-absent'));
    }
    row.append(scoreCell);

    const outcome = !candidate.attemptId ? ['Not attempted', 'pill']
      : candidate.percentage == null ? ['Awaiting grading', 'pill warn']
      : candidate.passed ? ['Passed', 'pill ok'] : ['Did not pass', 'pill danger'];
    const outcomeCell = node('span', '', 'compare-cell');
    outcomeCell.append(node('span', outcome[0], outcome[1]));
    row.append(outcomeCell);

    const decisionCell = node('span', '', 'compare-cell');
    decisionCell.append(candidate.decision
      ? node('span', roleLabel(candidate.decision), 'pill')
      : node('span', 'Not reviewed', 'score-absent'));
    row.append(decisionCell);
    summary.append(row);
  }
  rows.unshift(summary);
  setList('#campaign-candidates', rows, 'No candidates invited yet. Use “Invite a registered candidate” to add someone with an account, or send an email invitation to someone without one.');
  renderPageControls('#campaign-candidates-pagination', page,
    (nextPage) => compareCandidates(campaignId, nextPage));
  $('#campaign-candidates-panel').classList.remove('hidden');
  $('#campaign-candidates-panel').scrollIntoView({ behavior: 'smooth' });
}
async function loadReviewAudit(campaignId, candidateId, pageNumber, target) {
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates/${encodeURIComponent(candidateId)}/review/audit?page=${pageNumber}`));
  const rows = (page.content || []).map((entry) => item(
    `${entry.decision.replaceAll('_', ' ')} · ${new Date(entry.occurredAt).toLocaleString()}`,
    `Reviewer ${actorName(entry)} · ${actorIdNote(entry)} · Attempt ID: ${entry.attemptId} · ${entry.note || 'No note'}`));
  target.replaceChildren(...(rows.length ? rows : [node('p', 'No review history yet.', 'muted')]));
  const controls = node('div', '', 'form-row');
  const previous = button('Previous', () => run(() => loadReviewAudit(campaignId, candidateId, page.number - 1, target)));
  const next = button('Next', () => run(() => loadReviewAudit(campaignId, candidateId, page.number + 1, target)));
  previous.disabled = page.first; next.disabled = page.last;
  controls.append(previous, node('span', `Page ${page.number + 1} of ${Math.max(1, page.totalPages)}`, 'muted'), next);
  target.append(controls);
}
async function loadAssignments(pageNumber = 0) {
  const page = await request(ownOrg(`/assignments/mine?page=${pageNumber}`));
  const inProgress = pendingAttempt();
  const now = Date.now();
  // Two tests can share a title (the same test given in two rounds). Add the opening date so they can be told apart.
  const titleCount = new Map();
  for (const entry of page.content || []) titleCount.set(entry.assessmentTitle, (titleCount.get(entry.assessmentTitle) || 0) + 1);
  setList('#assignments-list', (page.content || []).map((assignment) => {
    const resumable = inProgress?.assignmentId === assignment.id;
    const from = Date.parse(assignment.availableFrom);
    const until = Date.parse(assignment.availableUntil);
    const state = now < from ? `Opens ${new Date(from).toLocaleString()}` : now >= until ? 'Closed' : 'Open now';
    const start = button(resumable ? 'Resume assessment' : 'Start / resume', () => startAttempt(assignment));
    if (state !== 'Open now') {
      start.disabled = true;
      start.title = state === 'Closed' ? 'The availability window for this assignment has ended.'
        : 'The availability window for this assignment has not opened yet.';
    }
    const origin = assignment.batchAssessmentId ? ' · Assigned through a batch'
      : assignment.campaignId ? ' · Assigned through a hiring campaign' : '';
    const duplicate = assignment.assessmentTitle && titleCount.get(assignment.assessmentTitle) > 1
      ? ` (${new Date(from).toLocaleDateString()})` : '';
    return item(assignment.assessmentTitle
      ? `${assignment.assessmentTitle}${duplicate}${resumable ? ' · attempt in progress' : ''}`
      : resumable ? 'Assessment assignment · attempt in progress' : 'Assessment assignment',
      `${state} · Available ${new Date(from).toLocaleString()} – ${new Date(until).toLocaleString()}`
        + ` · ${assignment.attemptsAllowed} attempt(s)${origin}`
        + (assignment.durationMinutes != null ? ` · ${assignment.durationMinutes} min` : ''),
      [button('Open assessment', () => openAssessmentDetail(assignment)),
        start, button('Attempt history', () => showHistory(assignment))]);
  }), 'No assignments yet.');
  renderPageControls('#assignments-pagination', page, loadAssignments);
}
async function showHistory(assignment) {
  const history = await request(ownOrg(`/assignments/${encodeURIComponent(assignment.id)}/attempts/mine`));
  const panel = $('#result-panel'); panel.replaceChildren(node('h2', 'Attempt history'));
  if (!history.length) panel.append(node('p', 'No attempts yet.', 'muted'));
  for (const attempt of history) {
    const submitted = attempt.status === 'SUBMITTED';
    const remaining = submitted ? 0 : Math.max(0, Math.ceil((Date.parse(attempt.deadline) - Date.now()) / 1000));
    const meta = `${attempt.status} · ID: ${attempt.id}`
      + (submitted ? '' : ` · ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')} remaining`);
    const controls = submitted
      ? [button('View result', () => showResult(attempt.id))]
      : [button('Resume', () => startAttempt(assignment))];
    panel.append(item(`Attempt ${attempt.attemptNumber}`, meta, controls));
  }
  panel.classList.remove('hidden');
  focusPanel(panel);
}
function renderAttemptInstructions(assignment, target = '#attempt-panel', resultTarget = '#result-panel') {
  const panel = $(target); panel.replaceChildren(node('h2', 'Before you begin'));
  const rules = node('ul', '', 'attempt-rules');
  rules.append(
    node('li', `Available ${new Date(assignment.availableFrom).toLocaleString()} – ${new Date(assignment.availableUntil).toLocaleString()}.`),
    node('li', `${assignment.attemptsAllowed} attempt(s) allowed for this assignment.`),
    node('li', 'The timer is controlled by the server and starts when the attempt begins. It cannot be paused.'),
    node('li', 'If an attempt is already in progress, this continues that attempt instead of starting a new one.'),
    node('li', 'Save each answer before you submit. Only saved answers are counted.'),
    node('li', 'Answers cannot be changed after you submit.'));
  panel.append(node('p', 'Read these notes, then begin.', 'muted'), rules);
  panel.append(button('Begin attempt', () => beginAttempt(assignment.id, target, resultTarget), 'button primary'),
    button('Cancel', () => panel.classList.add('hidden'), 'button quiet'));
  panel.classList.remove('hidden');
  panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  focusPanel(panel);
}
async function startAttempt(assignment, target = '#attempt-panel', resultTarget = '#result-panel') {
  if (assignment?.availableFrom && assignment?.availableUntil) {
    renderAttemptInstructions(assignment, target, resultTarget); return;
  }
  await beginAttempt(assignment?.id || assignment, target, resultTarget);
}
async function beginAttempt(assignmentId, target = '#attempt-panel', resultTarget = '#result-panel') {
  setActiveAttempt(await request(ownOrg(`/assignments/${encodeURIComponent(assignmentId)}/attempts`), { method: 'POST' }));
  rememberAttempt(assignmentId, state.activeAttempt);
  $(resultTarget).classList.add('hidden');
  renderAttempt(target, resultTarget);
}
function talentAttemptActive() {
  return state.organization?.type === 'TALENT' && state.organization.myRole === 'CANDIDATE'
    && state.activeAttempt && Date.now() < Date.parse(state.activeAttempt.deadline);
}
async function sendTelemetry(type, questionId) {
  if (!talentAttemptActive()) return;
  const attemptId = state.activeAttempt.id;
  try {
    const response = await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/telemetry`),
      { method: 'POST', body: { type, ...(questionId ? { questionId } : {}) } });
    if (response?.warning) {
      notice(response.warningMessage || 'Exam tab switching has reached the review threshold.', true);
    }
  } catch (_) { /* Optional signals must never block the assessment. */ }
}
document.addEventListener('visibilitychange', () => {
  if (talentAttemptActive()) void sendTelemetry(document.hidden ? 'TAB_HIDDEN' : 'TAB_VISIBLE');
});
document.addEventListener('fullscreenchange', () => {
  if (state.attemptFullscreen && !document.fullscreenElement) {
    state.attemptFullscreen = false;
    void sendTelemetry('FULLSCREEN_EXIT');
  }
});
$('#attempt-panel').addEventListener('copy', () => { void sendTelemetry('COPY_ATTEMPT'); });
$('#attempt-panel').addEventListener('paste', () => { void sendTelemetry('PASTE_ATTEMPT'); });
const attemptAnswerState = new Map();
let attemptProgressElement = null;
function refreshAttemptProgress() {
  if (!attemptProgressElement) return;
  const total = state.activeAttempt?.questions?.length || 0;
  const saved = [...attemptAnswerState.values()].filter((value) => value === 'saved').length;
  attemptProgressElement.textContent = `${saved} of ${total} saved`;
}
function attemptHasExpired(attempt) {
  return Date.now() >= Date.parse(attempt.deadline);
}
async function saveAttemptAnswer(attempt, question, body, statusPill, control, savedText) {
  const path = savedText === undefined
    ? `/attempts/${encodeURIComponent(attempt.id)}/answers/${encodeURIComponent(question.id)}`
    : `/attempts/${encodeURIComponent(attempt.id)}/answers/${encodeURIComponent(question.id)}/text`;
  control.disabled = true;
  statusPill.textContent = 'Saving…';
  statusPill.className = 'pill';
  try {
    await request(ownOrg(path), { method: 'PUT', body });
    if (savedText === undefined) {
      attempt.savedAnswers = { ...(attempt.savedAnswers || {}), [question.id]: body.selectedOptionIds };
    } else {
      attempt.savedTextAnswers = { ...(attempt.savedTextAnswers || {}), [question.id]: savedText };
    }
    attemptAnswerState.set(question.id, 'saved');
    statusPill.textContent = 'Saved';
    statusPill.className = 'pill ok';
    refreshAttemptProgress();
    void sendTelemetry('ANSWER_SAVED', question.id);
    notice('Answer saved.');
  } catch (error) {
    attemptAnswerState.set(question.id, 'error');
    statusPill.textContent = 'Save failed';
    statusPill.className = 'pill danger';
    throw error;
  } finally {
    control.disabled = attemptHasExpired(attempt);
  }
}
function renderAttempt(target = '#attempt-panel', resultTarget = '#result-panel') {
  const attempt = state.activeAttempt;
  const panel = $(target);
  panel.replaceChildren(node('h2', 'Assessment in progress'));
  attemptAnswerState.clear();
  let submitControl = null;
  const announce = node('p', '', 'muted');
  announce.setAttribute('aria-live', 'polite');
  const timer = node('p', '', 'pill warn');
  timer.setAttribute('role', 'timer');
  let announced = null;
  const expireAttempt = () => {
    if (timer.dataset.expired === 'true') return;
    timer.dataset.expired = 'true';
    timer.className = 'pill danger';
    announce.textContent = 'Time is up. Answers can no longer be saved; submit to send the answers that are already saved.';
    for (const control of panel.querySelectorAll('[data-attempt-save]')) control.disabled = true;
    if (submitControl) submitControl.textContent = 'Submit assessment (time is up)';
    notice('Time is up. Submit to send the answers you already saved.', true);
  };
  const updateTimer = () => {
    const seconds = Math.max(0, Math.ceil((Date.parse(attempt.deadline) - Date.now()) / 1000));
    timer.textContent = `Time remaining: ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    if (seconds <= 0) { window.clearInterval(state.timer); expireAttempt(); return; }
    if (seconds <= 60 && announced !== 60) { announced = 60; announce.textContent = 'One minute remaining.'; }
    else if (seconds <= 300 && announced !== 300) { announced = 300; announce.textContent = 'Five minutes remaining.'; }
  };
  window.clearInterval(state.timer); updateTimer(); state.timer = window.setInterval(updateTimer, 1000);
  const progress = node('p', '', 'pill');
  attemptProgressElement = progress;
  const header = node('div', '', 'attempt-head');
  header.append(node('p', 'Save each answer before submitting. The timer is set by the server.', 'muted'), timer, progress, announce);
  panel.append(header);
  refreshAttemptProgress();
  if (talentAttemptActive() && document.fullscreenEnabled) {
    panel.append(button('Use fullscreen (optional)', async () => {
      await panel.requestFullscreen(); state.attemptFullscreen = true; focusPanel(panel);
    }));
  }
  const expired = attemptHasExpired(attempt);
  for (const [index, question] of attempt.questions.entries()) {
    const block = node('div', '', 'question-block');
    block.append(node('p', `${question.topic} · Question ${index + 1}`, 'eyebrow'), node('h3', question.prompt));
    const statusPill = node('span', 'Not saved', 'pill warn');
    const actions = node('div', '', 'question-actions');
    if (question.type === 'SHORT_ANSWER') {
      const answer = node('textarea'); answer.rows = 5; answer.maxLength = 4000;
      answer.setAttribute('aria-label', `Answer for question ${index + 1}`);
      answer.value = attempt.savedTextAnswers?.[question.id] || '';
      const stored = typeof attempt.savedTextAnswers?.[question.id] === 'string';
      if (stored) { statusPill.textContent = 'Saved'; statusPill.className = 'pill ok'; }
      attemptAnswerState.set(question.id, stored ? 'saved' : 'unsaved');
      const save = button('Save answer', async () => {
        const text = answer.value.trim();
        if (!text) throw new Error('Write an answer before saving.');
        await saveAttemptAnswer(attempt, question, { text }, statusPill, save, text);
      });
      save.dataset.attemptSave = 'true';
      save.disabled = expired;
      answer.addEventListener('input', () => {
        attemptAnswerState.set(question.id, 'unsaved');
        statusPill.textContent = 'Not saved'; statusPill.className = 'pill warn';
      });
      actions.append(save, statusPill);
      block.append(answer, actions);
      panel.append(block); continue;
    }
    const stored = Array.isArray(attempt.savedAnswers?.[question.id]);
    if (stored) { statusPill.textContent = 'Saved'; statusPill.className = 'pill ok'; }
    attemptAnswerState.set(question.id, stored ? 'saved' : 'unsaved');
    const saved = new Set(attempt.savedAnswers?.[question.id] || []);
    const choices = node('div', '', 'question-options');
    for (const option of question.options) {
      const label = node('label'); const input = node('input');
      input.type = question.type === 'SINGLE_CHOICE' ? 'radio' : 'checkbox';
      input.name = `answer-${question.id}`; input.value = option.id; input.checked = saved.has(option.id);
      label.append(input, node('span', option.text)); choices.append(label);
    }
    const save = button('Save answer', async () => {
      const selected = [...choices.querySelectorAll('input:checked')].map((input) => input.value);
      await saveAttemptAnswer(attempt, question, { selectedOptionIds: selected }, statusPill, save);
    });
    save.dataset.attemptSave = 'true';
    save.disabled = expired;
    for (const input of choices.querySelectorAll('input')) {
      input.addEventListener('change', () => {
        attemptAnswerState.set(question.id, 'unsaved');
        statusPill.textContent = 'Not saved'; statusPill.className = 'pill warn';
      });
    }
    actions.append(save, statusPill);
    block.append(choices, actions);
    panel.append(block);
  }
  submitControl = node('button', expired ? 'Submit assessment (time is up)' : 'Submit assessment', 'button primary');
  submitControl.type = 'button';
  submitControl.addEventListener('click', () => run(async () => {
    const unsaved = [...attemptAnswerState.values()].filter((value) => value !== 'saved').length;
    const warning = unsaved === 1
      ? '1 answer is not saved and will not be counted.'
      : `${unsaved} answers are not saved and will not be counted.`;
    const message = unsaved
      ? `Submit now? ${warning} Answers cannot be changed after submission.`
      : 'Submit now? Answers cannot be changed after submission.';
    if (!await confirmDialog(message)) return;
    submitControl.disabled = true;
    submitControl.textContent = 'Submitting…';
    try {
      const result = await request(ownOrg(`/attempts/${encodeURIComponent(attempt.id)}/submit`), { method: 'POST' });
      window.clearInterval(state.timer);
      setActiveAttempt(null);
      forgetAttempt();
      panel.classList.add('hidden');
      renderResult(result, resultTarget);
      notice('Assessment submitted.');
    } catch (error) {
      submitControl.disabled = false;
      submitControl.textContent = attemptHasExpired(attempt) ? 'Submit assessment (time is up)' : 'Submit assessment';
      throw error;
    }
  }));
  panel.append(submitControl);
  panel.classList.remove('hidden');
  panel.scrollIntoView({ behavior: 'smooth' });
  focusPanel(panel);
  if (expired) expireAttempt();
}
async function showResult(attemptId, target = '#result-panel') {
  renderResult(await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/result`)), target);
}
function renderResult(result, target = '#result-panel') {
  const panel = $(target); panel.replaceChildren(node('h2', 'Your result'));
  panel.append(node('p', `Attempt ID: ${result.attemptId}`, 'item-meta'));
  if (!result.released) {
    const message = result.state === 'GRADING_PENDING'
      ? 'Submitted. Manual grading is still in progress; your score is not available yet.'
      : result.state === 'RELEASE_PENDING'
        ? 'Submitted and graded. Staff have not released your result yet.'
        : 'Submitted. Your score stays hidden until grading and staff release are complete.';
    panel.append(node('p', message, 'muted'));
    panel.append(button('Check again', () => showResult(result.attemptId, target), 'button secondary'));
  } else {
    // A released result is the moment this product exists to deliver, so the
    // score leads rather than sitting in a heading among the metadata.
    const hero = node('div', '', `result-hero ${result.passed ? 'is-pass' : 'is-fail'}`);
    const figure = node('div', '', 'result-figure');
    figure.append(node('span', `${result.percentage}`, 'result-score'), node('span', '%', 'result-unit'));
    const verdict = node('div', '', 'result-verdict');
    verdict.append(node('span', result.passed ? 'Passed' : 'Did not pass',
      `pill ${result.passed ? 'ok' : 'danger'}`));
    verdict.append(node('p', [result.submittedAt ? `Submitted ${new Date(result.submittedAt).toLocaleString()}` : null,
      result.releasedAt ? `Released ${new Date(result.releasedAt).toLocaleString()}` : null]
      .filter(Boolean).join(' · '), 'result-stamps'));
    hero.append(figure, verdict);
    panel.append(hero);

    const topics = result.topics || [];
    if (topics.length) {
      panel.append(node('h3', 'By topic', 'result-section'));
      panel.append(node('p',
        'Each topic counts only the questions you were asked in it. A topic with gaps is where another attempt would help most.',
        'muted'));
      const list = node('div', '', 'topic-list');
      // Weakest first: the point of a topic breakdown is to find where to work,
      // and that is buried if a perfect topic sits at the top.
      for (const topic of [...topics].sort((a, b) =>
        (a.total ? a.correct / a.total : 0) - (b.total ? b.correct / b.total : 0))) {
        const share = topic.total ? Math.round((topic.correct / topic.total) * 100) : 0;
        const complete = topic.total > 0 && topic.correct === topic.total;
        const row = node('div', '', 'topic-row');
        const head = node('div', '', 'topic-head');
        head.append(node('span', topic.topic, 'topic-name'),
          node('span', `${topic.correct}/${topic.total}`, 'topic-count'));
        const bar = node('div', '', 'bar topic-bar');
        const fill = node('div', '', `bar-fill ${complete ? 'bar-pass' : 'bar-gap'}`);
        fill.style.width = `${share}%`;
        bar.append(fill);
        row.append(head, bar,
          node('p', complete ? `${share}% · every question correct`
            : `${share}% · ${topic.total - topic.correct} to revisit`, 'topic-note'));
        list.append(row);
      }
      panel.append(list);
    }
    if (result.passed && state.organization?.type === 'ACADEMY' && state.organization?.myRole === 'LEARNER') {
      panel.append(button('My certificates', () => showView('certificates'), 'button secondary'));
    }
    if (state.organization?.type === 'ACADEMY' && state.organization?.myRole === 'LEARNER') {
      panel.append(button('View instructor feedback', async () => {
        const feedback = await request(ownOrg(`/academy/attempts/${encodeURIComponent(result.attemptId)}/feedback/mine`),
          { optional404: true });
        const box = node('div', '', 'result-box');
        box.append(node('h3', 'Instructor feedback'));
        if (feedback) {
          // Who wrote it matters as much as what it says: a learner acts on feedback
          // differently depending on whether their instructor or an administrator left it.
          const author = (feedback.authorDisplayName || '').trim();
          box.append(node('p', author ? `From ${author}` : 'From your instructor', 'muted'),
            node('p', feedback.summary), node('h4', 'Next steps'), node('p', feedback.nextSteps));
        } else {
          box.append(node('p', 'Your instructor has not published feedback for this attempt yet.'));
        }
        panel.append(box);
      }));
    }
    if (['LEARNER', 'CANDIDATE'].includes(state.organization?.myRole)) {
      const form = node('form', '', 'stack'); form.append(node('h3', 'Ask the AI Tutor'));
      form.append(node('p', 'Ask follow-up questions here. The last three exchanges stay in this tab only; reloading or switching workspaces clears them.', 'muted'));
      let history = state.tutorHistory.get(result.attemptId) || [];
      const transcript = node('div', '', 'tutor-thread');
      transcript.setAttribute('role', 'log');
      transcript.setAttribute('aria-label', 'AI Tutor conversation');
      transcript.setAttribute('aria-live', 'polite');
      const addTurn = (turn) => {
        const item = node('div', '', `tutor-turn ${turn.role === 'LEARNER' ? 'tutor-learner' : 'tutor-reply'}`);
        item.append(node('strong', turn.role === 'LEARNER' ? 'You' : 'AI Tutor'), node('p', turn.content));
        transcript.append(item);
      };
      history.forEach(addTurn);
      form.append(transcript);
      const label = node('label', 'Your question'); const input = node('textarea');
      input.required = true; input.maxLength = 1200; input.rows = 2; label.append(input);
      const submit = node('button', 'Ask tutor', 'button secondary'); submit.type = 'submit';
      form.append(label, submit);
      form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
        const question = input.value.trim();
        if (!question || submit.disabled) return;
        submit.disabled = true;
        try {
          const reply = await request(ownOrg(`/attempts/${encodeURIComponent(result.attemptId)}/tutor`),
            { method: 'POST', body: { question, history: history.slice(-6).map((turn) => ({
              role: turn.role, content: turn.content.slice(0, 800)
            })) } });
          const next = [{ role: 'LEARNER', content: question },
            { role: 'TUTOR', content: reply.explanation }];
          next.forEach(addTurn);
          history = [...history, ...next].slice(-6);
          state.tutorHistory.set(result.attemptId, history);
          input.value = '';
        } catch (error) {
          const explanation = explainAiFailure(error);
          if (!explanation) throw error;
          notice(explanation, true);
        } finally {
          submit.disabled = false;
        }
      }); });
      panel.append(form);
    }
  }
  panel.classList.remove('hidden'); panel.scrollIntoView({ behavior: 'smooth' });
  focusPanel(panel);
}
// ---------------------------------------------------------------- Learner assessment detail
function assessmentDetailStale(token) {
  return token !== state.assessmentDetailToken || !state.organization;
}
function showAssessmentDetailEmpty(message) {
  state.detailAssignment = null;
  const empty = $('#assessment-detail-empty');
  empty.classList.remove('hidden');
  empty.querySelector('p').textContent = message;
  $('#assessment-detail-title').textContent = 'Assessment detail';
  $('#assessment-detail-subtitle').textContent = '';
  $('#assessment-detail-actions').replaceChildren(
    button('Open My assessments', () => showView('attempts'), 'button secondary small'));
  setList('#assessment-detail-summary', [], message);
  setList('#assessment-detail-attempts', [], message);
  $('#assessment-detail-live').classList.add('hidden');
  $('#assessment-detail-result').classList.add('hidden');
}
async function loadAssessmentDetail() {
  const token = ++state.assessmentDetailToken;
  for (const selector of ['#assessment-detail-live', '#assessment-detail-result']) {
    $(selector).classList.add('hidden');
    $(selector).replaceChildren();
  }
  await loadList('#assessment-detail-summary', async () => {
    let assignment = state.detailAssignment;
    if (!assignment && state.detailAssignmentId) {
      assignment = await request(ownOrg(`/assignments/${encodeURIComponent(state.detailAssignmentId)}`));
    } else if (!assignment) {
      const page = await request(ownOrg('/assignments/mine?page=0'));
      if (assessmentDetailStale(token)) return;
      assignment = (page.content || [])[0] || null;
      if (!assignment) {
        showAssessmentDetailEmpty('No assignment has been given to you in this workspace yet.');
        return;
      }
    }
    if (assessmentDetailStale(token)) return;
    state.detailAssignment = assignment;
    state.detailAssignmentId = assignment.id;
    await renderAssessmentDetail(assignment, token);
  }, 'The assignment could not be loaded.');
}
async function renderAssessmentDetail(assignment, token) {
  const from = Date.parse(assignment.availableFrom);
  const until = Date.parse(assignment.availableUntil);
  const availability = Date.now() < from ? `Opens ${new Date(from).toLocaleString()}`
    : Date.now() >= until ? 'Closed' : 'Open now';
  const origin = assignment.batchAssessmentId ? 'Assigned through a batch'
    : assignment.campaignId ? 'Assigned through a hiring campaign' : 'Assigned directly';
  $('#assessment-detail-empty').classList.add('hidden');
  $('#assessment-detail-title').textContent = assignment.assessmentTitle || 'Assessment detail';
  $('#assessment-detail-subtitle').textContent = `${availability} · ${origin} · Assignment ${assignment.id}`;
  const history = await request(ownOrg(`/assignments/${encodeURIComponent(assignment.id)}/attempts/mine`));
  if (assessmentDetailStale(token)) return;
  const used = history.length;
  const allowed = assignment.attemptsAllowed || 0;
  const remaining = Math.max(0, allowed - used);
  const instructionRow = item('Instructions', assignment.instructions?.trim()
    || 'Read each question carefully. Your timer starts when you begin, and the server enforces the deadline.');
  instructionRow.classList.add('assessment-instructions');
  setList('#assessment-detail-summary', [
    item('Availability window', `${when(assignment.availableFrom)} – ${when(assignment.availableUntil)}`),
    item('Right now', availability),
    item('Attempts', `${used} used of ${allowed} allowed · ${remaining} remaining`),
    item('Where it came from', origin),
    item('Assessment', assignment.assessmentTitle || 'Name unavailable'),
    instructionRow,
    item('Time limit', assignment.durationMinutes != null
      ? `${assignment.durationMinutes} minutes, controlled by the server` : 'Unavailable'),
    item('Questions', assignment.questionCount != null
      ? `${assignment.questionCount} question(s)` : 'Unavailable'),
    item('Assignment identifier', assignment.id)
  ], 'No assignment detail is available.');
  const start = button(remaining === 0 ? 'No attempts remaining' : 'Start an attempt',
    () => startAttempt(assignment, '#assessment-detail-live', '#assessment-detail-result'), 'button secondary');
  if (availability !== 'Open now' || remaining === 0) {
    start.disabled = true;
    start.title = availability === 'Closed' ? 'The availability window for this assignment has ended.'
      : availability !== 'Open now' ? 'The availability window for this assignment has not opened yet.'
      : 'Every allowed attempt has already been started or submitted.';
  }
  $('#assessment-detail-actions').replaceChildren(start);
  setList('#assessment-detail-attempts', history.map((attempt) => {
    const submitted = attempt.status === 'SUBMITTED';
    const seconds = submitted ? 0 : Math.max(0, Math.ceil((Date.parse(attempt.deadline) - Date.now()) / 1000));
    const controls = submitted
      ? [button('View result, feedback and tutor', () => showResult(attempt.id, '#assessment-detail-result'))]
      : [button('Resume this attempt', () => beginAttempt(assignment.id, '#assessment-detail-live', '#assessment-detail-result'))];
    return item(`Attempt ${attempt.attemptNumber} · ${attempt.status}`,
      submitted ? `Submitted ${when(attempt.submittedAt)}`
        : `Server deadline ${when(attempt.deadline)} · ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} remaining`,
      controls);
  }), 'No attempt has been started for this assignment yet.');
  const live = state.activeAttempt;
  if (live?.assignmentId === assignment.id && Date.now() < Date.parse(live.deadline)) {
    renderAttempt('#assessment-detail-live', '#assessment-detail-result');
    return;
  }
  const marker = pendingAttempt();
  if (marker?.assignmentId === assignment.id) {
    const panel = $('#assessment-detail-live');
    panel.classList.remove('hidden');
    panel.replaceChildren(node('h3', 'An attempt was in progress'),
      node('p', `Attempt ${marker.attemptId} was still open when this page was last used. Resuming continues that same attempt while its deadline has not passed. Once the deadline has passed the server finalizes it first, and a new attempt starts only if attempts remain.`, 'muted'),
      button('Resume now', () => beginAttempt(assignment.id, '#assessment-detail-live', '#assessment-detail-result'), 'button secondary'));
  }
}
function openAssessmentDetail(assignment) {
  state.detailAssignment = assignment || null;
  state.detailAssignmentId = assignment?.id || null;
  return showView('assessment-detail');
}
$('#assessment-detail-back').addEventListener('click', () => run(() => showView('attempts')));
$('#assessment-detail-open-list').addEventListener('click', () => run(() => showView('attempts')));

async function loadCertificates(pageNumber = 0) {
  const page = await request(ownOrg(`/certificates/mine?page=${pageNumber}`));
  setList('#certificates-list', (page.content || []).map((certificate) => item(
    certificate.status === 'ISSUED' ? 'Verified achievement' : 'Revoked certificate',
    `Code: ${certificate.verificationCode} · Issued ${new Date(certificate.issuedAt).toLocaleDateString()}`,
    [button('View & print', () => openPrivateCertificate(certificate.id)),
      button('Verify publicly', () => window.open(`/certificates/${encodeURIComponent(certificate.verificationCode)}`, '_blank', 'noopener'))])),
  'No certificates yet.');
  renderPageControls('#certificates-pagination', page, loadCertificates);
}

function renderCertificatePaper(selector, details) {
  const target = $(selector);
  target.setAttribute('aria-busy', 'false');
  const design = details.design || {};
  target.replaceChildren();
  target.classList.toggle('is-revoked', details.status === 'REVOKED');
  if (design.logoDataUrl) {
    const logo = node('img'); logo.src = design.logoDataUrl; logo.alt = `${details.organizationName} logo`;
    logo.className = 'certificate-logo'; target.append(logo);
  }
  target.append(node('p', details.organizationName, 'certificate-issuer'),
    node('h3', design.heading || 'Certificate of achievement', 'certificate-heading'),
    node('p', design.message || 'Has successfully completed the assessment', 'certificate-message'),
    node('p', details.holderName, 'certificate-holder'),
    node('p', details.assessmentTitle, 'certificate-assessment'));
  const footer = node('div', '', 'certificate-footer');
  const issued = node('div', '', 'certificate-date');
  issued.append(node('strong', new Date(details.issuedAt).toLocaleDateString()), node('span', 'Issued'));
  footer.append(issued);
  if (design.signatureDataUrl || design.signatoryName) {
    const signer = node('div', '', 'certificate-signer');
    if (design.signatureDataUrl) {
      const signature = node('img'); signature.src = design.signatureDataUrl;
      signature.alt = 'Authorized signature'; signer.append(signature);
    }
    signer.append(node('strong', design.signatoryName || 'Authorized signer'));
    footer.append(signer);
  }
  target.append(footer, node('p', `Verify: ${details.verificationCode}`, 'certificate-code'));
  if (details.status === 'REVOKED') target.append(node('p', 'Revoked — not a valid credential', 'certificate-revoked'));
}

async function openPrivateCertificate(id) {
  const certificate = await request(ownOrg(`/certificates/${encodeURIComponent(id)}/display`));
  renderCertificatePaper('#certificate-display', certificate);
  $('#certificate-display-panel').classList.remove('hidden');
  $('#certificate-display-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}
$('#certificate-print').addEventListener('click', () => window.print());

async function loadCertificateDesign() {
  const design = await request(ownOrg('/certificate-design'));
  state.certificateDesign = design;
  const form = $('#certificate-design-form');
  form.elements.heading.value = design.heading;
  form.elements.message.value = design.message;
  form.elements.signatoryName.value = design.signatoryName || '';
  form.elements.logo.value = '';
  form.elements.signature.value = '';
  form.elements.removeLogo.checked = false;
  form.elements.removeSignature.checked = false;
  $('#certificate-design-status').textContent = design.id
    ? 'Current design. Changes apply only to future certificates.'
    : 'Default design. Save to use your own wording and images for future certificates.';
  renderCertificatePaper('#certificate-design-preview', {
    organizationName: state.organization.name, holderName: 'Sample Learner',
    assessmentTitle: 'Sample assessment', issuedAt: new Date().toISOString(),
    verificationCode: 'PREVIEW ONLY', status: 'ISSUED', design
  });
}

async function certificateImageData(file) {
  if (!file) return null;
  if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 65536 || file.size === 0) {
    throw new Error('Choose a PNG or JPEG image no larger than 64 KiB.');
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 4096) {
    binary += String.fromCharCode(...bytes.slice(offset, offset + 4096));
  }
  return `data:${file.type};base64,${btoa(binary)}`;
}

$('#certificate-design-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = event.currentTarget;
  const save = form.querySelector('button[type="submit"]'); save.disabled = true;
  try {
    const values = new FormData(form);
    const logo = await certificateImageData(form.elements.logo.files[0]);
    const signature = await certificateImageData(form.elements.signature.files[0]);
    await request(ownOrg('/certificate-design'), { method: 'PUT', body: {
      heading: values.get('heading').trim(), message: values.get('message').trim(),
      signatoryName: values.get('signatoryName').trim(),
      logoDataUrl: form.elements.removeLogo.checked ? null : logo || state.certificateDesign?.logoDataUrl || null,
      signatureDataUrl: form.elements.removeSignature.checked ? null : signature || state.certificateDesign?.signatureDataUrl || null
    } });
    await loadCertificateDesign();
    notice('Certificate design saved for future certificates.');
  } catch (error) { fieldErrors(form, error); throw error; }
  finally { save.disabled = false; }
}); });

async function loadAcademyCatalog() {
  if (state.organization.myRole === 'LEARNER') {
    await loadMyBatches();
    return;
  }
  await loadPublishedAssessments('#batch-assessment-select', '#batch-assessment-select-pagination');
  await loadAcademyCourses();
}

async function loadMyBatches(pageNumber = 0) {
  const page = await request(ownOrg(`/academy/batches/mine?page=${pageNumber}`));
  setList('#my-batches-list', (page.content || []).map((enrollment) => item(
    `${enrollment.courseName} · ${enrollment.batchName}`,
    `Enrolled ${new Date(enrollment.enrolledAt).toLocaleDateString()}`)),
  'You are not enrolled in a batch yet. An instructor or the workspace owner enrols you. An assessment assigned to you directly still appears under My Assessments.');
  renderPageControls('#my-batches-pagination', page, loadMyBatches);
}

async function loadAcademyCourses(pageNumber = 0) {
  const page = await request(ownOrg(`/academy/courses?page=${pageNumber}`));
  state.academyCourses = page.content || [];
  setList('#courses-list', state.academyCourses.map((course) => item(course.name,
    course.description || `Course ID: ${course.id}`)), 'No courses yet. A course groups learners into batches, and a batch is what you distribute an assessment to. Create one with the form below.');
  renderPageControls('#courses-pagination', page, loadAcademyCourses);
  const select = $('#academy-course-select');
  const selected = select.value;
  select.replaceChildren(...state.academyCourses.map((course) => {
    const option = node('option', course.name); option.value = course.id; return option;
  }));
  if (state.academyCourses.some((course) => course.id === selected)) select.value = selected;
  await loadAcademyBatches();
}

async function loadAcademyBatches(pageNumber = 0) {
  const courseId = $('#academy-course-select').value;
  if (!courseId) {
    state.academyBatches = [];
    setList('#batches-list', [], 'Create a course first.');
    $('#batches-pagination').replaceChildren();
    $('#academy-batch-select').replaceChildren();
    setList('#batch-learners-list', [], 'Select a batch to see its learners.');
    $('#batch-learners-pagination').replaceChildren();
    setList('#batch-assessments-list', [], 'Select a batch to see its assessments.');
    $('#batch-assessments-pagination').replaceChildren();
    return;
  }
  const page = await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches?page=${pageNumber}`));
  const batches = page.content || [];
  state.academyBatches = batches;
  setList('#batches-list', batches.map((batch) => item(batch.name,
    `${new Date(batch.startsAt).toLocaleDateString()} – ${new Date(batch.endsAt).toLocaleDateString()}`,
    [button('Open workspace', () => openBatchWorkspace(courseId, batch.id))])),
  'No batches for this course yet. A batch is one run of the course — a cohort with a start and an end. Create one with the form below.');
  renderPageControls('#batches-pagination', page, loadAcademyBatches);
  const select = $('#academy-batch-select');
  const selected = select.value;
  select.replaceChildren(...batches.map((batch) => {
    const option = node('option', batch.name); option.value = batch.id; return option;
  }));
  if (batches.some((batch) => batch.id === selected)) select.value = selected;
  await loadAcademyBatchLearners();
  await loadBatchAssessments();
}

// Academy rows identify a learner. The server now sends learnerDisplayName
// beside learnerUserId, but it can be absent — the account may have been
// removed, or the lookup may have found nothing — so the id remains the
// fallback and is always shown somewhere. A name alone is not enough to act
// on: two learners can share one, and staff forms take the id.
// Audit rows name the person who acted. The audit APIs send actorDisplayName
// beside actorUserId, and it can be absent because a record outlives the account
// that wrote it, so the id stays the fallback and is always shown somewhere: an
// audit trail that cannot be tied back to an exact account is not an audit trail.
function actorName(entry) {
  const name = (entry.actorDisplayName || '').trim();
  return name || `User ${entry.actorUserId}`;
}
function actorIdNote(entry) {
  return (entry.actorDisplayName || '').trim()
    ? `ID ${entry.actorUserId}`
    : 'Display name unavailable for this account';
}
// Staff grading screens name the participant. The review APIs send
// participantDisplayName beside participantUserId, and it can be absent when the
// account no longer resolves, so the id stays the fallback and is always shown
// somewhere: two people can share a name, and the forms here take an id.
function participantName(row) {
  const name = (row.participantDisplayName || '').trim();
  return name || `Participant ${row.participantUserId}`;
}
function participantIdNote(row) {
  return (row.participantDisplayName || '').trim()
    ? `ID ${row.participantUserId}`
    : 'Display name unavailable for this account';
}
function learnerName(row) {
  const name = (row.learnerDisplayName || '').trim();
  return name || `Learner ${row.learnerUserId}`;
}
function learnerIdNote(row) {
  return (row.learnerDisplayName || '').trim()
    ? `ID ${row.learnerUserId}`
    : 'Display name unavailable for this account';
}
async function loadAcademyBatchLearners(pageNumber = 0) {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  if (!courseId || !batchId) {
    setList('#batch-learners-list', [], 'Select a batch to see its learners.');
    $('#batch-learners-pagination').replaceChildren();
    return;
  }
  const page = await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/learners?page=${pageNumber}`));
  setList('#batch-learners-list', (page.content || []).map((enrollment) => item(
    learnerName(enrollment),
    `${learnerIdNote(enrollment)} · Enrolled ${new Date(enrollment.enrolledAt).toLocaleDateString()}`)),
  'No learners enrolled yet. Use “Enroll a learner” below. They must already be a member of this workspace with the learner role.');
  renderPageControls('#batch-learners-pagination', page, loadAcademyBatchLearners);
}

async function loadBatchAssessments(pageNumber = 0) {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  $('#batch-progress-panel').classList.add('hidden');
  if (!courseId || !batchId) {
    setList('#batch-assessments-list', [], 'Select a batch to see its assessments.');
    $('#batch-assessments-pagination').replaceChildren();
    return;
  }
  const page = await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/assessments?page=${pageNumber}`));
  setList('#batch-assessments-list', (page.content || []).map((assessment) => item(
    assessment.assessmentTitle,
    `Version ${assessment.assessmentVersionId} · ${assessment.attemptsAllowed} attempt(s) · Available until ${new Date(assessment.availableUntil).toLocaleString()}`,
    [button('Progress', () => showBatchProgress(assessment.id)),
      button('Reconcile assignments', async () => {
        const result = await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/assessments/${encodeURIComponent(assessment.id)}/reconcile`),
          { method: 'POST' });
        notice(`${result.createdAssignments} assignment(s) added; ${result.alreadyAssigned} already present.`);
      })])), 'No assessments assigned to this batch yet.');
  renderPageControls('#batch-assessments-pagination', page, loadBatchAssessments);
}

// A cohort funnel built from the analytics record the server returns. Every
// stage is a count the API reported; the bar is that count as a share of the
// active learners, and the share is printed beside it so the width is never
// the only way to read the number.
//
// The denominator is active learners, not assigned, because a learner who was
// never assigned is still part of the cohort a coordinator is answerable for.
function cohortFunnel(analytics) {
  const base = analytics.activeLearners || 0;
  const awaitingGrades = Math.max(0, (analytics.submittedLearners || 0) - (analytics.gradedLearners || 0));
  const stages = [
    ['Active learners', analytics.activeLearners, 'funnel-base'],
    ['Assigned', analytics.assignedLearners, ''],
    ['Submitted', analytics.submittedLearners, ''],
    ['Graded', analytics.gradedLearners, ''],
    ['Passed', analytics.passedLearners, 'funnel-pass']
  ];
  const chart = node('div', '', 'funnel');
  for (const [label, value, variant] of stages) {
    const count = value ?? 0;
    const share = base ? Math.round((count / base) * 100) : 0;
    const row = node('div', '', 'funnel-row');
    const track = node('div', '', 'funnel-track');
    const fill = node('div', '', `funnel-fill ${variant}`.trim());
    fill.style.width = `${base ? Math.max(share, count ? 2 : 0) : 0}%`;
    track.append(fill);
    row.append(node('span', label, 'funnel-label'), track,
      node('span', base ? `${count} · ${share}%` : String(count), 'funnel-value'));
    chart.append(row);
  }
  const notes = node('div', '', 'funnel-notes');
  notes.append(node('p', analytics.averageLatestPercentage === null
    ? 'Average latest score: no graded results yet'
    : `Average latest score: ${analytics.averageLatestPercentage.toFixed(1)}%`, 'funnel-note'));
  notes.append(node('p', awaitingGrades
    ? `${awaitingGrades} submitted attempt${awaitingGrades === 1 ? '' : 's'} still awaiting grading`
    : 'Nothing is awaiting grading', awaitingGrades ? 'funnel-note warn-note' : 'funnel-note'));
  notes.append(node('p', analytics.gradedLearners
    ? `Pass rate ${analytics.passRatePercent}% of graded · completion ${analytics.completionRatePercent}% of active`
    : 'Pass rate is not meaningful until something has been graded', 'funnel-note'));
  chart.append(notes);
  return chart;
}
async function showBatchProgress(batchAssessmentId) {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  const route = `/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/assessments/${encodeURIComponent(batchAssessmentId)}`;
  const analytics = await request(ownOrg(`${route}/analytics`));
  setList('#batch-analytics', [cohortFunnel(analytics)
  ], 'Analytics are unavailable.');
  await loadBatchProgressPage(route, 0);
  $('#batch-progress-panel').classList.remove('hidden');
  $('#batch-progress-panel').scrollIntoView({ behavior: 'smooth' });
}
async function loadBatchProgressPage(route, pageNumber) {
  const page = await request(ownOrg(`${route}/progress?page=${pageNumber}`));
  setList('#batch-progress-list', (page.content || []).map((row) => item(
    learnerName(row),
    `${learnerIdNote(row)} · ` + (row.attemptId ? `Latest attempt: ${row.percentage == null ? 'Awaiting manual grading' : `${row.percentage}% · ${row.passed ? 'Passed' : 'Not passed'}`} · ${new Date(row.submittedAt).toLocaleString()}`
      : 'No submitted attempt yet.'),
    !row.attemptId && row.assignmentId ? [reminderButton(row.assignmentId)] : [])), 'No learners assigned yet.');
  renderPageControls('#batch-progress-pagination', page,
    (nextPage) => loadBatchProgressPage(route, nextPage));
}

// ---------------------------------------------------------------- Academy batch workspace
function batchWorkspaceRoute() {
  return `/academy/courses/${encodeURIComponent(state.batchWorkspaceCourseId)}`
    + `/batches/${encodeURIComponent(state.batchWorkspaceBatchId)}`;
}
function batchWorkspaceStale(token) {
  return token !== state.batchWorkspaceToken || !state.organization;
}
function emptyBatchWorkspace(message) {
  state.batchWorkspaceBatchId = null;
  state.batchWorkspaceAssessmentId = null;
  state.batchWorkspaceBatches = [];
  $('#academy-batch-batch').replaceChildren();
  $('#academy-batch-subtitle').textContent = message;
  $('#academy-batch-selected').classList.add('hidden');
  for (const selector of ['#academy-batch-summary', '#academy-batch-assessments', '#academy-batch-learners']) {
    setList(selector, [], message);
  }
  for (const selector of ['#academy-batch-assessments-pagination', '#academy-batch-learners-pagination',
    '#academy-batch-progress-pagination']) {
    $(selector).replaceChildren();
  }
}
async function loadAcademyBatchWorkspace() {
  const token = ++state.batchWorkspaceToken;
  state.batchWorkspaceAssessmentId = null;
  state.batchWorkspaceAssessmentPage = 0;
  $('#academy-batch-selected').classList.add('hidden');
  await loadList('#academy-batch-summary', async () => {
    const courses = await request(ownOrg('/academy/courses?page=0'));
    if (batchWorkspaceStale(token)) return;
    state.batchWorkspaceCourses = courses.content || [];
    const select = $('#academy-batch-course');
    const selected = state.batchWorkspaceCourses.find((course) => course.id === state.batchWorkspaceCourseId)
      || state.batchWorkspaceCourses[0] || null;
    select.replaceChildren(...state.batchWorkspaceCourses.map((course) => {
      const option = node('option', course.name); option.value = course.id; return option;
    }));
    if (!selected) {
      emptyBatchWorkspace('No course exists yet, so there is no batch to open. Create a course under Courses & batches.');
      setList('#academy-batch-summary', [], 'No course exists yet.');
      return;
    }
    select.value = selected.id;
    state.batchWorkspaceCourseId = selected.id;
    await loadAcademyWorkspaceBatches(token);
  }, 'The course list could not be loaded.');
}
async function loadAcademyWorkspaceBatches(token) {
  const courseId = state.batchWorkspaceCourseId;
  if (!courseId) { emptyBatchWorkspace('Choose a course first.'); return; }
  const page = await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches?page=0`));
  if (batchWorkspaceStale(token)) return;
  state.batchWorkspaceBatches = page.content || [];
  const select = $('#academy-batch-batch');
  const selected = state.batchWorkspaceBatches.find((batch) => batch.id === state.batchWorkspaceBatchId)
    || state.batchWorkspaceBatches[0] || null;
  select.replaceChildren(...state.batchWorkspaceBatches.map((batch) => {
    const option = node('option', batch.name); option.value = batch.id; return option;
  }));
  if (!selected) {
    emptyBatchWorkspace('This course has no batches yet. Create one under Courses & batches.');
    setList('#academy-batch-summary', [], 'This course has no batches yet.');
    return;
  }
  select.value = selected.id;
  state.batchWorkspaceBatchId = selected.id;
  await loadAcademyWorkspaceDetail(token);
}
async function loadAcademyWorkspaceDetail(token) {
  const course = state.batchWorkspaceCourses.find((entry) => entry.id === state.batchWorkspaceCourseId);
  const batch = state.batchWorkspaceBatches.find((entry) => entry.id === state.batchWorkspaceBatchId);
  const courseName = course?.name || 'Course';
  const batchName = batch?.name || 'Batch';
  $('#academy-batch-title').textContent = batchName;
  $('#academy-batch-subtitle').textContent = batch
    ? `${courseName} · ${batchName} · ${new Date(batch.startsAt).toLocaleDateString()} – ${new Date(batch.endsAt).toLocaleDateString()}`
    : `${courseName} · ${batchName}`;
  state.batchWorkspaceAssessmentId = null;
  state.batchWorkspaceAssessmentPage = 0;
  $('#academy-batch-selected').classList.add('hidden');
  await Promise.all([
    loadList('#academy-batch-summary', () => loadAcademyWorkspaceSummary(courseName, batch, token),
      'The batch summary could not be loaded.'),
    loadList('#academy-batch-assessments', () => loadAcademyWorkspaceAssessments(token, 0),
      'The batch assessments could not be loaded.'),
    loadList('#academy-batch-learners', () => loadAcademyWorkspaceLearners(token, 0),
      'The batch roster could not be loaded.'),
    loadList('#academy-batch-learning', () => loadAcademyLearningProgress(token, 0),
      'The learning progress could not be loaded.')
  ]);
}
async function loadAcademyLearningProgress(token, pageNumber = 0) {
  const page = await request(ownOrg(`${batchWorkspaceRoute()}/assessments/learning-progress?page=${pageNumber}`));
  if (batchWorkspaceStale(token)) return;
  const rows = (page.content || []).map((step, index) => {
    const card = node('div', '', 'learning-step');
    card.append(node('strong', `${index + 1 + pageNumber * 20}. ${step.assessmentTitle}`, 'learning-title'));
    card.append(stageBars([
      ['Finished', step.submittedLearners, ''],
      ['Marked', step.gradedLearners, ''],
      ['Passed', step.passedLearners, 'funnel-pass']
    ], step.activeLearners || 0));
    card.append(node('p', step.gradedLearners
      ? `${step.completionRatePercent}% of the batch finished · ${step.passRatePercent}% of marked tests passed`
      : `${step.completionRatePercent}% of the batch finished · nothing marked yet`, 'funnel-note'));
    return card;
  });
  setList('#academy-batch-learning', rows, 'No tests have been given to this batch yet, so there is no progress to show.');
  renderPageControls('#academy-batch-learning-pagination', page, (next) => run(() => loadList('#academy-batch-learning',
    () => loadAcademyLearningProgress(token, next), 'The learning progress could not be loaded.')));
}
async function loadAcademyWorkspaceSummary(courseName, batch, token) {
  const roster = await request(ownOrg(`${batchWorkspaceRoute()}/learners?page=0`));
  if (batchWorkspaceStale(token)) return;
  const enrolled = roster.totalElements ?? (roster.content || []).length;
  setList('#academy-batch-summary', [
    item('Course', courseName),
    item('Batch', batch?.name || 'Not reported'),
    item('Batch dates', batch
      ? `${new Date(batch.startsAt).toLocaleString()} – ${new Date(batch.endsAt).toLocaleString()}` : 'Not reported'),
    item('Enrolled learners', `${enrolled} learner(s) on the server roster`),
    item('Batch identifier', state.batchWorkspaceBatchId)
  ], 'No batch summary is available.');
}
async function loadAcademyWorkspaceAssessments(token, pageNumber = 0) {
  state.batchWorkspaceAssessmentPage = pageNumber;
  const route = batchWorkspaceRoute();
  const page = await request(ownOrg(`${route}/assessments?page=${pageNumber}`));
  if (batchWorkspaceStale(token)) return;
  setList('#academy-batch-assessments', (page.content || []).map((assessment) => item(
    assessment.assessmentTitle,
    `Version ${assessment.assessmentVersionId} · ${assessment.attemptsAllowed} attempt(s) allowed`
      + ` · Available until ${new Date(assessment.availableUntil).toLocaleString()}`,
    [button('Progress & analytics', () => loadAcademyWorkspaceAssessment(token, assessment)),
      button('Reconcile assignments', async () => {
        if (!await confirmDialog(`Reconcile assignments for "${assessment.assessmentTitle}"?\n\nReconciliation adds an assignment for each enrolled learner who does not already have one. Existing assignments are never changed or removed.`)) return;
        const result = await request(ownOrg(`${route}/assessments/${encodeURIComponent(assessment.id)}/reconcile`),
          { method: 'POST' });
        notice(`${result.createdAssignments} assignment(s) added; ${result.alreadyAssigned} already present.`);
        await loadAcademyWorkspaceAssessments(token, pageNumber);
      })])), 'No assessment has been distributed to this batch yet.');
  renderPageControls('#academy-batch-assessments-pagination', page,
    (nextPage) => run(() => loadAcademyWorkspaceAssessments(token, nextPage)));
}
async function loadAcademyWorkspaceAssessment(token, assessment) {
  state.batchWorkspaceAssessmentId = assessment.id;
  $('#academy-batch-selected').classList.remove('hidden');
  $('#academy-batch-selected-title').textContent = assessment.assessmentTitle;
  await Promise.all([
    loadList('#academy-batch-analytics', () => loadAcademyWorkspaceAnalytics(assessment, token),
      'Cohort analytics could not be loaded.'),
    loadList('#academy-batch-progress', () => loadAcademyWorkspaceProgress(assessment, token, 0),
      'Learner progress could not be loaded.')
  ]);
  $('#academy-batch-selected').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}
async function loadAcademyWorkspaceAnalytics(assessment, token) {
  const analytics = await request(ownOrg(`${batchWorkspaceRoute()}/assessments/${encodeURIComponent(assessment.id)}/analytics`));
  if (batchWorkspaceStale(token)) return;
  setList('#academy-batch-analytics', [cohortFunnel(analytics)
  ], 'Analytics are unavailable.');
}
async function loadAcademyWorkspaceProgress(assessment, token, pageNumber = 0) {
  const page = await request(ownOrg(`${batchWorkspaceRoute()}/assessments/${encodeURIComponent(assessment.id)}/progress?page=${pageNumber}`));
  if (batchWorkspaceStale(token)) return;
  setList('#academy-batch-progress', (page.content || []).map((row) => item(
    learnerName(row),
    `${learnerIdNote(row)} · ` + (row.attemptId
      ? `Latest attempt: ${row.percentage == null ? 'Awaiting manual grading' : `${row.percentage}% · ${row.passed ? 'Passed' : 'Not passed'}`} · ${new Date(row.submittedAt).toLocaleString()}`
      : 'No submitted attempt yet.'))), 'No learner has been assigned this assessment yet.');
  renderPageControls('#academy-batch-progress-pagination', page,
    (nextPage) => run(() => loadAcademyWorkspaceProgress(assessment, token, nextPage)));
}
async function loadAcademyWorkspaceLearners(token, pageNumber = 0) {
  const page = await request(ownOrg(`${batchWorkspaceRoute()}/learners?page=${pageNumber}`));
  if (batchWorkspaceStale(token)) return;
  setList('#academy-batch-learners', (page.content || []).map((enrollment) => item(
    learnerName(enrollment),
    `${learnerIdNote(enrollment)} · Enrolled ${new Date(enrollment.enrolledAt).toLocaleDateString()}`)),
  'No learner is enrolled in this batch yet.');
  renderPageControls('#academy-batch-learners-pagination', page,
    (nextPage) => run(() => loadAcademyWorkspaceLearners(token, nextPage)));
}
function openBatchWorkspace(courseId, batchId) {
  state.batchWorkspaceCourseId = courseId || null;
  state.batchWorkspaceBatchId = batchId || null;
  state.batchWorkspaceAssessmentId = null;
  return showView('academy-batch');
}
$('#academy-batch-back').addEventListener('click', () => run(() => showView('academy')));
$('#academy-batch-reload').addEventListener('click', () => run(loadAcademyBatchWorkspace));
$('#academy-batch-course').addEventListener('change', (event) => run(async () => {
  state.batchWorkspaceCourseId = event.target.value;
  state.batchWorkspaceBatchId = null;
  state.batchWorkspaceAssessmentId = null;
  await loadAcademyWorkspaceBatches(++state.batchWorkspaceToken);
}));
$('#academy-batch-batch').addEventListener('change', (event) => run(async () => {
  state.batchWorkspaceBatchId = event.target.value;
  await loadAcademyWorkspaceDetail(++state.batchWorkspaceToken);
}));
$('#academy-batch-selected-clear').addEventListener('click', () => {
  state.batchWorkspaceAssessmentId = null;
  $('#academy-batch-selected').classList.add('hidden');
});

// ---------------------------------------------------------------- Talent campaign workspace
function campaignWorkspaceStale(token) {
  return token !== state.campaignWorkspaceToken || !state.organization;
}
function clearCampaignCandidatePanels(message) {
  state.campaignWorkspaceCandidateId = null;
  $('#talent-campaign-candidate').replaceChildren();
  for (const selector of ['#talent-campaign-candidate-summary', '#talent-campaign-integrity',
    '#talent-campaign-events', '#talent-campaign-review', '#talent-campaign-history']) {
    setList(selector, [], message);
  }
  for (const selector of ['#talent-campaign-events-pagination', '#talent-campaign-history-pagination']) {
    $(selector).replaceChildren();
  }
}
function emptyCampaignWorkspace(message) {
  state.campaignWorkspaceId = null;
  state.talentCampaigns = [];
  $('#talent-campaign-select').replaceChildren();
  $('#talent-campaign-title').textContent = 'Campaign workspace';
  $('#talent-campaign-subtitle').textContent = message;
  $('#talent-campaign-close').disabled = true;
  setList('#talent-campaign-summary', [], message);
  setList('#talent-campaign-comparison', [], message);
  $('#talent-campaign-comparison-pagination').replaceChildren();
  clearCampaignCandidatePanels(message);
}
async function loadTalentCampaignWorkspace() {
  const token = ++state.campaignWorkspaceToken;
  await loadList('#talent-campaign-summary', async () => {
    const page = await request(ownOrg('/talent/campaigns?page=0'));
    if (campaignWorkspaceStale(token)) return;
    state.talentCampaigns = page.content || [];
    const select = $('#talent-campaign-select');
    const selected = state.talentCampaigns.find((campaign) => campaign.id === state.campaignWorkspaceId)
      || state.talentCampaigns[0] || null;
    select.replaceChildren(...state.talentCampaigns.map((campaign) => {
      const option = node('option', campaign.title); option.value = campaign.id; return option;
    }));
    if (!selected) {
      emptyCampaignWorkspace('No hiring campaign exists yet. Create one under Hiring campaigns.');
      return;
    }
    select.value = selected.id;
    state.campaignWorkspaceId = selected.id;
    await loadCampaignWorkspaceDetail(token);
  }, 'The campaign list could not be loaded.');
}
async function loadCampaignWorkspaceDetail(token) {
  const campaign = (state.talentCampaigns || []).find((entry) => entry.id === state.campaignWorkspaceId);
  const campaignId = state.campaignWorkspaceId;
  if (!campaignId) { emptyCampaignWorkspace('Choose a campaign first.'); return; }
  $('#talent-campaign-title').textContent = campaign?.title || 'Campaign workspace';
  $('#talent-campaign-subtitle').textContent = campaign
    ? `${campaign.open ? 'Open to new invitations' : 'Closed to new invitations'}`
      + ` · Frozen version ${campaign.assessmentVersionId}`
      + (campaign.createdAt ? ` · Created ${new Date(campaign.createdAt).toLocaleDateString()}` : '')
    : 'Campaign details are unavailable.';
  $('#talent-campaign-close').disabled = !campaign?.open;
  clearCampaignCandidatePanels('Choose a candidate above to see their integrity signals, review decision and review history.');
  await Promise.all([
    loadList('#talent-campaign-summary', () => loadCampaignWorkspaceSummary(campaign, campaignId),
      'The campaign summary could not be loaded.'),
    loadList('#talent-campaign-comparison', () => loadCampaignWorkspaceCandidates(token, 0),
      'The candidate comparison could not be loaded.')
  ]);
}
async function loadCampaignWorkspaceSummary(campaign, campaignId) {
  setList('#talent-campaign-summary', [
    item('Status', campaign?.open ? 'Open to new invitations' : 'Closed to new invitations'),
    item('Frozen assessment version', campaign?.assessmentVersionId || 'Not reported'),
    item('Created', campaign?.createdAt ? new Date(campaign.createdAt).toLocaleString() : 'Not recorded'),
    item('Campaign identifier', campaignId),
    item('Integrity signals', 'Per attempt, under the selected candidate below. The server reports no campaign-level signal summary.')
  ], 'The campaign summary is unavailable.');
}
async function loadCampaignWorkspaceCandidates(token, pageNumber = 0) {
  state.campaignWorkspacePage = pageNumber;
  const campaignId = state.campaignWorkspaceId;
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates?page=${pageNumber}`));
  if (campaignWorkspaceStale(token)) return;
  const rows = page.content || [];
  state.campaignWorkspaceCandidates = rows;
  const summary = node('div', '', 'stack');
  summary.append(node('h4', `${page.totalElements ?? rows.length} invited candidate(s)`),
    node('p', 'Latest submitted attempt per candidate, exactly as the campaign API reports it.', 'muted'));
  const head = node('div', '', 'compare-row compare-head');
  head.append(node('span', 'Candidate'), node('span', 'Latest attempt'), node('span', 'Decision'));
  summary.append(head);
  for (const candidate of rows) {
    const row = node('div', '', 'compare-row');
    const attempt = candidate.attemptId
      ? (candidate.percentage == null ? 'Awaiting grading'
        : `${candidate.percentage}% · ${candidate.passed ? 'Passed' : 'Not passed'}`)
      : 'No submitted attempt';
    row.append(node('span', talentCandidateName(candidate)), node('span', attempt),
      node('span', candidate.decision ? roleLabel(candidate.decision) : 'Not reviewed'));
    summary.append(row);
  }
  setList('#talent-campaign-comparison', rows.length ? [summary] : [], 'No candidate has been invited to this campaign yet.');
  const select = $('#talent-campaign-candidate');
  const previous = state.campaignWorkspaceCandidateId || select.value;
  select.replaceChildren(...rows.map((candidate) => {
    const option = node('option', talentCandidateName(candidate)); option.value = candidate.candidateUserId; return option;
  }));
  if (rows.some((candidate) => candidate.candidateUserId === previous)) select.value = previous;
  renderPageControls('#talent-campaign-comparison-pagination', page,
    (nextPage) => run(() => loadCampaignWorkspaceCandidates(token, nextPage)));
  if (!rows.length) {
    clearCampaignCandidatePanels('This page has no candidates, so there is nothing to inspect.');
    return;
  }
  const chosen = rows.find((candidate) => candidate.candidateUserId === select.value) || rows[0];
  select.value = chosen.candidateUserId;
  await loadCampaignWorkspaceCandidate(chosen, token);
}
async function loadCampaignWorkspaceCandidate(candidate, token) {
  const campaignId = state.campaignWorkspaceId;
  state.campaignWorkspaceCandidateId = candidate.candidateUserId;
  const route = `/talent/campaigns/${encodeURIComponent(campaignId)}`
    + `/candidates/${encodeURIComponent(candidate.candidateUserId)}`;
  setList('#talent-campaign-candidate-summary', [
    item(talentCandidateName(candidate),
      `Assignment ${candidate.assignmentId || 'not reported'}`,
      candidate.assignmentId && !candidate.attemptId ? [reminderButton(candidate.assignmentId)] : []),
    item('Latest submitted attempt', candidate.attemptId
      ? `${candidate.attemptId} · ${candidate.percentage == null ? 'Awaiting manual grading' : `${candidate.percentage}% · ${candidate.passed ? 'Passed' : 'Not passed'}`}`
        + ` · Submitted ${candidate.submittedAt ? new Date(candidate.submittedAt).toLocaleString() : 'not reported'}`
      : 'No submitted attempt yet.'),
    item('Current decision', candidate.decision ? roleLabel(candidate.decision) : 'Not reviewed'),
    item('Reviewer note', candidate.reviewerNote || 'No note recorded')
  ], 'No candidate detail is available.');
  await Promise.all([
    loadList('#talent-campaign-integrity', () => loadCampaignCandidateIntegrity(candidate, token),
      'The integrity summary could not be loaded.'),
    loadList('#talent-campaign-events', () => loadCampaignCandidateEvents(candidate, token, 0),
      'The raw event log could not be loaded.'),
    loadList('#talent-campaign-history', () => loadCampaignCandidateHistory(candidate, token, 0),
      'The review history could not be loaded.'),
    loadList('#talent-campaign-review', () => loadCampaignCandidateReviewForm(candidate, route, token),
      'The review decision form could not be prepared.')
  ]);
}
async function loadCampaignCandidateIntegrity(candidate, token) {
  if (!candidate.attemptId) {
    setList('#talent-campaign-integrity', [], 'No submitted attempt yet, so the server has no integrity signals for this candidate.');
    return;
  }
  const report = await request(ownOrg(`/talent/attempts/${encodeURIComponent(candidate.attemptId)}/integrity`));
  if (campaignWorkspaceStale(token)) return;
  const counts = Object.entries(report.eventCounts || {})
    .map(([type, count]) => `${type.replaceAll('_', ' ').toLowerCase()}: ${count}`).join(' · ');
  setList('#talent-campaign-integrity', [
    item('Recorded events', String(report.totalEvents)),
    item('Rapid answer intervals reported by the client', String(report.clientReportedRapidAnswerIntervals)),
    item('Event counts', counts || 'No signals recorded.'),
    item('How to read this', report.interpretation)
  ], 'No integrity signals are available.');
}
async function loadCampaignCandidateEvents(candidate, token, pageNumber = 0) {
  if (!candidate.attemptId) {
    setList('#talent-campaign-events', [], 'No submitted attempt yet, so there is no event log to read.');
    $('#talent-campaign-events-pagination').replaceChildren();
    return;
  }
  const page = await request(ownOrg(`/talent/attempts/${encodeURIComponent(candidate.attemptId)}/integrity/events?page=${pageNumber}`));
  if (campaignWorkspaceStale(token)) return;
  const rows = (page.content || []).map((event) => node('p',
    `${event.type.replaceAll('_', ' ').toLowerCase()}${event.questionId ? ` · question ${event.questionId}` : ''}`
      + ` · ${new Date(event.receivedAt).toLocaleString()}`, 'item-meta'));
  const target = $('#talent-campaign-events');
  target.setAttribute('aria-busy', 'false');
  target.replaceChildren(node('p',
    `${page.totalElements ?? rows.length} recorded event(s) — these signals are client-reported, cannot be verified, and are not proof of misconduct.`,
    'muted'), ...rows);
  if (!rows.length) target.append(node('p', 'No raw events retained.', 'item-meta'));
  renderPageControls('#talent-campaign-events-pagination', page,
    (nextPage) => run(() => loadCampaignCandidateEvents(candidate, token, nextPage)));
}
async function loadCampaignCandidateHistory(candidate, token, pageNumber = 0) {
  const campaignId = state.campaignWorkspaceId;
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}`
    + `/candidates/${encodeURIComponent(candidate.candidateUserId)}/review/audit?page=${pageNumber}`));
  if (campaignWorkspaceStale(token)) return;
  setList('#talent-campaign-history', (page.content || []).map((entry) => item(
    `${entry.decision.replaceAll('_', ' ')} · ${new Date(entry.occurredAt).toLocaleString()}`,
    `Reviewer ${actorName(entry)} · ${actorIdNote(entry)} · Attempt ${entry.attemptId || 'not recorded'} · ${entry.note || 'No note'}`)),
  'No review has been recorded for this candidate yet.');
  renderPageControls('#talent-campaign-history-pagination', page,
    (nextPage) => run(() => loadCampaignCandidateHistory(candidate, token, nextPage)));
}
async function loadCampaignCandidateReviewForm(candidate, route, token) {
  if (candidate.percentage == null) {
    setList('#talent-campaign-review', [], candidate.attemptId
      ? 'The attempt is still awaiting manual grading, so a review decision cannot be saved yet.'
      : 'A review decision needs a graded, submitted attempt.');
    return;
  }
  const form = node('form', '', 'form-row');
  const decisionLabel = node('label', 'Decision');
  const select = node('select');
  for (const decision of ['HOLD', 'SHORTLISTED', 'HIRED', 'REJECTED']) {
    const option = node('option', decision.replaceAll('_', ' ')); option.value = decision; select.append(option);
  }
  select.value = candidate.decision || 'HOLD';
  decisionLabel.append(select);
  const noteLabel = node('label', 'Reviewer note');
  const noteInput = node('input');
  noteInput.maxLength = 1000; noteInput.value = candidate.reviewerNote || '';
  noteLabel.append(noteInput);
  const save = node('button', 'Save review', 'button secondary small'); save.type = 'submit';
  form.append(decisionLabel, noteLabel, save);
  form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
    save.disabled = true;
    try {
      await request(ownOrg(`${route}/review`), { method: 'PUT', body: {
        attemptId: candidate.attemptId, decision: select.value, note: noteInput.value
      } });
      notice('Review saved. One review is stored per candidate; saving again replaces it.');
      if (campaignWorkspaceStale(token)) return;
      await Promise.all([
        loadCampaignWorkspaceCandidates(token, state.campaignWorkspacePage),
        loadCampaignCandidateHistory(candidate, token, 0)
      ]);
    } catch (error) {
      fieldErrors(form, error);
      throw error;
    } finally { save.disabled = false; }
  }); });
  const target = $('#talent-campaign-review');
  target.setAttribute('aria-busy', 'false');
  target.replaceChildren(form, node('p',
    'A review is offered only for a graded, submitted attempt; the server refuses one otherwise.', 'muted'));
}
function openCampaignWorkspace(campaignId) {
  state.campaignWorkspaceId = campaignId || null;
  state.campaignWorkspaceCandidateId = null;
  return showView('talent-campaign');
}
$('#talent-campaign-back').addEventListener('click', () => run(() => showView('campaigns')));
$('#talent-campaign-reload').addEventListener('click', () => run(loadTalentCampaignWorkspace));
$('#talent-campaign-select').addEventListener('change', (event) => run(async () => {
  state.campaignWorkspaceId = event.target.value;
  state.campaignWorkspaceCandidateId = null;
  await loadCampaignWorkspaceDetail(++state.campaignWorkspaceToken);
}));
$('#talent-campaign-candidate').addEventListener('change', (event) => run(async () => {
  const candidate = (state.campaignWorkspaceCandidates || [])
    .find((entry) => entry.candidateUserId === event.target.value);
  if (candidate) await loadCampaignWorkspaceCandidate(candidate, ++state.campaignWorkspaceToken);
}));
$('#talent-campaign-close').addEventListener('click', () => run(async () => {
  const campaignId = state.campaignWorkspaceId;
  const campaign = (state.talentCampaigns || []).find((entry) => entry.id === campaignId);
  if (!campaignId || !campaign?.open) throw new Error('Choose an open campaign before closing it.');
  if (!await confirmDialog(`Close "${campaign.title}" to new invitations?\n\nExisting assignments stay usable until their own windows end. Closing is audited and cannot be undone from this screen.`)) return;
  $('#talent-campaign-close').disabled = true;
  try {
    await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/close`), { method: 'POST' });
    notice('Campaign closed to new invitations.');
    await loadTalentCampaignWorkspace();
  } finally { $('#talent-campaign-close').disabled = false; }
}));

function stopMessageRefresh() {
  window.clearInterval(state.messageRefresh);
  state.messageRefresh = null;
  state.messageRefreshBusy = false;
}

function startMessageRefresh() {
  stopMessageRefresh();
  state.messageRefresh = window.setInterval(() => {
    if (state.view !== 'messages' || !state.activeConversationId || state.messagePage !== 0
        || state.messageRefreshBusy) return;
    state.messageRefreshBusy = true;
    loadConversationMessages().catch((error) => notice(error.message || 'Could not refresh messages.', true))
      .finally(() => { state.messageRefreshBusy = false; });
  }, 15000);
}

// ---------------------------------------------------------------- unread messages
// How many messages are waiting, beside Messages in the sidebar, in the tab title, and on Home.
// Fetched when an organization is chosen, every thirty seconds while the tab is visible, and when
// it becomes visible again. A badge that cannot refresh keeps its last value rather than flicker.
state.unread = 0;
state.readThrough = new Map();
const MESSAGING_ROLES = ['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER'];
function messagingAvailable() {
  const organization = state.organization;
  return Boolean(state.accessToken && organization && organization.type === 'ACADEMY'
    && MESSAGING_ROLES.includes(organization.myRole) && !state.activeAttempt);
}
async function refreshUnread() {
  if (!messagingAvailable()) { setUnread(0); return; }
  const token = state.accessToken; const organizationId = state.organization.id;
  try {
    const result = await request(ownOrg('/messaging/unread'));
    if (token !== state.accessToken || organizationId !== state.organization?.id) return;
    setUnread(result.unread || 0);
  } catch { /* keep the last count */ }
}
function setUnread(count) {
  state.unread = count;
  paintUnread();
}
function unreadLabel(count) { return count > 99 ? '99+' : String(count); }
function paintUnread() {
  const button = document.querySelector('#workspace-nav [data-screen="messages"]');
  if (button) {
    let badge = button.querySelector('.nav-badge');
    if (state.unread > 0) {
      if (!badge) { badge = node('span', '', 'nav-badge'); button.append(badge); }
      badge.textContent = unreadLabel(state.unread);
      badge.setAttribute('aria-label', t(state.unread === 1 ? '1 unread message' : '{n} unread messages', { n: state.unread }));
    } else { badge?.remove(); }
  }
  const note = $('#home-messages-unread');
  if (note) {
    note.hidden = state.unread === 0;
    note.textContent = state.unread === 0 ? '' : t(state.unread === 1 ? '1 new message' : '{n} new messages', { n: state.unread });
  }
  titleBadge();
}
// "(3) Messages · JachAI": the count in the browser tab, where it is seen from another tab.
function titleBadge() {
  const base = document.title.replace(/^\((\d+|99\+)\)\s*/, '');
  document.title = state.unread > 0 ? `(${unreadLabel(state.unread)}) ${base}` : base;
}
async function markConversationRead(conversationId) {
  try {
    await request(ownOrg(`/messaging/conversations/${encodeURIComponent(conversationId)}/read`), { method: 'POST' });
  } catch { return; }
  const row = document.querySelector(`#conversations-list .chat-row[data-conversation-id="${CSS.escape(conversationId)}"]`);
  row?.classList.remove('unread');
  row?.querySelector('.unread-pill')?.remove();
  void refreshUnread();
}
function shortTime(value) {
  const when = new Date(value);
  const today = new Date();
  return when.toDateString() === today.toDateString()
    ? when.toLocaleTimeString(getLocale(), { hour: 'numeric', minute: '2-digit' })
    : when.toLocaleDateString(getLocale(), { day: 'numeric', month: 'short' });
}
setInterval(() => { if (document.visibilityState === 'visible') void refreshUnread(); }, 30000);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void refreshUnread(); });

async function loadMessagingPartners(pageNumber = 0) {
  const partners = await request(ownOrg(`/messaging/partners?page=${pageNumber}`));
  $('#messaging-partner-select').replaceChildren(...(partners.content || []).map((partner) => {
    const option = node('option', `${partner.displayName} · ${partner.email}`);
    option.value = partner.userId; return option;
  }));
  renderPageControls('#messaging-partner-pagination', partners, loadMessagingPartners);
}

async function loadConversations(pageNumber = 0) {
  const page = await request(ownOrg(`/messaging/conversations?page=${pageNumber}`));
  const rows = (page.content || []).map((conversation) => {
    const row = node('button', '', 'chat-row');
    row.type = 'button';
    row.dataset.conversationId = conversation.id;
    row.classList.toggle('active', conversation.id === state.activeConversationId);
    row.classList.toggle('unread', conversation.unread > 0);
    const text = node('span', '', 'chat-row-text');
    // What was said last, as in any chat list; a thread nobody has written in says when it began.
    text.append(node('strong', conversation.counterpartDisplayName),
      node('small', conversation.lastMessagePreview
        || `${t('Started')} ${new Date(conversation.createdAt).toLocaleDateString(getLocale())}`, 'chat-preview'));
    const meta = node('span', '', 'chat-row-meta');
    if (conversation.lastMessageAt) meta.append(node('small', shortTime(conversation.lastMessageAt)));
    if (conversation.unread > 0) {
      const pill = node('span', unreadLabel(conversation.unread), 'unread-pill');
      pill.setAttribute('aria-label', t(conversation.unread === 1 ? '1 unread message' : '{n} unread messages', { n: conversation.unread }));
      meta.append(pill);
    }
    row.append(initialsAvatar(conversation.counterpartDisplayName), text, meta);
    row.addEventListener('click', () => run(() => openConversation(conversation)));
    return row;
  });
  setList('#conversations-list', rows,
    'No conversations yet. Choose someone below and press Start.');
  renderPageControls('#conversations-pagination', page, loadConversations);
}
async function loadMessaging() {
  await loadMessagingPartners();
  await loadConversations();
  if (state.activeConversationId) {
    await loadConversationMessages();
    startMessageRefresh();
  } else {
    $('#conversation-panel').classList.add('hidden');
  }
}

async function openConversation(conversation) {
  state.activeConversationId = conversation.id;
  state.activeConversationName = conversation.counterpartDisplayName;
  state.messagePage = 0;
  state.messageLoadToken++;
  state.messageStickToBottom = true;
  const title = $('#conversation-title');
  title.replaceChildren(initialsAvatar(conversation.counterpartDisplayName),
    node('span', conversation.counterpartDisplayName));
  $('#conversation-panel').classList.remove('hidden');
  for (const row of $('#conversations-list').querySelectorAll('.chat-row')) {
    row.classList.toggle('active', row.dataset.conversationId === conversation.id);
  }
  await loadConversationMessages();
  void markConversationRead(conversation.id);
  startMessageRefresh();
  // Side by side on a wide screen, stacked on a phone: there the thread is below the
  // list and has to be brought into view, or choosing someone appears to do nothing.
  if (matchMedia('(max-width: 820px)').matches) {
    $('#conversation-panel').scrollIntoView({ block: 'start' });
  } else {
    $('#message-form textarea').focus({ preventScroll: true });
  }
}
async function loadConversationMessages(pageNumber = state.messagePage) {
  const conversationId = state.activeConversationId;
  if (!conversationId) return;
  const previousPage = state.messagePage;
  state.messagePage = pageNumber;
  const loadToken = ++state.messageLoadToken;
  let page;
  try {
    page = await request(ownOrg(`/messaging/conversations/${encodeURIComponent(conversationId)}/messages?page=${pageNumber}`));
  } catch (error) {
    if (loadToken === state.messageLoadToken) state.messagePage = previousPage;
    throw error;
  }
  if (conversationId !== state.activeConversationId || loadToken !== state.messageLoadToken) return;
  const thread = $('#conversation-messages');
  // Stay where the reader is: follow new messages only if they were already at the
  // bottom, so a refresh never yanks someone away from what they were reading.
  const nearBottom = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 80;
  const me = myUserId();
  const rows = [];
  let lastDay = '';
  for (const message of [...(page.content || [])].reverse()) {
    const sent = new Date(message.sentAt);
    const day = sent.toDateString();
    if (day !== lastDay) { rows.push(node('div', dayLabel(sent), 'chat-day')); lastDay = day; }
    const mine = message.senderUserId === me;
    const bubble = node('div', '', `bubble ${mine ? 'mine' : 'theirs'}`);
    bubble.append(node('p', message.body, 'bubble-text'),
      node('span', sent.toLocaleTimeString(getLocale(), { hour: 'numeric', minute: '2-digit' }), 'bubble-time'));
    // Who sent it is the side of the screen, as in every chat; the name is for screen readers.
    bubble.setAttribute('aria-label', `${mine ? t('You') : state.activeConversationName}, ${sent.toLocaleString()}`);
    rows.push(bubble);
  }
  setList('#conversation-messages', rows, 'No messages yet. Say hello.');
  renderPageControls('#conversation-messages-pagination', page, loadConversationMessages);
  // A message from the other person landed while this thread is open and on screen: it is read.
  const newest = (page.content || [])[0];
  if (pageNumber === 0 && newest && newest.senderUserId !== me && document.visibilityState === 'visible'
      && state.readThrough.get(conversationId) !== newest.sentAt) {
    state.readThrough.set(conversationId, newest.sentAt);
    void markConversationRead(conversationId);
  }
  if (pageNumber === 0 && (nearBottom || state.messageStickToBottom)) {
    thread.scrollTop = thread.scrollHeight;
    state.messageStickToBottom = false;
  }
}

// The signed-in user's id is the subject of their access token. Reading it here changes
// nothing about where the token lives: it is decoded, never stored or sent anywhere.
function myUserId() {
  try {
    const payload = state.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '='))).sub || null;
  } catch { return null; }
}
function initialsAvatar(name) {
  const avatar = node('span', (name || '?').trim().charAt(0).toUpperCase(), 'chat-avatar');
  avatar.setAttribute('aria-hidden', 'true');
  return avatar;
}
function dayLabel(date) {
  const today = new Date();
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return t('Today');
  if (date.toDateString() === yesterday.toDateString()) return t('Yesterday');
  return date.toLocaleDateString(getLocale(), { day: 'numeric', month: 'short', year: 'numeric' });
}
$('#verify-code-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const code = $('#verify-code').value.trim();
  if (!code) throw new Error('Enter the verification code printed on the certificate.');
  window.location.assign(`/certificates/${encodeURIComponent(code)}`);
}); });
$('#login-tab').addEventListener('click', () => switchAuth('login'));
// 'noopener' makes window.open return null even when the tab DID open, so null cannot mean
// "blocked", and an error built on it is wrong most of the time it appears. Open it, and leave a
// plain link beside the button for the rare browser that really did refuse.
function openCheckout(control, url) {
  let target;
  try { target = new URL(url, window.location.origin); } catch { target = null; }
  if (!target || !['https:', 'http:'].includes(target.protocol)) {
    throw new Error('The payment page address was not valid. Try again.');
  }
  window.open(target.href, '_blank', 'noopener,noreferrer');
  let fallback = document.querySelector('#checkout-fallback');
  if (!fallback) {
    fallback = node('p', '', 'muted'); fallback.id = 'checkout-fallback';
    control.closest('.card, .panel, div')?.append(fallback);
  }
  const link = node('a', t('open the payment page again'), 'inline-link');
  link.href = target.href; link.target = '_blank'; link.rel = 'noopener noreferrer';
  fallback.replaceChildren(t('Did the payment page not open? '), link, '.');
}
$('#start-demo-pay').addEventListener('click', () => run(async () => {
  const control = $('#start-demo-pay');
  control.disabled = true; control.textContent = 'Opening checkout…';
  try {
    const checkout = await request(ownOrg('/billing/orders'), { method: 'POST', body: { provider: 'DEMO' } });
    openCheckout(control, checkout.checkoutUrl);
  } finally {
    control.disabled = false; control.textContent = 'Upgrade to TEAM';
  }
}));
$('#start-ssl-demo').addEventListener('click', () => run(async () => {
  if (!await confirmDialog('Open the SSLCommerz sandbox?\n\n'
      + 'The sandbox opens in a new tab, so this signed-in tab stays available. '
      + 'After returning, use Check payment in Billing to read the server-verified result. '
      + 'A browser redirect never confirms a payment by itself.')) return;
  const control = $('#start-ssl-demo');
  control.disabled = true; control.textContent = 'Opening SSLCommerz sandbox…';
  try {
    const checkout = await request(ownOrg('/billing/orders'),
      { method: 'POST', body: { provider: 'SSLCOMMERZ' } });
    openCheckout(control, checkout.checkoutUrl);
  } finally {
    control.disabled = false; control.textContent = 'Pay with SSLCommerz sandbox';
  }
}));
$('#start-bkash-demo').addEventListener('click', () => run(async () => {
  if (!await confirmDialog('Open the bKash sandbox?\n\n'
      + 'The sandbox opens in a new tab, so this signed-in tab stays available. '
      + 'After returning, use Check payment in Billing to read the server-verified result. '
      + 'A browser redirect never confirms a payment by itself.')) return;
  const control = $('#start-bkash-demo');
  control.disabled = true; control.textContent = 'Opening bKash sandbox…';
  try {
    const checkout = await request(ownOrg('/billing/orders'),
      { method: 'POST', body: { provider: 'BKASH' } });
    openCheckout(control, checkout.checkoutUrl);
  } finally {
    control.disabled = false; control.textContent = 'Pay with direct bKash sandbox';
  }
}));
$('#register-tab').addEventListener('click', () => switchAuth('register'));
function switchAuth(which) {
  $('#login-form').classList.toggle('hidden', which !== 'login');
  $('#register-form').classList.toggle('hidden', which !== 'register');
  $('#login-form').closest('.auth-card').classList.toggle('registering', which === 'register');
  for (const name of ['login', 'register']) {
    const tab = $(`#${name}-tab`); tab.classList.toggle('active', name === which);
    tab.setAttribute('aria-selected', String(name === which));
    tab.setAttribute('tabindex', name === which ? '0' : '-1');
  }
  // A programmatic click does not move focus, so place it on the selected tab (the ARIA tab pattern)
  // rather than leaving it on the old tab or on nothing.
  const selected = $(`#${which}-tab`);
  if (document.activeElement !== selected) selected.focus();
}
for (const tab of [$('#login-tab'), $('#register-tab')]) {
  tab.addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    event.preventDefault();
    const next = event.currentTarget.id === 'login-tab' ? 'register' : 'login';
    switchAuth(next);
    $(`#${next}-tab`).focus();
  });
}
async function setupGoogleSignIn() {
  const area = $('#google-login-area');
  area.classList.remove('hidden');
  const config = await request('/auth/google/config', { auth: false });
  if (!config?.clientId) {
    area.replaceChildren(node('p', 'Google sign-in needs a Google Web client ID. The app operator must set GOOGLE_CLIENT_ID and restart; email sign-in remains available.', 'muted'));
    return;
  }
  const script = document.createElement('script');
  script.src = 'https://accounts.google.com/gsi/client?hl=en';
  script.async = true;
  await new Promise((resolve, reject) => {
    script.onload = resolve; script.onerror = reject; document.head.append(script);
  });
  window.google.accounts.id.initialize({ client_id: config.clientId, callback: ({ credential }) => run(async () => {
    if (state.accessToken) {
      await request('/auth/google/link', { method: 'POST', body: { credential } });
      notice('Google account linked. You can use it to sign in next time.');
    } else {
      try {
        const tokens = await request('/auth/google', { method: 'POST', auth: false, body: { credential } });
        await signedIn(tokens);
      } catch (error) {
        if (error.code !== 'ACCOUNT_LINK_REQUIRED') throw error;
        const help = $('#google-login-help');
        help.replaceChildren(node('span', 'Google could not link this account automatically. Sign in with email/password, then connect Google from Profile & settings.'));
        help.classList.remove('hidden');
        help.scrollIntoView({ block: 'nearest' });
      }
    }
  }) });
  area.querySelector('.muted').textContent = 'or';
  for (const [selector, area] of [['#google-login-button', '#google-login-area'],
    ['#google-link-button', '#google-link-area']]) {
    const areaEl = $(area);
    const mutedEl = areaEl.querySelector('.muted');
    if (mutedEl && area === '#google-link-area') mutedEl.textContent = 'or';
    window.google.accounts.id.renderButton($(selector), { theme: 'outline', size: 'large', text: 'continue_with', locale: 'en' });
    areaEl.classList.remove('hidden');
  }
}
setupGoogleSignIn().catch(() => {
  const area = $('#google-login-area');
  area.replaceChildren(node('p', 'Google sign-in could not load. Check your connection or browser settings, then refresh. Email sign-in still works.', 'muted'));
  area.classList.remove('hidden');
});
let mailDeliveryMode = 'UNKNOWN';
request('/auth/email-delivery-mode', { auth: false }).then((config) => {
  mailDeliveryMode = config?.mode || 'UNKNOWN';
  $('#registration-mail-mode').textContent = mailDeliveryMode === 'SMTP'
    ? 'This server queues verification links for email. Check your inbox and spam folder; delivery is not confirmed on this page.'
    : mailDeliveryMode === 'LOCAL_CONSOLE'
      ? 'This local run does not send email. Find the verification link in the IntelliJ console or local app log. Configure SMTP and restart to receive email.'
      : 'Delivery setup could not be checked. Ask the operator where verification links go.';
}).catch(() => {
  $('#registration-mail-mode').textContent = 'Delivery setup could not be checked. Ask the operator where verification links go.';
});
$('#login-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const element = event.currentTarget;
  const form = new FormData(element);
  const control = element.querySelector('button[type="submit"]');
  if (control) control.disabled = true;
  try {
    const tokens = await request('/auth/login', { method: 'POST', auth: false,
      body: { email: form.get('email'), password: form.get('password') } });
    await signedIn(tokens);
  } catch (error) {
    fieldErrors(element, error);
    if (error.code === 'EMAIL_UNVERIFIED') {
      error.message = 'Email not verified yet. Open the verification link, or use “Need a new verification link?” below. The Create account tab shows where this run sends links.';
    }
    throw error;
  } finally { if (control) control.disabled = false; }
}); });
const DEMO_ROLE_HINTS = {
  'academy-admin': 'Runs courses and plans',
  instructor: 'Writes tests and grades',
  learner: 'Takes tests, earns certificates',
  'talent-admin': 'Runs hiring rounds',
  recruiter: 'Compares candidates',
  candidate: 'Takes a hiring test'
};
function loadDemoAccess() {
  return request('/demo/accounts', { auth: false, retry: false }).then((accounts) => {
  if (!Array.isArray(accounts) || !accounts.length) return;
  const roles = $('#demo-access-roles');
  roles.replaceChildren(...accounts.map((account) => {
    const control = button('', async () => {
      control.disabled = true;
      try {
        demoWorkspace = String(account.workspace || '').toUpperCase();
        const tokens = await request(`/demo/login/${encodeURIComponent(account.key)}`,
          { method: 'POST', auth: false, retry: false });
        await signedIn(tokens);
      } finally { control.disabled = false; }
    }, 'button secondary demo-role');
    const hint = DEMO_ROLE_HINTS[account.key];
    control.append(node('span', account.label, 'demo-role-name'));
    if (hint) control.append(node('span', hint, 'demo-role-hint'));
    control.setAttribute('aria-label', `Sign in as ${account.label} in the ${account.workspace} demo workspace`);
    return control;
  }));
  $('#demo-access').classList.remove('hidden');
  $('#demo-access-status').classList.add('hidden');
  const requestedRole = new URLSearchParams(window.location.search).get('demoRole');
  if (requestedRole && accounts.some((account) => account.key === requestedRole)) {
    const cleanUrl = new URL(window.location.href);
    cleanUrl.searchParams.delete('demoRole');
    window.history.replaceState(null, '', cleanUrl.pathname + cleanUrl.search + cleanUrl.hash);
    roles.children[accounts.findIndex((account) => account.key === requestedRole)].click();
  }
  }).catch((error) => {
    $('#demo-access-status').classList.remove('hidden');
    $('#demo-access-status').textContent = error.code === 'DEMO_LOCAL_ONLY'
      ? 'Demo roles are available only from this local app address.'
      : error.code === 'DEMO_HTTPS_REQUIRED'
        ? 'Public demo access requires the operator to configure a valid HTTPS application URL.'
        : 'Demo roles are not enabled on this deployment. The operator must enable public demo access before these buttons can be used.';
  });
}
loadDemoAccess();
$('#register-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const element = event.currentTarget;
  const form = new FormData(element);
  const control = element.querySelector('button[type="submit"]');
  if (control) control.disabled = true;
  try {
    const created = await request('/auth/register', { method: 'POST', auth: false,
      body: { displayName: form.get('displayName'), email: form.get('email'), password: form.get('password') } });
    element.reset(); switchAuth('login');
    notice(mailDeliveryMode === 'LOCAL_CONSOLE'
      ? 'Account created. This local run prints the verification link in the IntelliJ console or app log; it does not send email.'
      : mailDeliveryMode === 'SMTP'
        ? 'Account created. A verification link was queued for email. Check your inbox and spam folder; delivery is not confirmed here.'
        : `${created?.message || 'Account created; a verification link was queued'}. Delivery mode could not be checked.`);
  } catch (error) {
    fieldErrors(element, error);
    if (error.code === 'EMAIL_EXISTS') {
      error.message = 'This email already has an account. Use Sign in, or request a new verification link below if it is not verified yet. This attempt did not create a new account or email.';
    }
    throw error;
  } finally { if (control) control.disabled = false; }
}); });
$('#logout').addEventListener('click', () => run(async () => {
  let ended = true;
  try {
    await request('/auth/logout', { method: 'POST', body: { refreshToken: state.refreshToken },
      extraHeaders: SESSION_MARKER });
  } catch (error) {
    // A 401 means the session was already gone. Anything else means the server was not told.
    ended = error.status === 401;
  } finally { showSignedOut(); }
  notice(ended ? 'Signed out.'
    : 'Signed out here, but the server could not be reached to end the session. Close the browser to be sure.',
  !ended);
}));
$('#organization-select').addEventListener('change', (event) => run(async () => {
  state.organization = state.organizations.find((item) => item.id === event.target.value);
  await setOrganization();
}));
// ---- Guided practice: a short skill check, fixed-length sessions, a recap and a next-step suggestion.
// Every number comes from the server (/practice/paths, /sessions, /next); nothing is scored in the browser.
const GUIDED_PATH_TEXT = {
  baseline: ['Starting skill check', 'Four quick questions, one from each topic. Shows where to begin.'],
  java: ['Java', 'Collections, streams and Optional.'],
  spring: ['Spring', 'Transactions, components and injection.'],
  web: ['Web', 'HTTP methods and status codes.'],
  mongodb: ['MongoDB', 'Indexes, expiry and document identity.']
};
function guidedPathTitle(path) { return t((GUIDED_PATH_TEXT[path.id] || [path.title])[0]); }
async function loadGuidedPractice() {
  const next = $('#guided-next');
  const holder = $('#guided-paths');
  if (!holder) return;
  holder.setAttribute('aria-busy', 'true');
  const [pathsResult, nextResult] = await Promise.allSettled([request('/practice/paths'), request('/practice/next')]);
  holder.setAttribute('aria-busy', 'false');
  if (pathsResult.status !== 'fulfilled') {
    holder.replaceChildren(node('p', 'Guided practice could not be loaded. Reopen Practice to retry.', 'empty'),
      button('Try again', () => run(loadGuidedPractice), 'button secondary small'));
    next.classList.add('hidden');
    return;
  }
  const paths = pathsResult.value;
  if (nextResult.status === 'fulfilled') {
    const suggestion = nextResult.value;
    const target = paths.find((path) => path.id === suggestion.pathId);
    next.replaceChildren();
    if (target) {
      next.append(node('p', suggestion.pathId === 'baseline' ? 'Where to start' : 'Practise next', 'guided-next-label'),
        node('strong', suggestion.pathId === 'baseline' ? t('Take the starting skill check') : guidedPathTitle(target), 'guided-next-title'),
        node('p', suggestion.pathId === 'baseline'
          ? 'You have no finished sessions yet. A short check shows which topic needs the most practice.'
          : `${suggestion.correct} of ${suggestion.answered} answers were right in this topic in your recent sessions. It is your lowest score, so it is a good place to practise.`, 'muted'),
        button(suggestion.pathId === 'baseline' ? 'Start the skill check' : 'Practise this topic',
          () => startGuidedSession(target), 'button primary small'));
      next.classList.remove('hidden');
    } else next.classList.add('hidden');
  } else next.classList.add('hidden');
  holder.replaceChildren(...paths.map((path) => {
    const text = GUIDED_PATH_TEXT[path.id] || [path.title, ''];
    const card = node('article', '', `guided-path${path.id === 'baseline' ? ' is-baseline' : ''}`);
    card.append(node('h3', t(text[0])), node('p', t(text[1]), 'muted'),
      node('p', `${path.questionCount} ${path.questionCount === 1 ? t('question') : t('questions')}`, 'guided-count'));
    card.append(button(path.id === 'baseline' ? 'Start the skill check' : 'Start a session',
      () => startGuidedSession(path), 'button secondary small'));
    return card;
  }));
}
async function startGuidedSession(path) {
  const count = path.id === 'baseline' ? path.questionCount : Math.min(4, path.questionCount);
  const session = await request('/practice/sessions', { method: 'POST', body: { pathId: path.id, questionCount: count } });
  state.guided = { path, session, picked: null, reviewing: false };
  renderGuidedSession();
  $('#guided-session').scrollIntoView({ behavior: 'smooth', block: 'start' });
}
function guidedOptionText(question, optionId) {
  return question.options.find((option) => option.id === optionId)?.text || optionId;
}
function renderGuidedSession() {
  const box = $('#guided-session');
  const guided = state.guided;
  if (!guided) { box.classList.add('hidden'); box.replaceChildren(); return; }
  const { session, path } = guided;
  box.classList.remove('hidden');
  $('#guided-paths').classList.add('hidden');
  $('#guided-next').classList.add('hidden');
  const answered = new Map(session.results.map((result) => [result.questionId, result]));
  const total = session.questions.length;
  if (session.complete) { renderGuidedRecap(box, session, path, answered); return; }
  const question = session.questions.find((entry) => !answered.has(entry.id));
  const number = answered.size + 1;
  const head = node('div', '', 'guided-head');
  head.append(node('strong', guidedPathTitle(path)),
    node('span', `${t('Question')} ${number} ${t('of')} ${total}`, 'muted'));
  const progress = document.createElement('progress');
  progress.max = total; progress.value = answered.size;
  progress.setAttribute('aria-label', t('Session progress'));
  const form = node('form', '', 'stack');
  const fieldset = node('fieldset'); fieldset.append(node('legend', question.prompt));
  for (const option of question.options) {
    const label = node('label', '', 'guided-option');
    const input = document.createElement('input');
    input.type = 'radio'; input.name = 'guided-option'; input.value = option.id; input.required = true;
    label.append(input, node('span', option.text));
    fieldset.append(label);
  }
  const submit = node('button', 'Check answer', 'button primary'); submit.type = 'submit';
  const leave = button('Leave this session', () => { state.guided = null; renderGuidedSession(); loadGuidedPractice(); }, 'button quiet small');
  form.append(fieldset, node('div', '', 'form-row'));
  form.lastChild.append(submit, leave);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    run(async () => {
      const chosen = form.querySelector('input[name="guided-option"]:checked');
      if (!chosen) return;
      submit.disabled = true;
      try {
        guided.session = await request(`/practice/sessions/${encodeURIComponent(session.id)}/answers`,
          { method: 'POST', body: { questionId: question.id, optionId: chosen.value } });
        const result = guided.session.results.find((entry) => entry.questionId === question.id);
        notice(result?.correct ? 'Correct.' : 'Not quite. You will see the answers in the recap.');
        renderGuidedSession();
        $('#guided-session').scrollIntoView({ behavior: 'smooth', block: 'start' });
      } finally { submit.disabled = false; }
    });
  });
  box.replaceChildren(head, progress, form);
  form.querySelector('input')?.focus({ preventScroll: true });
}
function renderGuidedRecap(box, session, path, answered) {
  const total = session.questions.length;
  const percent = total ? Math.round((session.correctCount / total) * 100) : 0;
  const summary = node('div', '', 'guided-score');
  summary.append(node('strong', `${session.correctCount} / ${total}`, 'guided-score-number'),
    node('span', percent >= 75 ? 'Strong result. Keep it up.' : percent >= 50 ? 'A good start. A little more practice will help.' : 'This topic needs more practice. Try another session.'));
  const rows = session.questions.map((question) => {
    const result = answered.get(question.id);
    const row = node('div', '', `guided-recap-row ${result?.correct ? 'is-right' : 'is-wrong'}`);
    row.append(node('strong', question.prompt),
      node('p', `${t('Your answer')}: ${guidedOptionText(question, result?.selectedOptionId)}`, 'muted'));
    if (!result?.correct && result?.correctOptionId) {
      row.append(node('p', `${t('Right answer')}: ${guidedOptionText(question, result.correctOptionId)}`, 'guided-right'));
    }
    if (result?.explanation) row.append(node('p', result.explanation, 'muted'));
    row.append(node('span', result?.correct ? 'Correct' : 'Review this', 'guided-flag'));
    return row;
  });
  const actions = node('div', '', 'form-row');
  actions.append(
    button('Practise again', () => run(() => startGuidedSession(path)), 'button secondary'),
    button('Back to guided practice', () => { state.guided = null; renderGuidedSession(); $('#guided-paths').classList.remove('hidden'); run(loadGuidedPractice); }, 'button primary'));
  box.replaceChildren(node('h3', `${t('Recap')} · ${guidedPathTitle(path)}`), summary, ...rows, actions);
}

async function loadPractice() {
  // When the pilot is on it owns this screen entirely; the code below is what
  // everyone else still sees, unchanged.
  if (pilotRequested() && await mountPracticePilot()) return;
  if (!state.guided) { $('#guided-paths')?.classList.remove('hidden'); void loadGuidedPractice(); }
  const [questionsResult, usageResult, historyResult] = await Promise.allSettled([
    request('/practice/questions'), request('/practice/usage'), request('/practice/answers/mine?page=0')
  ]);
  if (questionsResult.status === 'fulfilled') state.practiceQuestions = questionsResult.value;
  else setPracticeGenerationState('error', 'Questions could not be loaded', 'Use Reload questions below to retry.');
  const questions = state.practiceQuestions;
  const select = $('#practice-question');
  const previous = select.value;
  const placeholder = node('option', 'Choose a question');
  placeholder.value = '';
  select.replaceChildren(placeholder, ...questions.map((question) => {
    const option = node('option', `${question.topic} — ${question.prompt}`);
    option.value = question.id;
    return option;
  }));
  if (questions.some((question) => question.id === previous)) select.value = previous;
  else select.value = questions[0]?.id || '';
  if (usageResult.status === 'fulfilled') {
    const usage = usageResult.value;
    state.practiceRemaining = usage.remaining;
    state.practiceGenerationRemaining = usage.generationRemaining;
    renderPracticeUsage(usage);
    $('#practice-generate').disabled = usage.generationRemaining === 0;
    $('#practice-submit').disabled = usage.remaining === 0 || questions.length === 0;
  } else {
    $('#practice-usage').textContent = 'Today’s allowance could not be checked. Reopen Practice to retry.';
    $('#practice-generate').disabled = true;
    $('#practice-submit').disabled = true;
  }
  if (historyResult.status === 'fulfilled') state.practiceAnswers = rowsOf(historyResult.value);
  const rows = state.practiceAnswers.map((answer) => item(
    state.practiceQuestions.find((question) => question.id === answer.questionId)?.prompt || answer.questionId,
    `${answer.correct ? 'Correct' : 'Review this'} · ${when(answer.answeredAt)}`));
  setList('#practice-history', rows, historyResult.status === 'fulfilled'
    ? 'No practice answers yet. Choose a question to begin.' : 'Answer history could not be loaded. Reopen Practice to retry.');
  renderPracticeQuestion();
}

function setPracticeGenerationState(kind, message, detail) {
  const panel = $('#practice-generation-status');
  panel.className = `practice-generation-status${kind ? ` is-${kind}` : ''}`;
  panel.setAttribute('aria-busy', kind === 'busy' ? 'true' : 'false');
  $('#practice-generation-message').textContent = message;
  $('#practice-generation-detail').textContent = detail;
  $('#practice-browse').classList.toggle('hidden', kind !== 'busy');
}
function renderPracticeUsage(usage) {
  $('#practice-usage').textContent = `${usage.generated}/${usage.generationLimit} AI questions created (${usage.generationRemaining} left) · ${usage.used}/${usage.limit} answers checked (${usage.remaining} left) today`;
}
$('#practice-browse').addEventListener('click', () => {
  const search = $('#practice-question-filter');
  search.scrollIntoView({ behavior: 'smooth', block: 'center' });
  search.focus({ preventScroll: true });
});

function renderPracticeQuestionList() {
  const list = $('#practice-question-list');
  const filter = $('#practice-question-filter').value.trim().toLocaleLowerCase();
  const selected = $('#practice-question').value;
  const answeredIds = new Set(state.practiceAnswers.map((answer) => answer.questionId));
  const matches = state.practiceQuestions.filter((question) =>
    `${question.topic} ${question.prompt}`.toLocaleLowerCase().includes(filter));
  list.replaceChildren(...matches.map((question) => {
    const control = node('button', '', 'practice-question-item');
    control.type = 'button';
    control.classList.toggle('selected', question.id === selected);
    if (question.id === selected) control.setAttribute('aria-current', 'true');
    control.append(node('span', question.topic, 'practice-question-topic'),
      node('strong', question.prompt, 'practice-question-preview'),
      node('span', answeredIds.has(question.id) ? 'Answered recently' : 'Select question', 'practice-question-state'));
    control.addEventListener('click', () => {
      $('#practice-question').value = question.id;
      renderPracticeQuestion();
      $('#practice-prompt').focus();
    });
    return control;
  }));
  if (!matches.length) list.append(node('p', filter ? 'No questions match this search.' : 'No questions yet. Create one or reload the starter examples.', 'muted'));
}

function renderPracticeQuestion() {
  const question = state.practiceQuestions.find((entry) => entry.id === $('#practice-question').value);
  $('#practice-empty').classList.toggle('hidden', !!question);
  $('#practice-answer-form').classList.toggle('hidden', !question);
  const fieldset = $('#practice-options');
  fieldset.replaceChildren();
  const legend = node('legend', question?.prompt || '');
  legend.id = 'practice-prompt';
  legend.tabIndex = -1;
  fieldset.append(legend);
  for (const option of question?.options || []) {
    const label = node('label', '', 'practice-option');
    const input = node('input');
    input.type = 'radio'; input.name = 'optionId'; input.value = option.id; input.required = true;
    label.append(input, node('span', option.text));
    fieldset.append(label);
  }
  state.practiceRequestId = crypto.randomUUID();
  $('#practice-submit').disabled = state.practiceRemaining === 0 || !question;
  $('#practice-result').classList.add('hidden');
  $('#practice-result').replaceChildren();
  renderPracticeQuestionList();
}

$('#practice-question').addEventListener('change', renderPracticeQuestion);
$('#practice-question-filter').addEventListener('input', renderPracticeQuestionList);
$('#practice-retry').addEventListener('click', () => run(loadPractice));
$('#practice-generate-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.currentTarget;
    const button = $('#practice-generate');
    button.disabled = true;
    button.textContent = 'Creating…';
    const data = Object.fromEntries(new FormData(form));
    setPracticeGenerationState('busy', `Creating a ${data.difficulty.toLowerCase()} question about ${data.topic}`,
      'You can browse and answer your existing questions while this runs.');
    try {
      const question = await request('/practice/questions/generate', { method: 'POST', body: data });
      state.practiceQuestions = [question, ...state.practiceQuestions.filter((entry) => entry.id !== question.id)];
      const select = $('#practice-question');
      select.replaceChildren(...state.practiceQuestions.map((entry) => {
        const option = node('option', `${entry.topic} — ${entry.prompt}`);
        option.value = entry.id;
        return option;
      }));
      select.value = question.id;
      renderPracticeQuestion();
      setPracticeGenerationState('success', 'Question ready', 'Choose an answer below, or pick another question from the list.');
      const [questionsResult, usageResult] = await Promise.allSettled([
        request('/practice/questions'), request('/practice/usage')
      ]);
      if (questionsResult.status === 'fulfilled') {
        state.practiceQuestions = questionsResult.value;
        renderPracticeQuestionList();
      }
      if (usageResult.status === 'fulfilled') {
        const usage = usageResult.value;
        state.practiceGenerationRemaining = usage.generationRemaining;
        state.practiceRemaining = usage.remaining;
        renderPracticeUsage(usage);
        button.disabled = usage.generationRemaining === 0;
      } else {
        $('#practice-usage').textContent = 'Question created. Reopen Practice to confirm today’s remaining allowance.';
        button.disabled = true;
      }
    } catch (error) {
      const feedback = {
        AI_NOT_CONFIGURED: ['AI is not enabled here', 'Ask the operator to configure a provider. Your personal allowance was not used.'],
        AI_PROVIDER_RATE_LIMIT: ['AI provider limit reached', 'This is not your personal 20-question limit. Try later or answer an existing question.'],
        AI_PROVIDER_AUTH: ['AI provider access needs attention', 'The operator needs to check the key or permissions. Your personal allowance was not used.'],
        AI_PROVIDER_BILLING: ['AI provider account needs attention', 'The operator needs to check provider billing. Your personal allowance was not used.'],
        AI_PROVIDER_CONFIGURATION: ['AI model setup needs attention', 'The operator needs to check the selected model and request settings.'],
        AI_PROVIDER_UNAVAILABLE: ['AI provider did not respond', 'Try again later. Your personal allowance was not used.'],
        PRACTICE_AI_PLATFORM_CAPACITY: ['Today’s platform AI capacity is used', 'Your personal allowance was not used. Existing practice questions are still available.'],
        PRACTICE_AI_LIMIT_REACHED: ['Your 20 AI questions are used today', 'Your personal generation allowance resets at 00:00 UTC. You can answer existing questions.']
      }[error.code] || ['Question creation failed', 'Check the error above, or answer an existing question.'];
      setPracticeGenerationState('error', feedback[0], feedback[1]);
      button.disabled = false;
      throw error;
    } finally {
      button.textContent = 'Create a question';
    }
  });
});
$('#practice-answer-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const form = event.currentTarget;
    const optionId = new FormData(form).get('optionId');
    if (!optionId) return;
    const submit = $('#practice-submit');
    submit.disabled = true;
    try {
      const result = await request('/practice/answers', { method: 'POST', body: {
        requestId: state.practiceRequestId, questionId: $('#practice-question').value, optionId
      } });
      const answer = state.practiceQuestions.find((question) => question.id === result.questionId);
      const correct = answer?.options.find((option) => option.id === result.correctOptionId)?.text || 'See explanation';
      const box = $('#practice-result');
      box.replaceChildren(node('strong', result.correct ? 'Correct.' : `Not quite. Correct answer: ${correct}.`),
        node('p', result.explanation));
      box.classList.remove('hidden');
      const refreshed = await Promise.allSettled([
        request('/practice/usage'), request('/practice/answers/mine?page=0')
      ]);
      if (refreshed[0].status === 'fulfilled') {
        const usage = refreshed[0].value;
        state.practiceRemaining = usage.remaining;
        renderPracticeUsage(usage);
      } else {
        state.practiceRemaining = Math.max(0, state.practiceRemaining - 1);
        $('#practice-usage').textContent = 'Answer saved. Refresh Practice to check today’s remaining limit.';
      }
      if (refreshed[1].status === 'fulfilled') {
        state.practiceAnswers = rowsOf(refreshed[1].value);
        renderPracticeQuestionList();
        setList('#practice-history', state.practiceAnswers.map((answer) => item(
          state.practiceQuestions.find((question) => question.id === answer.questionId)?.prompt || answer.questionId,
          `${answer.correct ? 'Correct' : 'Review this'} · ${when(answer.answeredAt)}`)),
        'No practice answers yet. Choose a question to begin.');
      }
      submit.disabled = true;
    } catch (error) {
      // Keep the same request ID for a retry; the backend will not charge it twice.
      submit.disabled = false;
      if (error.code === 'PRACTICE_LIMIT_REACHED') $('#practice-usage').textContent = 'Today’s free practice limit is used.';
      throw error;
    }
  });
});

function loadWorkspaceAccess() {
  setList('#workspace-access-list', state.organizations.map((organization) => item(
    organization.name,
    `${organization.type === 'ACADEMY' ? 'Academy' : 'Talent'} · ${organization.owner ? 'Owner' : roleTitle(organization.myRole)}`,
    [button(organization.id === state.organization?.id ? 'Current workspace' : 'Open workspace',
      async () => {
        if (organization.id === state.organization?.id) return;
        state.organization = organization;
        await setOrganization();
      })])), 'You have not joined a workspace yet.');
}

function updateOnboardingIntent() {
  const intent = $('#onboarding-intent').value;
  const guidance = {
    learner: 'Open Practice now. To take a test from an Academy or a company, use the code or invitation they gave you.',
    instructor: 'Join an Academy when you are invited. If you start a new Academy, you become its owner and can invite other instructors.',
    recruiter: 'Join a company when you are invited. If you start a new one, you become its owner and can invite recruiters.',
    owner: 'Start an Academy to teach and give certificates, or a Talent organization to hire.'
  };
  $('#onboarding-next-step').textContent = t(guidance[intent] || 'Choose one above. You can change it any time.');
  $('#onboarding-join').classList.toggle('hidden', !['learner', 'instructor', 'recruiter'].includes(intent));
  $('#onboarding-create').classList.toggle('hidden', !['instructor', 'recruiter', 'owner'].includes(intent));
  if (intent === 'instructor') $('#create-org-form [name="type"]').value = 'ACADEMY';
  if (intent === 'recruiter') $('#create-org-form [name="type"]').value = 'TALENT';
}
$('#onboarding-intent').addEventListener('change', updateOnboardingIntent);
$('#start-practice').addEventListener('click', () => run(() => showScreen('practice')));
$('#start-join').addEventListener('click', () => {
  $('#onboarding-intent').value = 'learner';
  updateOnboardingIntent();
  $('#onboarding-join').scrollIntoView({ behavior: 'smooth', block: 'start' });
});
$('#start-organize').addEventListener('click', () => {
  $('#onboarding-intent').value = 'owner';
  updateOnboardingIntent();
  $('#onboarding-create').scrollIntoView({ behavior: 'smooth', block: 'start' });
});
$('#onboarding-open-practice').addEventListener('click', () => run(() => showScreen('practice')));
updateOnboardingIntent();
for (const form of [$('#join-code-form'), $('#workspace-join-code-form')]) {
  form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
    const code = new FormData(form).get('code').trim();
    const submit = form.querySelector('button[type="submit"]'); submit.disabled = true;
    try {
      const joined = await request('/join-codes/accept', { method: 'POST', body: { code } });
      form.reset();
      await loadOrganizations(joined.id);
      notice(`Joined ${joined.name} as ${roleTitle(joined.myRole)}.`);
    } catch (error) { fieldErrors(form, error); throw error; }
    finally { submit.disabled = false; }
  }); });
}
$('#create-join-code').addEventListener('click', () => run(async () => {
  const created = await request(ownOrg('/join-codes'), { method: 'POST' });
  const output = $('#join-code-once');
  output.textContent = `Share privately: ${created.code} · Expires ${new Date(created.expiresAt).toLocaleString()}. This code is shown only once.`;
  output.classList.remove('hidden');
  await loadJoinCodes();
}));
for (const form of [$('#create-org-form'), $('#workspace-create-org-form')]) {
  form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
    const values = new FormData(form);
    const submit = form.querySelector('button[type="submit"]'); submit.disabled = true;
    try {
      const created = await request('/organizations', { method: 'POST',
        body: { name: values.get('name'), type: values.get('type') } });
      form.reset();
      await loadOrganizations(created.id);
      notice(`Created ${created.name}. You are its owner.`);
    } catch (error) { fieldErrors(form, error); throw error; }
    finally { submit.disabled = false; }
  }); });
}
$('#organization-settings-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/settings'), { method: 'PUT', body: {
    defaultResultReleasePolicy: form.get('defaultResultReleasePolicy')
  } });
  await loadOrganizationSettings(); notice('Result release default saved.');
}); });
$('#add-member-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  await request(ownOrg('/members'), { method: 'POST', body: { email: form.get('email'), role: form.get('role') } });
  element.reset(); await loadMembers(); notice('Member added.');
}); });
$('#invite-member-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  await request(ownOrg('/invitations'), { method: 'POST',
    body: { email: form.get('email').trim(), role: form.get('role') } });
  element.reset();
  await loadMemberInvitations(0);
  notice(mailDeliveryMode === 'SMTP'
    ? 'Invitation sent. If it does not arrive, ask them to check spam, or send it again.'
    : 'Invitation recorded, but this run is not using SMTP. The link is printed in the application console; configure MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD and MAIL_FROM for real delivery.');
}); });
$('#transfer-owner-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const email = new FormData(event.currentTarget).get('email').trim();
  const target = await request(ownOrg(`/members/admins/lookup?email=${encodeURIComponent(email)}`));
  if (!await confirmDialog(`Transfer workspace ownership to ${target.displayName} (${target.email})? You will no longer be the owner.`)) return;
  await request(ownOrg('/owner'), { method: 'PUT', body: { targetUserId: target.userId } });
  await loadOrganizations();
  notice('Workspace ownership transferred.');
}); });
function updateQuestionTypeFields() {
  const shortAnswer = $('#question-form select[name="type"]').value === 'SHORT_ANSWER';
  $('#question-option-grid').classList.toggle('hidden', shortAnswer);
  $('#question-correct-options').classList.toggle('hidden', shortAnswer);
  for (const input of $('#question-option-grid').querySelectorAll('input')) {
    input.required = !shortAnswer;
    input.disabled = shortAnswer;
  }
  for (const input of $('#question-correct-options').querySelectorAll('input')) input.disabled = shortAnswer;
}
function updateQuestionEditor() {
  const form = $('#question-form');
  const editing = state.editingQuestion;
  const heading = form.closest('.panel')?.querySelector('h2');
  if (heading) heading.textContent = editing ? `Edit question draft · ${editing.topic}` : 'Create a question';
  form.querySelector('button[type="submit"]').textContent = editing ? 'Save draft changes' : 'Save draft';
  let cancel = form.querySelector('[data-cancel-question-edit]');
  if (!cancel) {
    cancel = button('Cancel editing', () => resetQuestionEditor(), 'button quiet');
    cancel.dataset.cancelQuestionEdit = 'true'; form.append(cancel);
  }
  cancel.classList.toggle('hidden', !editing);
  updateQuestionTypeFields();
}
function resetQuestionEditor() {
  state.editingQuestion = null;
  $('#question-form').reset();
  updateQuestionEditor();
}
function editQuestion(question) {
  const optionIds = (question.options || []).map((option) => option.id);
  if (question.type !== 'SHORT_ANSWER' &&
      (optionIds.length !== 4 || optionIds.join(',') !== 'A,B,C,D')) {
    throw new Error('This draft uses custom option IDs. Edit it through the questions API; this form supports A–D.');
  }
  state.editingQuestion = question;
  const form = $('#question-form');
  form.elements.topic.value = question.topic;
  form.elements.prompt.value = question.prompt;
  form.elements.type.value = question.type;
  form.elements.difficulty.value = question.difficulty || 'MEDIUM';
  for (const id of ['A', 'B', 'C', 'D']) {
    form.elements[id].value = question.options?.find((option) => option.id === id)?.text || '';
  }
  for (const input of form.querySelectorAll('input[name="correct"]')) {
    input.checked = (question.correctOptionIds || []).includes(input.value);
  }
  updateQuestionEditor();
  form.scrollIntoView({ behavior: 'smooth', block: 'start' });
  form.elements.prompt.focus({ preventScroll: true });
}
$('#question-form select[name="type"]').addEventListener('change', updateQuestionTypeFields);
updateQuestionTypeFields();
$('#question-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const element = event.currentTarget;
  const form = new FormData(element); const correctOptionIds = form.getAll('correct');
  const shortAnswer = form.get('type') === 'SHORT_ANSWER';
  if (!shortAnswer && !correctOptionIds.length) throw new Error('Choose at least one correct option.');
  if (form.get('type') === 'SINGLE_CHOICE' && correctOptionIds.length !== 1) throw new Error('Single choice needs exactly one correct option.');
  const editing = state.editingQuestion;
  const submit = element.querySelector('button[type="submit"]'); submit.disabled = true;
  try {
    await request(ownOrg(editing ? `/questions/${encodeURIComponent(editing.id)}/draft` : '/questions'),
      { method: editing ? 'PUT' : 'POST', body: {
    topic: form.get('topic'), prompt: form.get('prompt'), type: form.get('type'),
    difficulty: form.get('difficulty'),
    options: shortAnswer ? [] : ['A', 'B', 'C', 'D'].map((id) => ({ id, text: form.get(id) })), correctOptionIds
    } });
    resetQuestionEditor(); await loadQuestions();
    notice(editing ? 'Question draft updated. Review and approve when ready.' : 'Question saved as a draft.');
  } catch (error) { fieldErrors(element, error); throw error; }
  finally { submit.disabled = false; }
}); });
$('#ai-draft-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const element = event.currentTarget;
  const form = new FormData(element);
  const control = element.querySelector('button[type="submit"]');
  if (control) control.disabled = true;
  try {
    notice(t('Generating a draft… this can take up to a minute.'));
    await request(ownOrg('/ai/question-drafts'), { method: 'POST',
      body: { topic: form.get('topic'), brief: form.get('brief'), type: form.get('type'),
        difficulty: form.get('difficulty') } });
    element.reset(); await loadQuestions(); notice('AI draft saved. Review before approval.');
  } catch (error) {
    const explanation = explainAiFailure(error);
    if (!explanation) throw error;
    notice(explanation, true);
  } finally { if (control) control.disabled = false; }
}); });
$('#assessment-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const element = event.currentTarget;
  const form = new FormData(element); const questionIds = [...state.selectedQuestionIds];
  if (!questionIds.length) throw new Error('Select at least one approved question.');
  const editing = state.editingAssessment;
  if (editing?.status === 'PUBLISHED' && !await confirmDialog(
    `Publish a new version of "${editing.title}"?\n\nExisting assignments keep their original version. New assignments use the new version. This does not consume another assessment plan slot.`)) return;
  const submit = element.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await request(ownOrg(editing ? `/assessments/${encodeURIComponent(editing.id)}/${editing.status === 'DRAFT' ? 'draft' : 'versions'}` : '/assessments'), {
      method: editing?.status === 'DRAFT' ? 'PUT' : 'POST',
      extraHeaders: editing ? { 'If-Match': String(editing.revision) } : {}, body: {
    title: form.get('title'), instructions: form.get('instructions'),
    durationMinutes: Number(form.get('durationMinutes')),
    passingPercentage: Number(form.get('passingPercentage')), resultReleasePolicy: form.get('resultReleasePolicy') || null, questionIds
    } });
    const message = !editing ? 'Assessment draft created.' : editing.status === 'DRAFT'
      ? 'Draft changes saved.' : 'New assessment version published. Existing assignments are unchanged.';
    state.editingAssessment = null; element.reset(); state.selectedQuestionIds.clear(); updateAssessmentEditor();
    await loadApprovedQuestions(); await loadAssessments();
    if (editing?.status === 'PUBLISHED') await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
    notice(message);
  } catch (error) { fieldErrors(element, error); throw error; }
  finally { submit.disabled = false; }
}); });
$('#assignment-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  if (!form.get('assessmentId')) throw new Error('Publish an assessment first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  const assignment = await request(ownOrg(`/assessments/${encodeURIComponent(form.get('assessmentId'))}/assignments`),
    { method: 'POST', body: { userId: form.get('userId').trim(), attemptsAllowed: Number(form.get('attemptsAllowed')),
      availableFrom, availableUntil } });
  element.reset();
  let recent = $('#recent-assignment');
  if (!recent) {
    recent = node('div', '', 'item-list'); recent.id = 'recent-assignment';
    $('#assignment-form').after(recent);
  }
  recent.replaceChildren(item('Assignment created', `ID ${assignment.id} · recipient ${assignment.assignedUserId}`,
    [reminderButton(assignment.id)]));
  notice('Assessment assigned. A reminder is optional; no email has been sent yet.');
}); });
function reminderButton(assignmentId) {
  const control = button('Queue email reminder', async () => {
    if (!await confirmDialog(`Queue an assessment reminder?\n\nAssignment ${assignmentId}\nAt most one reminder per UTC day is queued. Delivery is not guaranteed.`)) return;
    control.disabled = true;
    try {
      const result = await request(ownOrg(`/assignments/${encodeURIComponent(assignmentId)}/reminders`),
        { method: 'POST' });
      notice(result.queued ? 'Reminder queued; delivery is pending.' : 'A reminder was already queued today.');
    } finally { control.disabled = false; }
  });
  return control;
}
$('#inspect-attempt').addEventListener('click', () => run(async () => {
  const id = $('#review-attempt-id').value.trim(); if (!id) throw new Error('Enter an attempt ID.');
  await showStaffReview(id);
}));
$('#release-result').addEventListener('click', () => run(async () => {
  const id = $('#review-attempt-id').value.trim(); if (!id) throw new Error('Enter an attempt ID.');
  const control = $('#release-result');
  if (!await confirmDialog(`Release the result for attempt ${id}?\n\nReleasing queues a result email to the participant.`)) return;
  control.disabled = true;
  try {
    const result = await request(ownOrg(`/attempts/${encodeURIComponent(id)}/release`), { method: 'POST' });
    $('#review-output').textContent = `Released: ${result.percentage}% · ${result.passed ? 'Passed' : 'Not passed'} · the participant can now see this result`;
    $('#review-output').classList.remove('hidden');
    notice('Result released. A result email is queued for the participant.');
    if (state.activeReviewAttemptId === id) await showStaffReview(id);
    await loadReviewQueue(0);
  } finally { control.disabled = false; }
}));
$('#issue-certificate').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-attempt-id').value.trim(); if (!id) throw new Error('Enter an attempt ID.');
  const control = $('#issue-certificate');
  control.disabled = true;
  try {
    const certificate = await request(ownOrg(`/attempts/${encodeURIComponent(id)}/certificate`), { method: 'POST' });
    $('#review-certificate-id').value = certificate.id;
    $('#review-output').textContent = `Certificate ID: ${certificate.id} · Verification code: ${certificate.verificationCode}`
      + `\nPublic verification page: /certificates/${certificate.verificationCode}`
      + '\nA learner email is queued; this screen cannot confirm delivery.';
    $('#review-output').classList.remove('hidden');
    notice('Certificate issued. A learner email is queued.');
  } finally { control.disabled = false; }
}));
$('#revoke-certificate').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-certificate-id').value.trim(); if (!id) throw new Error('Enter a certificate ID.');
  if (!await confirmDialog(`Revoke certificate ${id}?\n\nRevocation is terminal: its public code will verify as invalid and this attempt cannot be issued a new certificate.`)) return;
  const control = $('#revoke-certificate');
  control.disabled = true;
  try {
    const certificate = await request(ownOrg(`/certificates/${encodeURIComponent(id)}/revoke`), { method: 'POST' });
    $('#review-output').textContent = `Certificate ${certificate.id} revoked. Its public code now verifies as invalid.`;
    $('#review-output').classList.remove('hidden');
    notice('Certificate revoked.');
  } finally { control.disabled = false; }
}));
async function loadCertificateAudit(id, pageNumber = 0) {
  const organizationId = state.organization?.id;
  const events = await request(ownOrg(`/certificates/${encodeURIComponent(id)}/audit?page=${pageNumber}`));
  if (state.organization?.id !== organizationId || $('#review-certificate-id').value.trim() !== id) return;
  $('#review-output').textContent = (events.content || []).length
    ? events.content.map((entry) => `${entry.action.replaceAll('_', ' ')} · ${entry.actorUserId} · ${new Date(entry.occurredAt).toLocaleString()}`).join('\n')
    : 'No audit events recorded for this certificate.';
  $('#review-output').classList.remove('hidden');
  renderPageControls('#certificate-audit-pagination', events,
    (nextPage) => loadCertificateAudit(id, nextPage));
}
$('#certificate-audit').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-certificate-id').value.trim(); if (!id) throw new Error('Enter a certificate ID.');
  await loadCertificateAudit(id);
}));
$('#review-certificate-id').addEventListener('input', () => {
  $('#certificate-audit-pagination').replaceChildren();
});
$('#feedback-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  const attemptId = form.get('attemptId').trim();
  const control = event.currentTarget.querySelector('button[type="submit"]');
  if (control) control.disabled = true;
  try {
    const feedback = await request(ownOrg(`/academy/attempts/${encodeURIComponent(attemptId)}/feedback`),
      { method: 'PUT', body: { summary: form.get('summary').trim(), nextSteps: form.get('nextSteps').trim() } });
    $('#feedback-output').textContent = `Draft saved for attempt ${feedback.attemptId}.`;
    $('#feedback-output').classList.remove('hidden'); notice('Feedback draft saved.');
  } finally { if (control) control.disabled = false; }
}); });
$('#publish-feedback').addEventListener('click', () => run(async () => {
  const attemptId = new FormData($('#feedback-form')).get('attemptId').trim();
  if (!attemptId) throw new Error('Enter an attempt ID first.');
  if (!await confirmDialog(`Publish this feedback for attempt ${attemptId}?\n\nPublished feedback is immutable and the learner can read it immediately. The result must already be released.`)) return;
  const control = $('#publish-feedback');
  control.disabled = true;
  try {
    const feedback = await request(ownOrg(`/academy/attempts/${encodeURIComponent(attemptId)}/feedback/publish`),
      { method: 'POST' });
    $('#feedback-output').textContent = `Feedback published for attempt ${feedback.attemptId}. The learner can now read it; email delivery is not confirmed.`;
    $('#feedback-output').classList.remove('hidden');
    try { await loadFeedbackAudit(attemptId, 0); }
    catch (_) { setList('#feedback-audit-list', [], 'Feedback is published; audit history could not be loaded. Retry the history view.'); }
    notice('Feedback published. The learner can view it; email delivery is not confirmed.');
  } finally { control.disabled = false; }
}));
function ensureFeedbackAuditPanel() {
  if ($('#feedback-audit-panel')) return;
  const panel = node('div', '', '');
  panel.id = 'feedback-audit-panel';
  panel.innerHTML = `<button id="feedback-audit-refresh" class="button quiet small" type="button">View publish history</button>
    <div id="feedback-audit-list" class="item-list"></div><div id="feedback-audit-pagination"></div>`;
  $('#feedback-output').after(panel);
  $('#feedback-audit-refresh').addEventListener('click', () => run(async () => {
    const attemptId = $('#feedback-form').elements.attemptId.value.trim();
    if (!attemptId) throw new Error('Enter an attempt ID first.');
    await loadList('#feedback-audit-list', () => loadFeedbackAudit(attemptId, 0),
      'Publish history could not be loaded.');
  }));
  $('#feedback-form').elements.attemptId.addEventListener('input', () => {
    $('#feedback-audit-list').replaceChildren();
    $('#feedback-audit-pagination').replaceChildren();
  });
}
async function loadFeedbackAudit(attemptId, pageNumber) {
  const orgId = state.organization?.id;
  if (!orgId || state.organization.type !== 'ACADEMY'
      || !['ORG_ADMIN', 'INSTRUCTOR'].includes(state.organization.myRole)) return;
  const page = await request(`/organizations/${encodeURIComponent(orgId)}/academy/attempts/${encodeURIComponent(attemptId)}/feedback/audit?page=${pageNumber}`);
  if (state.organization?.id !== orgId || $('#feedback-form').elements.attemptId.value.trim() !== attemptId) return;
  setList('#feedback-audit-list', (page.content || []).map((event) => item(
    event.action.replaceAll('_', ' '),
    `By ${actorName(event)} · ${actorIdNote(event)} · ${when(event.occurredAt)}`)),
  'No published feedback event recorded for this attempt.');
  renderPageControls('#feedback-audit-pagination', page,
    (nextPage) => loadFeedbackAudit(attemptId, nextPage));
}
$('#academy-course-select').addEventListener('change', () => run(loadAcademyBatches));
$('#academy-batch-select').addEventListener('change', () => run(async () => {
  await loadAcademyBatchLearners(); await loadBatchAssessments();
}));
$('#course-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  await request(ownOrg('/academy/courses'), { method: 'POST',
    body: { name: form.get('name').trim(), description: form.get('description').trim() } });
  element.reset(); await loadAcademyCatalog(); notice('Course created.');
}); });
$('#batch-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const courseId = $('#academy-course-select').value;
  if (!courseId) throw new Error('Create a course first.');
  const form = new FormData(element);
  const startsAt = new Date(form.get('startsAt')).toISOString();
  const endsAt = new Date(form.get('endsAt')).toISOString();
  if (endsAt <= startsAt) throw new Error('Batch end must follow start.');
  await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches`), { method: 'POST',
    body: { name: form.get('name').trim(), startsAt, endsAt } });
  element.reset(); await loadAcademyBatches(); notice('Batch created.');
}); });
$('#enrollment-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  if (!courseId || !batchId) throw new Error('Create and select a batch first.');
  const form = new FormData(element);
  await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/learners`),
    { method: 'POST', body: { learnerUserId: form.get('learnerUserId').trim() } });
  element.reset(); await loadAcademyBatchLearners(); notice('Learner enrolled.');
}); });
$('#batch-assessment-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  const batch = state.academyBatches.find((item) => item.id === batchId);
  if (!courseId || !batch) throw new Error('Select a batch first.');
  const form = new FormData(element);
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom || availableFrom < batch.startsAt || availableUntil > batch.endsAt)
    throw new Error('Assessment availability must be within the batch dates.');
  await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/assessments`),
    { method: 'POST', body: { assessmentId: form.get('assessmentId'),
      attemptsAllowed: Number(form.get('attemptsAllowed')), availableFrom, availableUntil } });
  element.reset(); await loadBatchAssessments(); notice('Assessment assigned to batch learners.');
}); });
$('#conversation-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const partnerUserId = new FormData(event.currentTarget).get('partnerUserId');
  if (!partnerUserId) throw new Error('Choose an instructor or learner first.');
  const conversation = await request(ownOrg('/messaging/conversations'),
    { method: 'POST', body: { partnerUserId } });
  await loadMessaging(); await openConversation(conversation);
}); });
$('#message-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const conversationId = state.activeConversationId;
  if (!conversationId) throw new Error('Open a conversation first.');
  const body = new FormData(element).get('body').trim();
  if (!body) return;
  const send = element.querySelector('button[type="submit"]');
  send.disabled = true;
  try {
    await request(ownOrg(`/messaging/conversations/${encodeURIComponent(conversationId)}/messages`),
      { method: 'POST', body: { body } });
    element.reset();
    element.querySelector('textarea').style.height = '';
    state.messageStickToBottom = true;
    await loadConversationMessages(0);
  } finally { send.disabled = false; }
}); });
// Enter sends, Shift+Enter adds a line, and the box grows with what is typed. An
// input method still composing a character (isComposing) must not be cut short.
$('#message-form textarea').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $('#message-form').requestSubmit();
  }
});
$('#message-form textarea').addEventListener('input', (event) => {
  const box = event.currentTarget;
  box.style.height = 'auto';
  box.style.height = `${Math.min(box.scrollHeight, 140)}px`;
});
$('#campaign-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  await request(ownOrg('/talent/campaigns'), { method: 'POST',
    body: { title: form.get('title'), assessmentId: form.get('assessmentId') } });
  element.reset(); await loadCampaigns(); notice('Hiring campaign created.');
}); });
$('#campaign-invite-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  if (!form.get('campaignId')) throw new Error('Create an open campaign first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  await request(ownOrg(`/talent/campaigns/${encodeURIComponent(form.get('campaignId'))}/invitations`),
    { method: 'POST', body: { userId: form.get('userId').trim(),
      attemptsAllowed: Number(form.get('attemptsAllowed')), availableFrom, availableUntil } });
  element.reset(); notice('Candidate invited to campaign.');
}); });
$('#campaign-email-invite-form').addEventListener('submit', (event) => { event.preventDefault(); const element = event.currentTarget; run(async () => {
  const form = new FormData(element);
  if (!form.get('campaignId')) throw new Error('Create an open campaign first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  await request(ownOrg(`/talent/campaigns/${encodeURIComponent(form.get('campaignId'))}/email-invitations`),
    { method: 'POST', body: { email: form.get('email').trim(),
      attemptsAllowed: Number(form.get('attemptsAllowed')), availableFrom, availableUntil } });
  if (state.emailInvitationCampaignId === form.get('campaignId'))
    await loadEmailInvitations(state.emailInvitationCampaignId);
  element.reset(); notice('Invitation created. The server queues the email; this screen cannot confirm delivery.');
}); });
$('#platform-refresh').addEventListener('click', () => run(loadPlatformView));
$('#platform-inspect').addEventListener('click', () => run(() =>
  inspectPlatformOrganization($('#platform-organization-id').value)));
$('#platform-repair-usage').addEventListener('click', () => run(async () => {
  const id = state.platformOrganizationId;
  if (!id || $('#platform-organization-id').value.trim() !== id)
    throw new Error('Inspect the selected organization before repairing its quota.');
  const name = state.platformOrganizationName ? `"${state.platformOrganizationName}" (${id})` : id;
  if (!await confirmDialog(`Repair provable quota gaps for ${name}?\n\nThe repair changes only counts justified by durable records. The preview above is not a promise of what will change, every run is audited, and ambiguous history still needs manual review.`)) return;
  $('#platform-repair-usage').disabled = true;
  try {
    await request(`/platform/organizations/${encodeURIComponent(id)}/billing/usage-reconciliation`,
      { method: 'POST' });
    await Promise.all([loadPlatformUsageReport(id, state.platformLoadToken),
      loadPlatformUsageAudit(id, 0, state.platformLoadToken), loadPlatformOperations()]);
    notice('Quota repair finished. Check the report and audit for manual follow-up.');
  } finally { $('#platform-repair-usage').disabled = false; }
}));
$('#platform-inspect-user').addEventListener('click', () => run(() =>
  inspectPlatformUser($('#platform-user-id').value)));
$('#platform-grant-role').addEventListener('click', () => run(() => changePlatformRole(true)));
$('#platform-revoke-role').addEventListener('click', () => run(() => changePlatformRole(false)));
// Back and forward move between screens. writeRoute uses replaceState, so a
// tab change does not add a history entry; only a deliberate link does.
window.addEventListener('hashchange', () => {
  const route = readRoute();
  if (!route || !availableScreens.includes(route.screenKey)) return;
  if (route.viewKey === state.view) return;
  run(() => showScreen(route.screenKey, route.viewKey));
});
$('#assessment-versions-close').addEventListener('click', () => hideAssessmentVersions());
$('#order-detail-close').addEventListener('click', () => hideOrderDetail());
$('#overview-refresh').addEventListener('click', () => run(() => loadOverview()));
$('#usage-summary-open').addEventListener('click', () => run(() => showView('billing')));
$('#usage-refresh').addEventListener('click', () => run(() =>
  loadList('#usage-report', () => loadUsageReconciliation(), 'The quota check could not be run.')));
$('#usage-repair').addEventListener('click', () => run(() => repairUsageReconciliation()));
$('#platform-manual-refresh').addEventListener('click', () => run(() => {
  const id = state.platformOrganizationId;
  if (!id) return;
  return Promise.all([
    loadPlatformManualPlan(id, state.platformLoadToken),
    loadPlatformManualPlanAudit(id, 0, state.platformLoadToken)
  ]);
}));
$('#platform-manual-grant-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(submitManualPlanGrant);
});
$('#platform-manual-revoke-form').addEventListener('submit', (event) => {
  event.preventDefault();
  run(submitManualPlanRevoke);
});

// A reload, the Back button, or coming back from another page (the password-reset page, the
// verification page) loads this script from nothing, and the access token only ever lived in
// memory. If a session was open in this browser, ask the server for a fresh access token with
// the HttpOnly cookie, and put the person back on the screen the address names.
async function restoreSession() {
  // A tab that has just handed a payment result to the original tab has nothing more to do.
  if (await billingHandoff) return;
  if (state.accessToken || !sessionHint.get()) return;
  document.body.dataset.restoring = 'true';
  document.body.dataset.restoringLabel = t('Restoring your session…');
  let tokens = null;
  try {
    tokens = await request('/auth/refresh', { method: 'POST', body: {}, auth: false, retry: false,
      extraHeaders: SESSION_MARKER });
  } catch (error) {
    // Only a definite "no" clears the hint. A dropped connection or a busy server (429) says
    // nothing about the session, and the person can simply try again.
    if (error.status === 401) sessionHint.clear();
  } finally {
    delete document.body.dataset.restoring;
    delete document.body.dataset.restoringLabel;
  }
  if (tokens) await run(() => signedIn(tokens));
}
assistant = createAssistant({ request: (path, init) => request(path, init) });
restoreSession();
