const $ = (selector) => document.querySelector(selector);
const state = { accessToken: null, refreshToken: null, organizations: [], organization: null,
  platformAdmin: false, platformUserId: null, platformOrganizationId: null,
  platformLoadToken: 0, platformTargetUserId: null, platformUserLoadToken: 0,
  questions: [], campaigns: [], academyCourses: [], academyBatches: [],
  emailInvitationCampaignId: null, emailInvitationLoadToken: 0,
  activeAttempt: null, activeReviewAttemptId: null, reviewLoadToken: 0,
  selectedQuestionIds: new Set(),
  timer: null, activeConversationId: null, messageRefresh: null,
  messageRefreshBusy: false, messagePage: 0, messageLoadToken: 0, view: 'overview' };
const views = ['overview', 'members', 'questions', 'assessments', 'academy', 'messages',
  'campaigns', 'attempts', 'certificates', 'platform'];
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
async function request(path, { method = 'GET', body, auth = true, retry = true, optional404 = false } = {}) {
  const headers = { Accept: 'application/json' };
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
      return request(path, { method, body, auth, retry: false, optional404 });
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
  state.activeReviewAttemptId = null;
  state.emailInvitationCampaignId = null;
  state.emailInvitationLoadToken++;
  state.selectedQuestionIds.clear();
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
  $('#review-certificate-id').value = '';
  $('#certificate-audit-pagination').replaceChildren();
  $('#campaign-candidates-panel').classList.add('hidden');
  $('#email-invitations-panel').classList.add('hidden');
  $('#conversation-panel').classList.add('hidden');
  $('#empty-org').classList.toggle('hidden', !!state.organization);
  $('#organization-select').classList.toggle('hidden', !state.organization);
  $('#workspace-role').textContent = state.organization?.myRole?.replaceAll('_', ' ') || '';
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
  $('#organization-settings-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  $('#billing-panel').classList.toggle('hidden', role !== 'ORG_ADMIN');
  $('#academy-feedback-panel').classList.toggle('hidden', state.organization.type !== 'ACADEMY');
  $('#academy-staff-panel').classList.toggle('hidden', !['ORG_ADMIN', 'INSTRUCTOR'].includes(role));
  $('#academy-learner-panel').classList.toggle('hidden', role !== 'LEARNER');
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  const pages = [['overview', 'Overview']];
  if (role === 'ORG_ADMIN') pages.push(['members', 'Members']);
  if (staff) pages.push(['questions', 'Question bank'], ['assessments', 'Assessments']);
  if (state.organization.type === 'ACADEMY' && ['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER'].includes(role))
    pages.push(['academy', role === 'LEARNER' ? 'My batches' : 'Courses & batches']);
  if (state.organization.type === 'ACADEMY' && ['INSTRUCTOR', 'LEARNER'].includes(role))
    pages.push(['messages', 'Messages']);
  if (staff && state.organization.type === 'TALENT') pages.push(['campaigns', 'Hiring campaigns']);
  if (['LEARNER', 'CANDIDATE'].includes(role)) pages.push(['attempts', 'My assessments']);
  if (role === 'LEARNER' && state.organization.type === 'ACADEMY') pages.push(['certificates', 'Certificates']);
  if (state.platformAdmin) pages.push(['platform', 'Platform']);
  for (const [key, title] of pages) {
    const tab = node('button', title); tab.type = 'button'; tab.dataset.view = key;
    tab.addEventListener('click', () => run(() => showView(key)));
    nav.append(tab);
  }
  $('#overview-hint').textContent = staff
    ? 'Start with a reviewed question, publish an assessment, then assign it to a verified member.'
    : 'Open My assessments to begin an assignment or revisit a result.';
  await showView('overview');
}
async function showView(key) {
  if (key !== 'messages') stopMessageRefresh();
  state.view = key;
  for (const view of views) $(`#${view}-view`).classList.toggle('hidden', key !== view);
  for (const tab of $('#workspace-nav').children) {
    const active = tab.dataset.view === key;
    tab.classList.toggle('active', active);
    if (active) tab.setAttribute('aria-current', 'true'); else tab.removeAttribute('aria-current');
  }
  const names = { overview: 'Overview', members: 'Members', questions: 'Question bank',
    assessments: 'Assessments', academy: 'Courses & batches', messages: 'Messages',
    campaigns: 'Hiring campaigns', attempts: 'My assessments',
    certificates: 'Certificates', platform: 'Platform' };
  $('#page-title').textContent = names[key];
  document.title = `${names[key]} · JachAI`;
  const heading = $('#page-title');
  heading.setAttribute('tabindex', '-1');
  heading.focus({ preventScroll: true });
  $('#page-subtitle').textContent = key === 'platform' ? 'Administration and support'
    : state.organization?.name || '';
  if (key === 'platform') { await loadPlatformView(); return; }
  if (key === 'overview' && state.organization.myRole === 'ORG_ADMIN') {
    await loadEntitlements(); await loadOrganizationSettings();
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
  if (key === 'messages') await loadMessaging();
  if (key === 'campaigns') {
    await loadList('#campaigns-list', async () => {
      await loadPublishedAssessments('#campaign-assessment', '#campaign-assessment-pagination');
      await loadCampaigns(); await loadTalentCandidates();
    }, 'Campaigns could not be loaded.');
    attachFilter('#campaigns-list', 'Filter campaigns by title or version');
  }
  if (key === 'attempts') {
    await loadList('#assignments-list', () => loadAssignments(), 'Assignments could not be loaded.');
    attachFilter('#assignments-list', 'Filter assignments by availability or attempt count');
  }
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
  await Promise.all([
    loadList('#platform-operations', () => loadPlatformOperations(), 'Operational counts could not be loaded.'),
    loadList('#platform-organizations', () => loadPlatformOrganizations(0), 'Organizations could not be loaded.')
  ]);
  if (state.platformOrganizationId) await inspectPlatformOrganization(state.platformOrganizationId);
  if (state.platformTargetUserId) await inspectPlatformUser(state.platformTargetUserId);
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
  for (const selector of ['#platform-billing', '#platform-orders', '#platform-usage-report', '#platform-usage-audit']) {
    beginList(selector);
  }
  try {
    await Promise.all([
      loadPlatformBilling(id, token), loadPlatformOrders(id, 0, token),
      loadPlatformUsageReport(id, token), loadPlatformUsageAudit(id, 0, token)
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
          if (!await confirmDialog(`Change ${member.displayName} from ${member.role.replaceAll('_', ' ')} to ${select.value.replaceAll('_', ' ')}?`)) return;
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
          if (!await confirmDialog(`Reactivate ${member.displayName} with the role ${select.value.replaceAll('_', ' ')}?`)) return;
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
    `${question.topic} · ${question.type.replaceAll('_', ' ')} · ${question.status}`,
    question.status === 'DRAFT' ? [button('Approve', async () => {
      await request(ownOrg(`/questions/${encodeURIComponent(question.id)}/approve`), { method: 'POST' });
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
    label.append(input, node('span', `${question.topic} — ${question.prompt}`)); return label;
  });
  setList('#assessment-questions', choices, 'Approve a question before creating an assessment.');
  renderPageControls('#assessment-questions-pagination', page, loadApprovedQuestions);
  updateSelectedQuestionCount();
}
function updateSelectedQuestionCount() {
  $('#selected-question-count').textContent = `${state.selectedQuestionIds.size} selected (maximum 100)`;
}
async function loadAssessments(pageNumber = 0) {
  const page = await request(ownOrg(`/assessments?page=${pageNumber}`));
  setList('#assessments-list', (page.content || []).map((assessment) => {
    const actions = [];
    if (assessment.status === 'DRAFT') {
      const publish = button('Publish', async () => {
        if (!await confirmDialog(`Publish "${assessment.title}"?\n\nPublishing freezes this version with ${assessment.questionIds.length} question(s) and a ${assessment.durationMinutes} minute limit, and adds it to the published list. A published version cannot be edited.`)) return;
        publish.disabled = true;
        try {
          await request(ownOrg(`/assessments/${encodeURIComponent(assessment.id)}/publish`), { method: 'POST' });
          notice('Assessment published. It can now be assigned.');
          await loadAssessments(page.number);
          await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
        } catch (error) { publish.disabled = false; throw error; }
      });
      actions.push(publish);
    }
    return item(assessment.title,
      `${assessment.status} · ${assessment.durationMinutes} min · ${assessment.questionIds.length} questions · Release: ${assessment.resultReleasePolicy || 'IMMEDIATE'}`,
      actions);
  }), 'No assessments yet.');
  renderPageControls('#assessments-pagination', page, loadAssessments);
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
    const actions = [button('Compare candidates', () => compareCandidates(campaign.id)),
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
      `${campaign.open ? 'Open' : 'Closed'} · Assessment version ${campaign.assessmentVersionId}`,
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
  setList('#assignments-list', (page.content || []).map((assignment) => {
    const resumable = inProgress?.assignmentId === assignment.id;
    return item(
      resumable ? 'Assessment assignment · attempt in progress' : 'Assessment assignment',
      `Available ${new Date(assignment.availableFrom).toLocaleString()} – ${new Date(assignment.availableUntil).toLocaleString()} · ${assignment.attemptsAllowed} attempt(s)`,
      [button(resumable ? 'Resume assessment' : 'Start / resume', () => startAttempt(assignment)),
        button('Attempt history', () => showHistory(assignment))]);
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
function renderAttemptInstructions(assignment) {
  const panel = $('#attempt-panel'); panel.replaceChildren(node('h2', 'Before you begin'));
  const rules = node('ul', '', 'attempt-rules');
  rules.append(
    node('li', `Available ${new Date(assignment.availableFrom).toLocaleString()} – ${new Date(assignment.availableUntil).toLocaleString()}.`),
    node('li', `${assignment.attemptsAllowed} attempt(s) allowed for this assignment.`),
    node('li', 'The timer is controlled by the server and starts when the attempt begins. It cannot be paused.'),
    node('li', 'If an attempt is already in progress, this continues that attempt instead of starting a new one.'),
    node('li', 'Save each answer before you submit. Only saved answers are counted.'),
    node('li', 'Answers cannot be changed after you submit.'));
  panel.append(node('p', 'Read these notes, then begin.', 'muted'), rules);
  panel.append(button('Begin attempt', () => beginAttempt(assignment.id), 'button primary'),
    button('Cancel', () => panel.classList.add('hidden'), 'button quiet'));
  panel.classList.remove('hidden');
  panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  focusPanel(panel);
}
async function startAttempt(assignment) {
  if (assignment?.availableFrom && assignment?.availableUntil) { renderAttemptInstructions(assignment); return; }
  await beginAttempt(assignment?.id || assignment);
}
async function beginAttempt(assignmentId) {
  state.activeAttempt = await request(ownOrg(`/assignments/${encodeURIComponent(assignmentId)}/attempts`), { method: 'POST' });
  rememberAttempt(assignmentId, state.activeAttempt);
  $('#result-panel').classList.add('hidden');
  renderAttempt();
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
function renderAttempt() {
  const attempt = state.activeAttempt;
  const panel = $('#attempt-panel');
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
  panel.append(node('p', 'Save each answer before submitting. The timer is set by the server.', 'muted'), timer, announce);
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
      renderResult(result);
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
async function showResult(attemptId) {
  renderResult(await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/result`)));
}
function renderResult(result) {
  const panel = $('#result-panel'); panel.replaceChildren(node('h2', 'Your result'));
  panel.append(node('p', `Attempt ID: ${result.attemptId}`, 'item-meta'));
  if (!result.released) {
    panel.append(node('p', 'Submitted. Your score stays hidden until staff release the result or finish manual grading.', 'muted'));
    panel.append(button('Check again', () => showResult(result.attemptId), 'button secondary'));
  } else {
    panel.append(node('h3', `${result.percentage}% · ${result.passed ? 'Passed' : 'Not passed'}`));
    for (const topic of result.topics || []) panel.append(item(topic.topic, `${topic.correct} of ${topic.total} correct`));
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
      const label = node('label', 'Your question'); const input = node('textarea');
      input.required = true; input.maxLength = 1200; input.rows = 2; label.append(input);
      const submit = node('button', 'Ask tutor', 'button secondary'); submit.type = 'submit';
      form.append(label, submit);
      form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
        const reply = await request(ownOrg(`/attempts/${encodeURIComponent(result.attemptId)}/tutor`),
          { method: 'POST', body: { question: input.value.trim() } });
        panel.append(node('p', reply.explanation, 'result-box'));
      }); });
      panel.append(form);
    }
  }
  panel.classList.remove('hidden'); panel.scrollIntoView({ behavior: 'smooth' });
  focusPanel(panel);
}
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
    `${new Date(batch.startsAt).toLocaleDateString()} – ${new Date(batch.endsAt).toLocaleDateString()}`)),
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
      : 'No submitted attempt yet.')), 'No learners assigned yet.');
  renderPageControls('#batch-progress-pagination', page,
    (nextPage) => loadBatchProgressPage(route, nextPage));
}

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
  const target = $(which === 'login' ? '#login-form' : '#register-form');
  const firstField = target?.querySelector('input');
  if (firstField && document.activeElement?.getAttribute('role') === 'tab') firstField.focus();
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
$('#question-form select[name="type"]').addEventListener('change', updateQuestionTypeFields);
updateQuestionTypeFields();
$('#question-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget); const correctOptionIds = form.getAll('correct');
  const shortAnswer = form.get('type') === 'SHORT_ANSWER';
  if (!shortAnswer && !correctOptionIds.length) throw new Error('Choose at least one correct option.');
  if (form.get('type') === 'SINGLE_CHOICE' && correctOptionIds.length !== 1) throw new Error('Single choice needs exactly one correct option.');
  await request(ownOrg('/questions'), { method: 'POST', body: {
    topic: form.get('topic'), prompt: form.get('prompt'), type: form.get('type'),
    options: shortAnswer ? [] : ['A', 'B', 'C', 'D'].map((id) => ({ id, text: form.get(id) })), correctOptionIds
  } });
  event.currentTarget.reset(); updateQuestionTypeFields();
  await loadQuestions(); notice('Question saved as a draft.');
}); });
$('#ai-draft-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/ai/question-drafts'), { method: 'POST',
    body: { topic: form.get('topic'), brief: form.get('brief'), type: form.get('type') } });
  event.currentTarget.reset(); await loadQuestions(); notice('AI draft saved. Review before approval.');
}); });
$('#assessment-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget); const questionIds = [...state.selectedQuestionIds];
  if (!questionIds.length) throw new Error('Select at least one approved question.');
  await request(ownOrg('/assessments'), { method: 'POST', body: {
    title: form.get('title'), durationMinutes: Number(form.get('durationMinutes')),
    passingPercentage: Number(form.get('passingPercentage')), resultReleasePolicy: form.get('resultReleasePolicy') || null, questionIds
  } });
  event.currentTarget.reset(); state.selectedQuestionIds.clear();
  await loadApprovedQuestions(); await loadAssessments(); notice('Assessment draft created.');
}); });
$('#assignment-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  if (!form.get('assessmentId')) throw new Error('Publish an assessment first.');
  const availableFrom = new Date(form.get('availableFrom')).toISOString();
  const availableUntil = new Date(form.get('availableUntil')).toISOString();
  if (availableUntil <= availableFrom) throw new Error('End time must follow start time.');
  await request(ownOrg(`/assessments/${encodeURIComponent(form.get('assessmentId'))}/assignments`),
    { method: 'POST', body: { userId: form.get('userId').trim(), attemptsAllowed: Number(form.get('attemptsAllowed')),
      availableFrom, availableUntil } });
  event.currentTarget.reset(); notice('Assessment assigned.');
}); });
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
    $('#feedback-output').textContent = `Feedback published for attempt ${feedback.attemptId}. The learner can now read it.`;
    $('#feedback-output').classList.remove('hidden'); notice('Learner can now view the feedback.');
  } finally { control.disabled = false; }
}));
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
