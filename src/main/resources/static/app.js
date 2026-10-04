const $ = (selector) => document.querySelector(selector);
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
  versionsAssessment: null, versionsToken: 0,
  view: 'overview' };
const views = ['overview', 'members', 'questions', 'assessments', 'academy', 'academy-batch', 'messages',
  'campaigns', 'talent-campaign', 'attempts', 'assessment-detail', 'certificates', 'platform'];
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
if (billingReturn) {
  history.replaceState(null, '', location.pathname);
  const outcome = { verified: 'returned from the sandbox gateway', cancelled: 'was cancelled at the sandbox gateway',
    failed: 'did not complete at the sandbox gateway' }[billingReturn];
  notice(`${outcome ? `Sandbox checkout ${outcome}.` : `Sandbox checkout returned "${billingReturn}".`} `
    + 'A browser redirect cannot confirm a payment — sign in and use Check payment to read the server-verified result.');
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
  notice.timeout = window.setTimeout(() => area.classList.add('hidden'), error ? 12000 : 7000);
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
const when = (value) => (value ? new Date(value).toLocaleString() : 'not recorded');
let pendingRequests = 0;
function updateBusyIndicator() {
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
      return 'No AI provider is configured, or the configured provider could not be reached. AI drafting is an optional server setting (Gemini or local Ollama); write the question manually meanwhile.';
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
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && state.accessToken) headers.Authorization = `Bearer ${state.accessToken}`;
  pendingRequests += 1; updateBusyIndicator();
  let response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store'
    });
  } catch (_) {
    throw new Error('Could not reach JachAI. Check your connection and try again.');
  } finally {
    pendingRequests -= 1; updateBusyIndicator();
  }
  if (response.status === 401 && auth && retry && state.refreshToken) {
    try {
      const renewed = await request('/auth/refresh', {
        method: 'POST', body: { refreshToken: state.refreshToken }, auth: false, retry: false
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
function showSignedOut() {
  window.clearInterval(state.timer);
  stopMessageRefresh();
  state.accessToken = null; state.refreshToken = null; state.organization = null;
  state.platformAdmin = false; state.platformUserId = null; state.platformOrganizationId = null;
  state.platformTargetUserId = null; state.platformLoadToken++; state.platformUserLoadToken++;
  state.activeAttempt = null; state.activeConversationId = null; state.messagePage = 0;
  state.tutorHistory.clear();
  state.activeReviewAttemptId = null;
  state.emailInvitationCampaignId = null;
  state.emailInvitationLoadToken++;
  state.selectedQuestionIds.clear();
  state.editingAssessment = null;
  state.editingQuestion = null;
  state.versionsAssessment = null;
  state.versionsToken++;
  state.reviewLoadToken++;
  state.messageLoadToken++;
  $('#auth').classList.remove('hidden'); $('#app').classList.add('hidden');
  $('#logout').classList.add('hidden');
}
async function signedIn(tokens) {
  state.accessToken = tokens.accessToken;
  state.refreshToken = tokens.refreshToken;
  $('#auth').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#logout').classList.remove('hidden');
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
async function loadOrganizations() {
  const previous = state.organization?.id;
  const platformAccess = await request('/platform/me');
  state.platformAdmin = platformAccess.platformAdmin;
  state.platformUserId = platformAccess.userId;
  state.organizations = await request('/organizations');
  const select = $('#organization-select');
  select.replaceChildren();
  for (const organization of state.organizations) {
    const option = node('option', organization.name);
    option.value = organization.id;
    select.append(option);
  }
  state.organization = state.organizations.find((item) => item.id === previous) || state.organizations[0] || null;
  if (state.organization) select.value = state.organization.id;
  await setOrganization();
}
async function setOrganization() {
  state.selectedQuestionIds.clear();
  state.editingAssessment = null;
  state.editingQuestion = null;
  hideAssessmentVersions();
  $('#question-form').reset();
  updateQuestionEditor();
  $('#assessment-form').reset();
  updateAssessmentEditor();
  $('#recent-assignment')?.remove();
  state.tutorHistory.clear();
  state.activeAttempt = null;
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
  $('#feedback-form').reset();
  $('#feedback-output').textContent = '';
  $('#feedback-output').classList.add('hidden');
  $('#feedback-audit-list')?.replaceChildren();
  $('#feedback-audit-pagination')?.replaceChildren();
  $('#review-certificate-id').value = '';
  $('#certificate-audit-pagination').replaceChildren();
  $('#campaign-candidates-panel').classList.add('hidden');
  $('#email-invitations-panel').classList.add('hidden');
  $('#conversation-panel').classList.add('hidden');
  $('#empty-org').classList.toggle('hidden', !!state.organization);
  $('#organization-select').classList.toggle('hidden', !state.organization);
  $('#workspace-role').textContent = state.organization
    ? `${state.organization.type === 'ACADEMY' ? 'Academy' : 'Talent'} · ${roleTitle(state.organization.myRole)}`
    : '';
  const nav = $('#workspace-nav'); nav.replaceChildren();
  if (!state.organization) {
    $('#page-title').textContent = 'Start here';
    $('#page-subtitle').textContent = 'Create an Academy or Talent workspace.';
    if (state.platformAdmin) {
      const tab = node('button', 'Platform'); tab.type = 'button'; tab.dataset.view = 'platform';
      tab.addEventListener('click', () => run(() => showView('platform')));
      nav.append(tab);
      await showView('platform');
    }
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
  $('#academy-learner-panel').classList.toggle('hidden', role !== 'LEARNER');
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  // Navigation is grouped by intent so Academy and Talent read as related but distinct products.
  const groups = [];
  const group = (heading, items) => { if (items.length) groups.push([heading, items]); };
  group('', [['overview', 'Overview']]);
  group('Assessment engine', [
    ...(role === 'ORG_ADMIN' ? [['members', 'Members']] : []),
    ...(staff ? [['questions', 'Question bank'], ['assessments', 'Assessments']] : [])
  ]);
  if (state.organization.type === 'ACADEMY') {
    group('Academy', [
      ...(['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER'].includes(role)
        ? [['academy', role === 'LEARNER' ? 'My batches' : 'Courses & batches']] : []),
      ...(['ORG_ADMIN', 'INSTRUCTOR'].includes(role) ? [['academy-batch', 'Batch workspace']] : []),
      ...(['INSTRUCTOR', 'LEARNER'].includes(role) ? [['messages', 'Messages']] : []),
      ...(role === 'LEARNER' ? [['certificates', 'Certificates']] : [])
    ]);
  }
  if (state.organization.type === 'TALENT') {
    group('Talent', staff ? [['campaigns', 'Hiring campaigns'], ['talent-campaign', 'Campaign workspace']] : []);
  }
  group('My work', ['LEARNER', 'CANDIDATE'].includes(role)
    ? [['attempts', 'My assessments'], ['assessment-detail', 'Assessment detail']] : []);
  group('Administration', state.platformAdmin ? [['platform', 'Platform']] : []);
  for (const [heading, items] of groups) {
    if (heading) nav.append(node('p', heading, 'nav-heading'));
    for (const [key, title] of items) {
      const tab = node('button', title); tab.type = 'button'; tab.dataset.view = key;
      tab.addEventListener('click', () => run(() => showView(key)));
      nav.append(tab);
    }
  }
  $('#overview-hint').textContent = staff
    ? 'Start with a reviewed question, publish an assessment, then assign it to a verified member.'
    : 'Open My assessments to begin an assignment or revisit a result.';
  const quick = $('#overview-actions');
  const shortcut = (label, key) => {
    const control = node('button', label, 'button quiet small');
    control.type = 'button';
    control.addEventListener('click', () => run(() => showView(key)));
    return control;
  };
  const academyStaff = state.organization.type === 'ACADEMY' && ['ORG_ADMIN', 'INSTRUCTOR'].includes(role);
  const talentStaff = state.organization.type === 'TALENT' && staff;
  const actions = [];
  if (academyStaff) actions.push(shortcut('Question bank', 'questions'), shortcut('Assessments & review', 'assessments'),
    shortcut('Courses & batches', 'academy'), shortcut('Messages', 'messages'));
  if (talentStaff) actions.push(shortcut('Hiring campaigns', 'campaigns'), shortcut('Assessments & review', 'assessments'));
  if (role === 'ORG_ADMIN') actions.push(shortcut('Members', 'members'));
  if (['LEARNER', 'CANDIDATE'].includes(role)) actions.push(shortcut('My assessments', 'attempts'));
  if (role === 'LEARNER' && state.organization.type === 'ACADEMY') {
    actions.push(shortcut('My batches', 'academy'), shortcut('Certificates', 'certificates'));
  }
  if (state.platformAdmin) actions.push(shortcut('Platform', 'platform'));
  quick.replaceChildren(...actions);
  await showView('overview');
}
async function loadOverviewMetrics() {
  const container = $('#overview-metrics');
  const role = state.organization?.myRole;
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  if (!state.organization || !staff) {
    container.classList.add('hidden');
    container.replaceChildren();
    return;
  }
  container.classList.remove('hidden');
  beginList('#overview-metrics', 'Loading workspace counts…');
  const count = (path, label, view) => request(ownOrg(path))
    .then((page) => ({ label, value: page.totalElements ?? (page.content || []).length, view }))
    .catch(() => null);
  const jobs = [count('/attempts/review/pending?page=0', 'Waiting for release', 'assessments')];
  if (state.organization.type === 'ACADEMY') {
    jobs.push(count('/questions?status=DRAFT&page=0', 'Questions awaiting approval', 'questions'));
    jobs.push(count('/assessments?status=DRAFT&page=0', 'Draft assessments', 'assessments'));
    // The card opens the batch workspace, which picks its first course and batch.
    if (['ORG_ADMIN', 'INSTRUCTOR'].includes(role)) jobs.push(count('/academy/courses?page=0', 'Courses', 'academy-batch'));
  }
  if (state.organization.type === 'TALENT') {
    jobs.push(count('/talent/campaigns?page=0', 'Hiring campaigns', 'talent-campaign'));
    jobs.push(count('/assessments?status=DRAFT&page=0', 'Draft assessments', 'assessments'));
  }
  if (role === 'ORG_ADMIN') jobs.push(count('/members?page=0', 'Members', 'members'));
  const metrics = (await Promise.all(jobs)).filter(Boolean);
  if (!metrics.length) {
    container.replaceChildren(node('p', 'Workspace counts are unavailable right now.', 'empty'));
    return;
  }
  container.replaceChildren(...metrics.map((metric) => {
    const card = node('button', '', 'metric');
    card.type = 'button';
    card.append(node('span', String(metric.value), 'metric-value'), node('span', metric.label, 'metric-label'));
    card.addEventListener('click', () => run(() => showView(metric.view)));
    return card;
  }));
}
async function showView(key) {
  if (key !== 'messages') stopMessageRefresh();
  state.view = key;
  for (const view of views) $(`#${view}-view`).classList.toggle('hidden', key !== view);
  for (const tab of $('#workspace-nav').querySelectorAll('button')) {
    const active = tab.dataset.view === key;
    tab.classList.toggle('active', active);
    if (active) tab.setAttribute('aria-current', 'true'); else tab.removeAttribute('aria-current');
  }
  const names = { overview: 'Overview', members: 'Members', questions: 'Question bank',
    assessments: 'Assessments', academy: 'Courses & batches', 'academy-batch': 'Batch workspace',
    messages: 'Messages', campaigns: 'Hiring campaigns', 'talent-campaign': 'Campaign workspace',
    attempts: 'My assessments', 'assessment-detail': 'Assessment detail',
    certificates: 'Certificates', platform: 'Platform' };
  $('#page-title').textContent = names[key];
  document.title = `${names[key]} · JachAI`;
  const heading = $('#page-title');
  heading.setAttribute('tabindex', '-1');
  heading.focus({ preventScroll: true });
  $('#page-subtitle').textContent = key === 'platform' ? 'Administration and support'
    : state.organization?.name || '';
  if (key === 'platform') { await loadPlatformView(); return; }
  if (key === 'overview') await loadOverviewMetrics();
  if (key === 'overview' && state.organization.myRole === 'ORG_ADMIN') {
    await loadEntitlements(); await loadOrganizationSettings();
    await loadList('#usage-report', () => loadUsageReconciliation(),
      'The quota check could not be run.');
    ensureTenantSupportAuditPanel();
    await loadList('#tenant-support-audit', () => loadTenantSupportAudit(0),
      'Platform support history could not be loaded.');
  }
  if (key === 'members') {
    await loadList('#members-list', () => loadMembers(), 'Members could not be loaded.');
    attachFilter('#members-list', 'Filter members by name, email, role or ID');
  }
  if (key === 'questions') {
    await loadList('#questions-list', () => loadQuestions(), 'Questions could not be loaded.');
    attachFilter('#questions-list', 'Filter questions by topic, prompt or status');
  }
  if (key === 'assessments') {
    await loadList('#assessments-list', async () => {
      await loadApprovedQuestions(); await loadAssessments();
      await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
    }, 'Assessments could not be loaded.');
    attachFilter('#assessments-list', 'Filter assessments by title, status or release policy');
    await loadList('#review-queue', () => loadReviewQueue(0), 'The review queue could not be loaded.');
    await loadOrganizationSettings();
  }
  if (key === 'academy') {
    await loadList('#courses-list', () => loadAcademyCatalog(), 'Academy courses could not be loaded.');
    attachFilter('#courses-list', 'Filter courses by name or description');
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
}
async function loadEntitlements() {
  const plan = await request(ownOrg('/billing/entitlements'));
  setList('#plan-details', [
    item(`${plan.tier} plan`, `${state.organization.type} workspace · ${plan.retentionDays}-day message and telemetry retention`),
    item('Assessments & invitations', `${plan.maxPublishedAssessments} published assessments · ${plan.monthlyInvitations} invitations per month`),
    item('AI & certificates', `${plan.dailyUserAiRequests} AI requests per user/day · ${plan.monthlyCertificates} certificates per month`)
  ], 'Plan details are unavailable.');
  const options = await request(ownOrg('/billing/options'));
  const checkout = $('#start-ssl-demo');
  checkout.disabled = !options.sslCommerzAvailable;
  $('#start-bkash-demo').disabled = !options.directBkashAvailable;
  const sandboxNote = options.sandboxOnly === false
    ? ' This deployment does not report sandbox-only mode — do not enter real payment details.' : '';
  $('#billing-hint').textContent = (options.sslCommerzAvailable || options.directBkashAvailable
    ? `Sandbox only · TEAM demo price: ৳${options.demoTeamPriceBdt} for 30 days. No real money is charged.`
    : 'Sandbox merchant credentials are not configured yet, so both demo checkout buttons stay disabled. No real money is charged.')
    + (options.sslCommerzAvailable ? '' : ' SSLCOMMERZ sandbox credentials are not configured.')
    + (options.directBkashAvailable ? '' : ' Direct bKash sandbox credentials are not configured.')
    + sandboxNote;
  await loadPaymentHistory();
}
async function loadPaymentHistory(pageNumber = 0) {
  const payments = await request(ownOrg(`/billing/orders?page=${pageNumber}`));
  setList('#payment-history', (payments.content || []).map((order) => item(
    `${order.provider} · ৳${order.amountBdt} · ${order.status}`,
    `${order.id} · ${new Date(order.createdAt).toLocaleString()}`,
    order.status === 'CHECKOUT_READY' || order.status === 'VERIFIED'
      ? [button('Check payment', async () => {
          await request(ownOrg(`/billing/orders/${encodeURIComponent(order.id)}/reconcile`), { method: 'POST' });
          await loadEntitlements();
        })] : [])),
  'No demo transactions yet.');
  renderPageControls('#payment-history-pagination', payments, loadPaymentHistory);
}
async function loadPlatformView() {
  if (!state.platformAdmin) throw new Error('Platform administrator access required.');
  ensurePlatformSupportPanel();
  await Promise.all([
    loadList('#platform-operations', () => loadPlatformOperations(), 'Operational counts could not be loaded.'),
    loadList('#platform-storage', () => loadPlatformStorage(), 'Storage metrics could not be loaded.'),
    loadList('#platform-organizations', () => loadPlatformOrganizations(0), 'Organizations could not be loaded.')
  ]);
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
  if (state.platformTargetUserId) await inspectPlatformUser(state.platformTargetUserId);
}
function ensurePlatformSupportPanel() {
  if ($('#platform-support-panel')) return;
  const panel = node('div', '', 'card panel');
  panel.id = 'platform-support-panel';
  panel.innerHTML = `<h2>Ticketed assessment support</h2>
    <p class="muted">Exceptional access to one assessment's metadata only. Every preview is audited and visible to that organization's admins. No questions, answers, attempts or learner details are shown.</p>
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
      throw new Error('Inspect the selected organization before opening a support ticket.');
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
      notice('Metadata preview recorded in the tenant-visible support audit.');
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
  panel.innerHTML = `<div class="section-heading"><div><h2>Platform support access</h2>
    <p class="muted">Every platform-admin metadata preview of this workspace is recorded here. No staff member can hide a recorded access from this history.</p></div>
    <button id="tenant-support-audit-refresh" class="button quiet small" type="button">Reload</button></div>
    <div id="tenant-support-audit" class="item-list"></div><div id="tenant-support-audit-pagination"></div>`;
  $('#plan-panel').insertAdjacentElement('afterend', panel);
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
    item('Configured warning threshold', `${formatBytes(storage.warningThresholdBytes)} · set by ATLAS_STORAGE_WARNING_BYTES`)
  ];
  if (storage.warning) {
    rows.push(item('Above the configured threshold',
      'Treat this as a prompt to review retention, telemetry, audit and email growth — not as proof that a quota has been exceeded.'));
  }
  setList('#platform-storage', rows, 'Storage metrics are unavailable.');
}
async function loadPlatformOperations() {
  const summary = await request('/platform/operations/summary');
  setList('#platform-operations', [
    item('Failed email jobs', String(summary.failedEmailJobs)),
    item('Email jobs overdue over 15 minutes', String(summary.overdueEmailJobs)),
    item('Open sandbox checkouts older than 24 hours', String(summary.oldOpenSandboxOrders)),
    item('Verified orders not applied after 5 minutes', String(summary.verifiedButUnappliedOrders)),
    item('Quota repairs without completion', String(summary.usageRepairRunsWithoutCompletion)),
    item('Counts generated', summary.generatedAt ? new Date(summary.generatedAt).toLocaleString() : 'Not reported')
  ], 'Operational counts are unavailable.');
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
  setList('#platform-support-preview', [], 'A preview requires a new ticket and explicit acknowledgement.');
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
  ] : [item('No subscription record', 'Effective plan is FREE until a verified sandbox payment is applied.')],
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
function renderUsageReport(report) {
  const entries = report.entries || [];
  $('#usage-generated').textContent = `Checked ${when(report.generatedAt)}`
    + ` · ${report.tier || 'unknown'} plan`
    + ` · looking back ${report.lookbackMonths} month${report.lookbackMonths === 1 ? '' : 's'}`;
  setList('#usage-report', entries.map((entry) => {
    const row = item(
      `${entry.metric.replaceAll('_', ' ')} · ${entry.window}`,
      `Recorded ${entry.recordedUsage}; proven ${entry.provenMinimumUsage}-${entry.provenMaximumUsage};`
        + ` plan limit ${entry.planLimit}. ${entry.detail || ''}`.trim());
    // The status is spelled out, not only coloured, so it survives being read
    // aloud or printed.
    const status = (entry.status || '').replaceAll('_', ' ');
    const tone = entry.status === 'MATCHES' ? 'pill ok'
      : entry.status === 'OUTCOME_UNCONFIRMED' ? 'pill danger' : 'pill warn';
    row.querySelector('.item-actions').append(node('span', status, tone));
    return row;
  }), 'No quota discrepancies in the scanned windows.');

  const manual = $('#usage-manual');
  const steps = report.manualSteps || [];
  manual.textContent = steps.length
    ? ['This check could not settle everything by itself:', ...steps].join('\n')
    : '';
  manual.classList.toggle('hidden', !steps.length);

  // Repair is only offered when something is actually out of step. Offering it
  // against a clean report would invite a write that changes nothing.
  const repairable = entries.some((entry) => entry.status && entry.status !== 'MATCHES');
  const repair = $('#usage-repair');
  repair.disabled = !repairable;
  repair.title = repairable ? 'Rewrite the counters to match what the records prove'
    : 'Nothing to repair: every scanned window matches';
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
    throw new Error('Inspect the exact user ID before changing their role.');
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
  if (!await confirmDialog(`Grant a manual TEAM plan to ${manualPlanLabel(id)}?\n\nManual grant — not a payment. No money is charged, no order is marked paid, and an active paid subscription still takes precedence.\nExpires ${expiresAt.toLocaleString()}\nReason: ${reason}`)) return;
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
  'No ownership transfers yet.');
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
    return item(attempt.assessmentTitle,
      `Participant ID: ${attempt.participantUserId} · Attempt ${attempt.attemptNumber} · ${attempt.percentage == null ? 'Manual grading pending' : `${attempt.percentage}% · ${attempt.passed ? 'Passed' : 'Not passed'}`} · Submitted ${new Date(attempt.submittedAt).toLocaleString()}`,
      actions);
  });
  setList('#review-queue', rows, 'No results are waiting for release.');
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
  $('#attempt-review-summary').textContent = `${review.assessmentTitle} · Participant ${review.participantUserId} · ${review.percentage == null ? 'Score pending' : `${review.percentage}% · ${review.passed ? 'Passed' : 'Not passed'}`} · ${review.resultReleasedAt ? 'Released' : 'Not released'}`;
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
    `Graded by ${entry.actorUserId} · ${new Date(entry.gradedAt).toLocaleString()}`)),
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
async function compareCandidates(campaignId, pageNumber = 0) {
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates?page=${pageNumber}`));
  const rows = (page.content || []).map((candidate) => {
    const info = item(`Candidate ID: ${candidate.candidateUserId}`,
      candidate.attemptId
        ? `Latest attempt: ${candidate.attemptId} · ${candidate.percentage == null ? 'Awaiting manual grading · Review unavailable' : `${candidate.percentage}% · ${candidate.passed ? 'Passed' : 'Not passed'} · ${candidate.decision || 'Not reviewed'}`}`
        : 'No submitted attempt yet.');
    if (!candidate.attemptId) return info;
    let reviewForm = null;
    if (candidate.percentage != null) {
      reviewForm = node('form', '', 'form-row');
      const decisionLabel = node('label', 'Decision'); const select = node('select');
      for (const decision of ['HOLD', 'SHORTLISTED', 'REJECTED']) {
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
        node('p', `${report.totalEvents} recorded events · ${report.clientReportedRapidAnswerIntervals} rapid answer intervals`, 'item-meta'),
        node('p', counts || 'No signals recorded.'),
        node('p', report.interpretation, 'muted'));
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
  head.append(node('span', 'Candidate'), node('span', 'Latest attempt'), node('span', 'Decision'));
  summary.append(head);
  for (const candidate of page.content || []) {
    const row = node('div', '', 'compare-row');
    const attempt = candidate.attemptId
      ? (candidate.percentage == null ? 'Awaiting grading'
        : `${candidate.percentage}% · ${candidate.passed ? 'Passed' : 'Not passed'}`)
      : 'No submitted attempt';
    row.append(node('span', candidate.candidateUserId), node('span', attempt),
      node('span', candidate.decision ? roleLabel(candidate.decision) : 'Not reviewed'));
    summary.append(row);
  }
  rows.unshift(summary);
  setList('#campaign-candidates', rows, 'No candidates invited yet.');
  renderPageControls('#campaign-candidates-pagination', page,
    (nextPage) => compareCandidates(campaignId, nextPage));
  $('#campaign-candidates-panel').classList.remove('hidden');
  $('#campaign-candidates-panel').scrollIntoView({ behavior: 'smooth' });
}
async function loadReviewAudit(campaignId, candidateId, pageNumber, target) {
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates/${encodeURIComponent(candidateId)}/review/audit?page=${pageNumber}`));
  const rows = (page.content || []).map((entry) => item(
    `${entry.decision.replaceAll('_', ' ')} · ${new Date(entry.occurredAt).toLocaleString()}`,
    `Reviewer ID: ${entry.actorUserId} · Attempt ID: ${entry.attemptId} · ${entry.note || 'No note'}`));
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
    return item(assignment.assessmentTitle
      ? `${assignment.assessmentTitle}${resumable ? ' · attempt in progress' : ''}`
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
  state.activeAttempt = await request(ownOrg(`/assignments/${encodeURIComponent(assignmentId)}/attempts`), { method: 'POST' });
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
    await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/telemetry`),
      { method: 'POST', body: { type, ...(questionId ? { questionId } : {}) } });
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
      state.activeAttempt = null;
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
    panel.append(node('h3', `${result.percentage}% · ${result.passed ? 'Passed' : 'Not passed'}`),
      node('p', [result.submittedAt ? `Submitted ${new Date(result.submittedAt).toLocaleString()}` : null,
        result.releasedAt ? `Released ${new Date(result.releasedAt).toLocaleString()}` : null]
        .filter(Boolean).join(' · '), 'item-meta'));
    for (const topic of result.topics || []) {
      const share = topic.total ? Math.round((topic.correct / topic.total) * 100) : 0;
      const main = node('div', '', 'item-main');
      main.append(node('span', topic.topic, 'item-title'),
        node('p', `${topic.correct} of ${topic.total} correct (${share}%)`, 'item-meta'));
      const bar = node('div', '', 'bar');
      const fill = node('div', '', 'bar-fill');
      fill.style.width = `${share}%`;
      bar.append(fill);
      main.append(bar);
      const row = node('div', '', 'item');
      row.append(main);
      panel.append(row);
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
          box.append(node('p', feedback.summary), node('h4', 'Next steps'), node('p', feedback.nextSteps));
        } else {
          box.append(node('p', 'Your instructor has not published feedback for this attempt yet.'));
        }
        panel.append(box);
      }));
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
    [button('Verify publicly', () => window.open(`/certificates/${encodeURIComponent(certificate.verificationCode)}`, '_blank', 'noopener'))])),
  'No certificates yet.');
  renderPageControls('#certificates-pagination', page, loadCertificates);
}

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
  'You are not enrolled in a batch yet.');
  renderPageControls('#my-batches-pagination', page, loadMyBatches);
}

async function loadAcademyCourses(pageNumber = 0) {
  const page = await request(ownOrg(`/academy/courses?page=${pageNumber}`));
  state.academyCourses = page.content || [];
  setList('#courses-list', state.academyCourses.map((course) => item(course.name,
    course.description || `Course ID: ${course.id}`)), 'No courses yet.');
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
  'No batches for this course yet.');
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
    `Learner ${enrollment.learnerUserId}`,
    `Enrolled ${new Date(enrollment.enrolledAt).toLocaleDateString()}`)),
  'No learners enrolled yet.');
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

async function showBatchProgress(batchAssessmentId) {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  const route = `/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/assessments/${encodeURIComponent(batchAssessmentId)}`;
  const analytics = await request(ownOrg(`${route}/analytics`));
  const awaitingGrades = analytics.submittedLearners - analytics.gradedLearners;
  setList('#batch-analytics', [
    item('Cohort coverage', `${analytics.assignedLearners} assigned / ${analytics.activeLearners} active learners`),
    item('Completion', `${analytics.submittedLearners} submitted (${analytics.completionRatePercent}% of active learners)`),
    item('Grading & pass rate', `${analytics.gradedLearners} graded · ${awaitingGrades} awaiting grading · ${analytics.passedLearners} passed (${analytics.gradedLearners ? `${analytics.passRatePercent}% of graded` : 'no graded results yet'})`),
    item('Average latest score', analytics.averageLatestPercentage === null ? 'No graded results yet' : `${analytics.averageLatestPercentage.toFixed(1)}%`)
  ], 'Analytics are unavailable.');
  await loadBatchProgressPage(route, 0);
  $('#batch-progress-panel').classList.remove('hidden');
  $('#batch-progress-panel').scrollIntoView({ behavior: 'smooth' });
}
async function loadBatchProgressPage(route, pageNumber) {
  const page = await request(ownOrg(`${route}/progress?page=${pageNumber}`));
  setList('#batch-progress-list', (page.content || []).map((row) => item(
    `Learner ${row.learnerUserId}`,
    row.attemptId ? `Latest attempt: ${row.percentage == null ? 'Awaiting manual grading' : `${row.percentage}% · ${row.passed ? 'Passed' : 'Not passed'}`} · ${new Date(row.submittedAt).toLocaleString()}`
      : 'No submitted attempt yet.',
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
      'The batch roster could not be loaded.')
  ]);
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
  const awaitingGrades = analytics.submittedLearners - analytics.gradedLearners;
  setList('#academy-batch-analytics', [
    item('Cohort coverage', `${analytics.assignedLearners} assigned / ${analytics.activeLearners} active learners`),
    item('Completion', `${analytics.submittedLearners} submitted (${analytics.completionRatePercent}% of active learners)`),
    item('Grading & pass rate', `${analytics.gradedLearners} graded · ${awaitingGrades} awaiting grading · ${analytics.passedLearners} passed (${analytics.gradedLearners ? `${analytics.passRatePercent}% of graded` : 'no graded results yet'})`),
    item('Average latest score', analytics.averageLatestPercentage === null
      ? 'No graded results yet' : `${analytics.averageLatestPercentage.toFixed(1)}%`)
  ], 'Analytics are unavailable.');
}
async function loadAcademyWorkspaceProgress(assessment, token, pageNumber = 0) {
  const page = await request(ownOrg(`${batchWorkspaceRoute()}/assessments/${encodeURIComponent(assessment.id)}/progress?page=${pageNumber}`));
  if (batchWorkspaceStale(token)) return;
  setList('#academy-batch-progress', (page.content || []).map((row) => item(
    `Learner ${row.learnerUserId}`,
    row.attemptId
      ? `Latest attempt: ${row.percentage == null ? 'Awaiting manual grading' : `${row.percentage}% · ${row.passed ? 'Passed' : 'Not passed'}`} · ${new Date(row.submittedAt).toLocaleString()}`
      : 'No submitted attempt yet.')), 'No learner has been assigned this assessment yet.');
  renderPageControls('#academy-batch-progress-pagination', page,
    (nextPage) => run(() => loadAcademyWorkspaceProgress(assessment, token, nextPage)));
}
async function loadAcademyWorkspaceLearners(token, pageNumber = 0) {
  const page = await request(ownOrg(`${batchWorkspaceRoute()}/learners?page=${pageNumber}`));
  if (batchWorkspaceStale(token)) return;
  setList('#academy-batch-learners', (page.content || []).map((enrollment) => item(
    `Learner ${enrollment.learnerUserId}`,
    `Enrolled ${new Date(enrollment.enrolledAt).toLocaleDateString()}`)),
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
    row.append(node('span', candidate.candidateUserId), node('span', attempt),
      node('span', candidate.decision ? roleLabel(candidate.decision) : 'Not reviewed'));
    summary.append(row);
  }
  setList('#talent-campaign-comparison', rows.length ? [summary] : [], 'No candidate has been invited to this campaign yet.');
  const select = $('#talent-campaign-candidate');
  const previous = state.campaignWorkspaceCandidateId || select.value;
  select.replaceChildren(...rows.map((candidate) => {
    const option = node('option', candidate.candidateUserId); option.value = candidate.candidateUserId; return option;
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
    item(`Candidate ${candidate.candidateUserId}`,
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
    `Reviewer ${entry.actorUserId} · Attempt ${entry.attemptId || 'not recorded'} · ${entry.note || 'No note'}`)),
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
  for (const decision of ['HOLD', 'SHORTLISTED', 'REJECTED']) {
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
  setList('#conversations-list', (page.content || []).map((conversation) => item(
    conversation.counterpartDisplayName,
    `Started ${new Date(conversation.createdAt).toLocaleDateString()}`,
    [button('Open', () => openConversation(conversation))])), 'No conversations yet.');
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
  state.messagePage = 0;
  state.messageLoadToken++;
  $('#conversation-title').textContent = `Conversation with ${conversation.counterpartDisplayName}`;
  $('#conversation-panel').classList.remove('hidden');
  await loadConversationMessages();
  startMessageRefresh();
  $('#conversation-panel').scrollIntoView({ behavior: 'smooth' });
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
  const rows = [...(page.content || [])].reverse().map((message) => item(message.body,
    `${message.senderUserId} · ${new Date(message.sentAt).toLocaleString()}`));
  setList('#conversation-messages', rows, 'No messages yet. Say hello.');
  renderPageControls('#conversation-messages-pagination', page, loadConversationMessages);
}

$('#verify-code-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const code = $('#verify-code').value.trim();
  if (!code) throw new Error('Enter the verification code printed on the certificate.');
  window.location.assign(`/certificates/${encodeURIComponent(code)}`);
}); });
$('#login-tab').addEventListener('click', () => switchAuth('login'));
$('#start-ssl-demo').addEventListener('click', () => run(async () => {
  const control = $('#start-ssl-demo');
  control.disabled = true; control.textContent = 'Opening SSLCommerz sandbox…';
  try {
    const checkout = await request(ownOrg('/billing/orders'),
      { method: 'POST', body: { provider: 'SSLCOMMERZ' } });
    window.location.assign(checkout.checkoutUrl);
  } catch (error) {
    control.disabled = false; control.textContent = 'Pay with SSLCommerz sandbox';
    throw error;
  }
}));
$('#start-bkash-demo').addEventListener('click', () => run(async () => {
  const control = $('#start-bkash-demo');
  control.disabled = true; control.textContent = 'Opening bKash sandbox…';
  try {
    const checkout = await request(ownOrg('/billing/orders'),
      { method: 'POST', body: { provider: 'BKASH' } });
    window.location.assign(checkout.checkoutUrl);
  } catch (error) {
    control.disabled = false; control.textContent = 'Pay with direct bKash sandbox';
    throw error;
  }
}));
$('#register-tab').addEventListener('click', () => switchAuth('register'));
function switchAuth(which) {
  $('#login-form').classList.toggle('hidden', which !== 'login');
  $('#register-form').classList.toggle('hidden', which !== 'register');
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
  const config = await request('/auth/google/config', { auth: false });
  if (!config?.clientId) {
    // Honest unavailable state: never advertise a sign-in method this deployment cannot offer.
    const area = $('#google-login-area');
    area.replaceChildren(node('p', 'Google sign-in is not configured on this deployment. Use email and password instead.', 'muted'));
    area.classList.remove('hidden');
    return;
  }
  const script = document.createElement('script');
  script.src = 'https://accounts.google.com/gsi/client';
  script.async = true;
  await new Promise((resolve, reject) => {
    script.onload = resolve; script.onerror = reject; document.head.append(script);
  });
  window.google.accounts.id.initialize({ client_id: config.clientId, callback: ({ credential }) => run(async () => {
    if (state.accessToken) {
      await request('/auth/google/link', { method: 'POST', body: { credential } });
      notice('Google account linked. You can use it to sign in next time.');
    } else {
      const tokens = await request('/auth/google', { method: 'POST', auth: false, body: { credential } });
      await signedIn(tokens);
    }
  }) });
  for (const [selector, area] of [['#google-login-button', '#google-login-area'],
    ['#google-link-button', '#google-link-area']]) {
    window.google.accounts.id.renderButton($(selector), { theme: 'outline', size: 'large', text: 'continue_with' });
    $(area).classList.remove('hidden');
  }
}
setupGoogleSignIn().catch(() => { /* optional provider: an unreachable config must not block sign-in */ });
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
    throw error;
  } finally { if (control) control.disabled = false; }
}); });
$('#register-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const element = event.currentTarget;
  const form = new FormData(element);
  const control = element.querySelector('button[type="submit"]');
  if (control) control.disabled = true;
  try {
    const created = await request('/auth/register', { method: 'POST', auth: false,
      body: { displayName: form.get('displayName'), email: form.get('email'), password: form.get('password') } });
    element.reset(); switchAuth('login');
    notice(created?.message
      ? `${created.message}. Delivery is queued by the server and is not confirmed on this screen.`
      : 'Account created. The server queues a verification email; delivery is not confirmed on this screen.');
  } catch (error) {
    fieldErrors(element, error);
    throw error;
  } finally { if (control) control.disabled = false; }
}); });
$('#logout').addEventListener('click', () => run(async () => {
  try { await request('/auth/logout', { method: 'POST', body: { refreshToken: state.refreshToken } }); }
  finally { showSignedOut(); notice('Signed out.'); }
}));
$('#organization-select').addEventListener('change', (event) => run(async () => {
  state.organization = state.organizations.find((item) => item.id === event.target.value);
  await setOrganization();
}));
$('#create-org-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request('/organizations', { method: 'POST', body: { name: form.get('name'), type: form.get('type') } });
  event.currentTarget.reset(); await loadOrganizations(); notice('Workspace created.');
}); });
$('#organization-settings-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/settings'), { method: 'PUT', body: {
    defaultResultReleasePolicy: form.get('defaultResultReleasePolicy')
  } });
  await loadOrganizationSettings(); notice('Result release default saved.');
}); });
$('#add-member-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/members'), { method: 'POST', body: { email: form.get('email'), role: form.get('role') } });
  event.currentTarget.reset(); await loadMembers(); notice('Member added.');
}); });
$('#invite-member-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/invitations'), { method: 'POST',
    body: { email: form.get('email').trim(), role: form.get('role') } });
  event.currentTarget.reset();
  await loadMemberInvitations(0);
  notice('Invitation recorded. The server queues the email; this screen cannot confirm delivery.');
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
$('#assignment-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  if (!form.get('assessmentId')) throw new Error('Publish an assessment first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  const assignment = await request(ownOrg(`/assessments/${encodeURIComponent(form.get('assessmentId'))}/assignments`),
    { method: 'POST', body: { userId: form.get('userId').trim(), attemptsAllowed: Number(form.get('attemptsAllowed')),
      availableFrom, availableUntil } });
  event.currentTarget.reset();
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
    `By ${event.actorUserId} · ${when(event.occurredAt)}`)),
  'No published feedback event recorded for this attempt.');
  renderPageControls('#feedback-audit-pagination', page,
    (nextPage) => loadFeedbackAudit(attemptId, nextPage));
}
$('#academy-course-select').addEventListener('change', () => run(loadAcademyBatches));
$('#academy-batch-select').addEventListener('change', () => run(async () => {
  await loadAcademyBatchLearners(); await loadBatchAssessments();
}));
$('#course-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/academy/courses'), { method: 'POST',
    body: { name: form.get('name').trim(), description: form.get('description').trim() } });
  event.currentTarget.reset(); await loadAcademyCatalog(); notice('Course created.');
}); });
$('#batch-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const courseId = $('#academy-course-select').value;
  if (!courseId) throw new Error('Create a course first.');
  const form = new FormData(event.currentTarget);
  const startsAt = new Date(form.get('startsAt')).toISOString();
  const endsAt = new Date(form.get('endsAt')).toISOString();
  if (endsAt <= startsAt) throw new Error('Batch end must follow start.');
  await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches`), { method: 'POST',
    body: { name: form.get('name').trim(), startsAt, endsAt } });
  event.currentTarget.reset(); await loadAcademyBatches(); notice('Batch created.');
}); });
$('#enrollment-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  if (!courseId || !batchId) throw new Error('Create and select a batch first.');
  const form = new FormData(event.currentTarget);
  await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/learners`),
    { method: 'POST', body: { learnerUserId: form.get('learnerUserId').trim() } });
  event.currentTarget.reset(); await loadAcademyBatchLearners(); notice('Learner enrolled.');
}); });
$('#batch-assessment-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const courseId = $('#academy-course-select').value;
  const batchId = $('#academy-batch-select').value;
  const batch = state.academyBatches.find((item) => item.id === batchId);
  if (!courseId || !batch) throw new Error('Select a batch first.');
  const form = new FormData(event.currentTarget);
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom || availableFrom < batch.startsAt || availableUntil > batch.endsAt)
    throw new Error('Assessment availability must be within the batch dates.');
  await request(ownOrg(`/academy/courses/${encodeURIComponent(courseId)}/batches/${encodeURIComponent(batchId)}/assessments`),
    { method: 'POST', body: { assessmentId: form.get('assessmentId'),
      attemptsAllowed: Number(form.get('attemptsAllowed')), availableFrom, availableUntil } });
  event.currentTarget.reset(); await loadBatchAssessments(); notice('Assessment assigned to batch learners.');
}); });
$('#conversation-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const partnerUserId = new FormData(event.currentTarget).get('partnerUserId');
  if (!partnerUserId) throw new Error('Choose an instructor or learner first.');
  const conversation = await request(ownOrg('/messaging/conversations'),
    { method: 'POST', body: { partnerUserId } });
  await loadMessaging(); await openConversation(conversation);
}); });
$('#message-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const conversationId = state.activeConversationId;
  if (!conversationId) throw new Error('Open a conversation first.');
  const form = new FormData(event.currentTarget);
  await request(ownOrg(`/messaging/conversations/${encodeURIComponent(conversationId)}/messages`),
    { method: 'POST', body: { body: form.get('body').trim() } });
  event.currentTarget.reset(); await loadConversationMessages(0);
}); });
$('#campaign-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/talent/campaigns'), { method: 'POST',
    body: { title: form.get('title'), assessmentId: form.get('assessmentId') } });
  event.currentTarget.reset(); await loadCampaigns(); notice('Hiring campaign created.');
}); });
$('#campaign-invite-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  if (!form.get('campaignId')) throw new Error('Create an open campaign first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  await request(ownOrg(`/talent/campaigns/${encodeURIComponent(form.get('campaignId'))}/invitations`),
    { method: 'POST', body: { userId: form.get('userId').trim(),
      attemptsAllowed: Number(form.get('attemptsAllowed')), availableFrom, availableUntil } });
  event.currentTarget.reset(); notice('Candidate invited to campaign.');
}); });
$('#campaign-email-invite-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  if (!form.get('campaignId')) throw new Error('Create an open campaign first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  await request(ownOrg(`/talent/campaigns/${encodeURIComponent(form.get('campaignId'))}/email-invitations`),
    { method: 'POST', body: { email: form.get('email').trim(),
      attemptsAllowed: Number(form.get('attemptsAllowed')), availableFrom, availableUntil } });
  if (state.emailInvitationCampaignId === form.get('campaignId'))
    await loadEmailInvitations(state.emailInvitationCampaignId);
  event.currentTarget.reset(); notice('Invitation created. The server queues the email; this screen cannot confirm delivery.');
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
$('#assessment-versions-close').addEventListener('click', () => hideAssessmentVersions());
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

