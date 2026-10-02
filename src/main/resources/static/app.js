const $ = (selector) => document.querySelector(selector);
const state = { accessToken: null, refreshToken: null, organizations: [], organization: null,
  questions: [], campaigns: [], academyCourses: [], academyBatches: [],
  activeAttempt: null, activeReviewAttemptId: null, reviewLoadToken: 0,
  selectedQuestionIds: new Set(),
  timer: null, activeConversationId: null, messageRefresh: null,
  messageRefreshBusy: false, messagePage: 0, messageLoadToken: 0, view: 'overview' };
const views = ['overview', 'members', 'questions', 'assessments', 'academy', 'messages',
  'campaigns', 'attempts', 'certificates'];
const invitationFromLink = new URLSearchParams(location.search).get('invitation');
const billingReturn = new URLSearchParams(location.search).get('billing');
if (invitationFromLink) {
  sessionStorage.setItem('jachaiPendingInvitation', invitationFromLink);
  history.replaceState(null, '', location.pathname);
}
if (billingReturn) {
  history.replaceState(null, '', location.pathname);
  if (billingReturn === 'verified') notice('Sandbox payment verified. Sign in to see your plan and transaction.');
  else notice(`Sandbox checkout ${billingReturn}. Sign in to review transaction history.`);
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
  area.textContent = message;
  area.classList.toggle('error', error);
  area.classList.remove('hidden');
  window.clearTimeout(notice.timeout);
  notice.timeout = window.setTimeout(() => area.classList.add('hidden'), 7000);
}
async function run(action) {
  try { await action(); } catch (error) { notice(error.message || 'Something went wrong.', true); }
}
function ownOrg(path) {
  if (!state.organization) throw new Error('Choose an organization first.');
  return `/organizations/${encodeURIComponent(state.organization.id)}${path}`;
}
async function request(path, { method = 'GET', body, auth = true, retry = true, optional404 = false } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && state.accessToken) headers.Authorization = `Bearer ${state.accessToken}`;
  const response = await fetch(`/api/v1${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store'
  });
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
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : null; } catch (_) { data = null; }
  if (response.status === 404 && optional404) return null;
  if (!response.ok) throw new Error(data?.message || `Request failed (${response.status}).`);
  return data;
}
function showSignedOut() {
  window.clearInterval(state.timer);
  stopMessageRefresh();
  state.accessToken = null; state.refreshToken = null; state.organization = null;
  state.activeAttempt = null; state.activeConversationId = null; state.messagePage = 0;
  state.activeReviewAttemptId = null;
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
  await loadOrganizations();
  notice(invitationMessage);
}
async function loadOrganizations() {
  const previous = state.organization?.id;
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
  window.clearInterval(state.timer);
  stopMessageRefresh();
  state.activeConversationId = null;
  state.messagePage = 0;
  state.messageLoadToken++;
  for (const view of views) $(`#${view}-view`).classList.add('hidden');
  $('#attempt-panel').classList.add('hidden');
  $('#result-panel').classList.add('hidden');
  $('#attempt-review-detail').classList.add('hidden');
  $('#campaign-candidates-panel').classList.add('hidden');
  $('#conversation-panel').classList.add('hidden');
  $('#empty-org').classList.toggle('hidden', !!state.organization);
  $('#organization-select').classList.toggle('hidden', !state.organization);
  $('#workspace-role').textContent = state.organization?.myRole?.replaceAll('_', ' ') || '';
  const nav = $('#workspace-nav'); nav.replaceChildren();
  if (!state.organization) {
    $('#page-title').textContent = 'Start here';
    $('#page-subtitle').textContent = 'Create an Academy or Talent workspace.';
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
  for (const tab of $('#workspace-nav').children) tab.classList.toggle('active', tab.dataset.view === key);
  const names = { overview: 'Overview', members: 'Members', questions: 'Question bank',
    assessments: 'Assessments', academy: 'Courses & batches', messages: 'Messages',
    campaigns: 'Hiring campaigns', attempts: 'My assessments',
    certificates: 'Certificates' };
  $('#page-title').textContent = names[key];
  $('#page-subtitle').textContent = state.organization.name;
  if (key === 'overview' && state.organization.myRole === 'ORG_ADMIN') {
    await loadEntitlements(); await loadOrganizationSettings();
  }
  if (key === 'members') await loadMembers();
  if (key === 'questions') await loadQuestions();
  if (key === 'assessments') {
    await loadApprovedQuestions(); await loadAssessments();
    await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
    await loadReviewQueue(0);
    await loadOrganizationSettings();
  }
  if (key === 'academy') await loadAcademyCatalog();
  if (key === 'messages') await loadMessaging();
  if (key === 'campaigns') {
    await loadPublishedAssessments('#campaign-assessment', '#campaign-assessment-pagination');
    await loadCampaigns(); await loadTalentCandidates();
  }
  if (key === 'attempts') await loadAssignments();
  if (key === 'certificates') await loadCertificates();
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
  $('#start-bkash-demo').disabled = !options.bkashAvailable;
  $('#billing-hint').textContent = options.sslCommerzAvailable || options.bkashAvailable
    ? `Sandbox only · TEAM demo price: ৳${options.demoTeamPriceBdt} for 30 days. No real money is charged.`
    : 'Sandbox merchant credentials are not configured yet. No real money is charged.';
  await loadPaymentHistory();
}
async function loadPaymentHistory(pageNumber = 0) {
  const payments = await request(ownOrg(`/billing/orders?page=${pageNumber}`));
  setList('#payment-history', (payments.content || []).map((order) => item(
    `${order.provider} · ৳${order.amountBdt} · ${order.status}`,
    `${order.id} · ${new Date(order.createdAt).toLocaleString()}`,
    (order.provider === 'BKASH' && order.status === 'CHECKOUT_READY') || order.status === 'VERIFIED'
      ? [button('Check payment', async () => {
          await request(ownOrg(`/billing/orders/${encodeURIComponent(order.id)}/reconcile`), { method: 'POST' });
          await loadEntitlements();
        })] : [])),
  'No demo transactions yet.');
  renderPageControls('#payment-history-pagination', payments, loadPaymentHistory);
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
  target.replaceChildren(...(rows.length ? rows : [node('p', emptyMessage, 'empty')]));
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
        actions.push(button('Save role', async () => {
          await request(ownOrg(`/members/${encodeURIComponent(member.userId)}/role`),
            { method: 'PUT', body: { role: select.value } });
          await loadMembers(page.number); notice('Member role updated.');
        }), button('Deactivate', async () => {
          if (!window.confirm(`Deactivate ${member.displayName} from this workspace?`)) return;
          await request(ownOrg(`/members/${encodeURIComponent(member.userId)}`), { method: 'DELETE' });
          await loadMembers(page.number); notice('Member deactivated.');
        }));
      } else {
        actions.push(button('Reactivate', async () => {
          await request(ownOrg('/members'), { method: 'POST',
            body: { email: member.email, role: select.value } });
          await loadMembers(page.number); notice('Member reactivated.');
        }));
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
  await loadOwnershipAudit(0);
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
  setList('#assessments-list', (page.content || []).map((assessment) => item(assessment.title,
    `${assessment.status} · ${assessment.durationMinutes} min · ${assessment.questionIds.length} questions · Release: ${assessment.resultReleasePolicy || 'IMMEDIATE'}`,
    assessment.status === 'DRAFT' ? [button('Publish', async () => {
      await request(ownOrg(`/assessments/${encodeURIComponent(assessment.id)}/publish`), { method: 'POST' });
      notice('Assessment published.'); await loadAssessments(page.number);
      await loadPublishedAssessments('#assign-assessment', '#assign-assessment-pagination');
    })] : [])), 'No assessments yet.');
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
  const rows = (page.content || []).map((attempt) => item(
    attempt.assessmentTitle,
    `Participant ID: ${attempt.participantUserId} · Attempt ${attempt.attemptNumber} · ${attempt.percentage}% · ${attempt.passed ? 'Passed' : 'Not passed'} · Submitted ${new Date(attempt.submittedAt).toLocaleString()}`,
    [button('Inspect answers', () => showStaffReview(attempt.attemptId)),
      button('Release result', async () => {
        await request(ownOrg(`/attempts/${encodeURIComponent(attempt.attemptId)}/release`), { method: 'POST' });
        if (state.activeReviewAttemptId === attempt.attemptId) await showStaffReview(attempt.attemptId);
        notice('Result released.'); await loadReviewQueue(page.number);
      })]));
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
  setList('#attempt-review-questions', review.questions.map((question) => item(question.prompt,
    `${question.topic} · ${question.correct ? 'Correct' : 'Incorrect'} · Chosen: ${answerText(question, question.selectedOptionIds)} · Answer key: ${answerText(question, question.correctOptionIds)}`)),
  'No questions found in this published version.');
  $('#attempt-review-detail').classList.remove('hidden');
  $('#attempt-review-detail').scrollIntoView({ behavior: 'smooth' });
}
async function loadCampaigns(pageNumber = 0) {
  const page = await request(ownOrg(`/talent/campaigns?page=${pageNumber}`));
  state.campaigns = page.content || [];
  setList('#campaigns-list', state.campaigns.map((campaign) => item(campaign.title,
    `${campaign.open ? 'Open' : 'Closed'} · Assessment version ${campaign.assessmentVersionId}`,
    [button('Compare candidates', () => compareCandidates(campaign.id)),
      ...(campaign.open ? [button('Close campaign', async () => {
        if (!window.confirm('Close this campaign to new invitations?')) return;
        await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaign.id)}/close`), { method: 'POST' });
        notice('Campaign closed.'); await loadCampaigns(page.number);
      })] : [])])), 'No campaigns yet.');
  renderPageControls('#campaigns-pagination', page, loadCampaigns);
  $('#invite-campaign').replaceChildren(...state.campaigns.filter((campaign) => campaign.open)
    .map((campaign) => { const option = node('option', campaign.title); option.value = campaign.id; return option; }));
  $('#email-invite-campaign').replaceChildren(...state.campaigns.filter((campaign) => campaign.open)
    .map((campaign) => { const option = node('option', campaign.title); option.value = campaign.id; return option; }));
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
async function compareCandidates(campaignId, pageNumber = 0) {
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates?page=${pageNumber}`));
  const rows = (page.content || []).map((candidate) => {
    const info = item(`Candidate ID: ${candidate.candidateUserId}`,
      candidate.attemptId
        ? `Latest attempt: ${candidate.attemptId} · ${candidate.percentage == null ? 'Score pending' : `${candidate.percentage}% · ${candidate.passed ? 'Passed' : 'Not passed'}`} · ${candidate.decision || 'Not reviewed'}`
        : 'No submitted attempt yet.');
    if (!candidate.attemptId) return info;
    const form = node('form', '', 'form-row');
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
    form.append(decisionLabel, noteLabel, save);
    form.addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
      await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates/${encodeURIComponent(candidate.candidateUserId)}/review`),
        { method: 'PUT', body: { attemptId: candidate.attemptId, decision: select.value, note: noteInput.value } });
      notice('Review saved.'); await compareCandidates(campaignId, page.number);
    }); });
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
    const auditPanel = node('div', '', 'item-list');
    const auditButton = button('Review history', () => run(() => loadReviewAudit(
      campaignId, candidate.candidateUserId, 0, auditPanel)));
    const wrapper = node('div', '', 'card panel');
    wrapper.append(info, form, integrityButton, integrityPanel, auditButton, auditPanel); return wrapper;
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
async function loadAssignments() {
  const assignments = await request(ownOrg('/assignments/mine'));
  setList('#assignments-list', assignments.map((assignment) => item(
    `Assessment assignment`, `Available ${new Date(assignment.availableFrom).toLocaleString()} – ${new Date(assignment.availableUntil).toLocaleString()} · ${assignment.attemptsAllowed} attempt(s)`,
    [button('Start / resume', () => startAttempt(assignment.id)),
      button('Attempt history', () => showHistory(assignment.id))])), 'No assignments yet.');
}
async function showHistory(assignmentId) {
  const history = await request(ownOrg(`/assignments/${encodeURIComponent(assignmentId)}/attempts/mine`));
  const panel = $('#result-panel'); panel.replaceChildren(node('h2', 'Attempt history'));
  if (!history.length) panel.append(node('p', 'No attempts yet.', 'muted'));
  for (const attempt of history) {
    const controls = attempt.status === 'SUBMITTED'
      ? [button('View result', () => showResult(attempt.id))]
      : [button('Resume', () => startAttempt(assignmentId))];
    panel.append(item(`Attempt ${attempt.attemptNumber}`, `${attempt.status} · ID: ${attempt.id}`, controls));
  }
  panel.classList.remove('hidden');
}
async function startAttempt(assignmentId) {
  state.activeAttempt = await request(ownOrg(`/assignments/${encodeURIComponent(assignmentId)}/attempts`), { method: 'POST' });
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
function renderAttempt() {
  const attempt = state.activeAttempt;
  const panel = $('#attempt-panel'); panel.replaceChildren(node('h2', 'Assessment in progress'));
  if (talentAttemptActive() && document.fullscreenEnabled) {
    panel.append(button('Use fullscreen (optional)', async () => {
      await panel.requestFullscreen(); state.attemptFullscreen = true;
    }));
  }
  const timer = node('p', '', 'pill warn'); panel.append(timer);
  const updateTimer = () => {
    const seconds = Math.max(0, Math.ceil((Date.parse(attempt.deadline) - Date.now()) / 1000));
    timer.textContent = `Time remaining: ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    if (seconds === 0) window.clearInterval(state.timer);
  };
  window.clearInterval(state.timer); updateTimer(); state.timer = window.setInterval(updateTimer, 1000);
  for (const [index, question] of attempt.questions.entries()) {
    const block = node('div', '', 'question-block');
    block.append(node('p', `${question.topic} · Question ${index + 1}`, 'eyebrow'), node('h3', question.prompt));
    const choices = node('div', '', 'question-options');
    const saved = new Set(attempt.savedAnswers?.[question.id] || []);
    for (const option of question.options) {
      const label = node('label'); const input = node('input');
      input.type = question.type === 'SINGLE_CHOICE' ? 'radio' : 'checkbox';
      input.name = `answer-${question.id}`; input.value = option.id; input.checked = saved.has(option.id);
      label.append(input, node('span', option.text)); choices.append(label);
    }
    block.append(choices, button('Save answer', async () => {
      const selected = [...choices.querySelectorAll('input:checked')].map((input) => input.value);
      await request(ownOrg(`/attempts/${encodeURIComponent(attempt.id)}/answers/${encodeURIComponent(question.id)}`),
        { method: 'PUT', body: { selectedOptionIds: selected } });
      attempt.savedAnswers = { ...(attempt.savedAnswers || {}), [question.id]: selected };
      void sendTelemetry('ANSWER_SAVED', question.id);
      notice('Answer saved.');
    }));
    panel.append(block);
  }
  panel.append(button('Submit assessment', async () => {
    if (!window.confirm('Submit now? Answers cannot be changed afterward.')) return;
    const result = await request(ownOrg(`/attempts/${encodeURIComponent(attempt.id)}/submit`), { method: 'POST' });
    window.clearInterval(state.timer); state.activeAttempt = null; panel.classList.add('hidden');
    renderResult(result); notice('Assessment submitted.');
  }, 'button primary'));
  panel.classList.remove('hidden'); panel.scrollIntoView({ behavior: 'smooth' });
}
async function showResult(attemptId) {
  renderResult(await request(ownOrg(`/attempts/${encodeURIComponent(attemptId)}/result`)));
}
function renderResult(result) {
  const panel = $('#result-panel'); panel.replaceChildren(node('h2', 'Your result'));
  panel.append(node('p', `Attempt ID: ${result.attemptId}`, 'item-meta'));
  if (!result.released) {
    panel.append(node('p', 'Submitted. Your organization has not released this result yet.', 'muted'));
  } else {
    panel.append(node('h3', `${result.percentage}% · ${result.passed ? 'Passed' : 'Not passed'}`));
    for (const topic of result.topics || []) panel.append(item(topic.topic, `${topic.correct} of ${topic.total} correct`));
    if (state.organization.type === 'ACADEMY' && state.organization.myRole === 'LEARNER') {
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
}
async function loadCertificates() {
  const certificates = await request(ownOrg('/certificates/mine'));
  setList('#certificates-list', certificates.map((certificate) => item(
    certificate.status === 'ISSUED' ? 'Verified achievement' : 'Revoked certificate',
    `Code: ${certificate.verificationCode} · Issued ${new Date(certificate.issuedAt).toLocaleDateString()}`,
    [button('Verify publicly', () => window.open(`/certificates/${encodeURIComponent(certificate.verificationCode)}`, '_blank', 'noopener'))])),
  'No certificates yet.');
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
  setList('#batch-analytics', [
    item('Cohort coverage', `${analytics.assignedLearners} assigned / ${analytics.activeLearners} active learners`),
    item('Completion & pass rate', `${analytics.submittedLearners} submitted (${analytics.completionRatePercent}% of active learners) · ${analytics.passedLearners} passed (${analytics.passRatePercent}% of submitted)`),
    item('Average latest score', analytics.averageLatestPercentage === null ? 'No submitted attempts yet' : `${analytics.averageLatestPercentage.toFixed(1)}%`)
  ], 'Analytics are unavailable.');
  await loadBatchProgressPage(route, 0);
  $('#batch-progress-panel').classList.remove('hidden');
  $('#batch-progress-panel').scrollIntoView({ behavior: 'smooth' });
}
async function loadBatchProgressPage(route, pageNumber) {
  const page = await request(ownOrg(`${route}/progress?page=${pageNumber}`));
  setList('#batch-progress-list', (page.content || []).map((row) => item(
    `Learner ${row.learnerUserId}`,
    row.attemptId ? `Latest attempt: ${row.percentage}% · ${row.passed ? 'Passed' : 'Not passed'} · ${new Date(row.submittedAt).toLocaleString()}`
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
  const checkout = await request(ownOrg('/billing/orders'),
    { method: 'POST', body: { provider: 'SSLCOMMERZ' } });
  window.location.assign(checkout.checkoutUrl);
}));
$('#start-bkash-demo').addEventListener('click', () => run(async () => {
  const checkout = await request(ownOrg('/billing/orders'),
    { method: 'POST', body: { provider: 'BKASH' } });
  window.location.assign(checkout.checkoutUrl);
}));
$('#register-tab').addEventListener('click', () => switchAuth('register'));
function switchAuth(which) {
  $('#login-form').classList.toggle('hidden', which !== 'login');
  $('#register-form').classList.toggle('hidden', which !== 'register');
  for (const name of ['login', 'register']) {
    const tab = $(`#${name}-tab`); tab.classList.toggle('active', name === which);
    tab.setAttribute('aria-selected', String(name === which));
  }
}
async function setupGoogleSignIn() {
  const config = await request('/auth/google/config', { auth: false });
  if (!config?.clientId) return;
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
run(setupGoogleSignIn);
$('#login-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  const tokens = await request('/auth/login', { method: 'POST', auth: false,
    body: { email: form.get('email'), password: form.get('password') } });
  await signedIn(tokens);
}); });
$('#register-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request('/auth/register', { method: 'POST', auth: false,
    body: { displayName: form.get('displayName'), email: form.get('email'), password: form.get('password') } });
  event.currentTarget.reset(); switchAuth('login'); notice('Account created. Check your email to verify it.');
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
$('#transfer-owner-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const email = new FormData(event.currentTarget).get('email').trim();
  const target = await request(ownOrg(`/members/admins/lookup?email=${encodeURIComponent(email)}`));
  if (!window.confirm(`Transfer workspace ownership to ${target.displayName} (${target.email})? You will no longer be the owner.`)) return;
  await request(ownOrg('/owner'), { method: 'PUT', body: { targetUserId: target.userId } });
  await loadOrganizations();
  notice('Workspace ownership transferred.');
}); });
$('#question-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget); const correctOptionIds = form.getAll('correct');
  if (!correctOptionIds.length) throw new Error('Choose at least one correct option.');
  if (form.get('type') === 'SINGLE_CHOICE' && correctOptionIds.length !== 1) throw new Error('Single choice needs exactly one correct option.');
  await request(ownOrg('/questions'), { method: 'POST', body: {
    topic: form.get('topic'), prompt: form.get('prompt'), type: form.get('type'),
    options: ['A', 'B', 'C', 'D'].map((id) => ({ id, text: form.get(id) })), correctOptionIds
  } });
  event.currentTarget.reset(); await loadQuestions(); notice('Question saved as a draft.');
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
  const result = await request(ownOrg(`/attempts/${encodeURIComponent(id)}/release`), { method: 'POST' });
  $('#review-output').textContent = `Released: ${result.percentage}% · ${result.passed ? 'Passed' : 'Not passed'}`;
  $('#review-output').classList.remove('hidden'); notice('Result released.');
  if (state.activeReviewAttemptId === id) await showStaffReview(id);
  await loadReviewQueue(0);
}));
$('#issue-certificate').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-attempt-id').value.trim(); if (!id) throw new Error('Enter an attempt ID.');
  const certificate = await request(ownOrg(`/attempts/${encodeURIComponent(id)}/certificate`), { method: 'POST' });
  $('#review-certificate-id').value = certificate.id;
  $('#review-output').textContent = `Certificate ID: ${certificate.id} · Verification code: ${certificate.verificationCode}`;
  $('#review-output').classList.remove('hidden'); notice('Certificate issued.');
}));
$('#revoke-certificate').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-certificate-id').value.trim(); if (!id) throw new Error('Enter a certificate ID.');
  const certificate = await request(ownOrg(`/certificates/${encodeURIComponent(id)}/revoke`), { method: 'POST' });
  $('#review-output').textContent = `Certificate ${certificate.id} revoked. Its public code now verifies as invalid.`;
  $('#review-output').classList.remove('hidden'); notice('Certificate revoked.');
}));
$('#certificate-audit').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-certificate-id').value.trim(); if (!id) throw new Error('Enter a certificate ID.');
  const events = await request(ownOrg(`/certificates/${encodeURIComponent(id)}/audit?page=0`));
  $('#review-output').textContent = (events.content || []).length
    ? events.content.map((entry) => `${entry.action.replaceAll('_', ' ')} · ${entry.actorUserId} · ${new Date(entry.occurredAt).toLocaleString()}`).join('\n')
    : 'No audit events recorded for this certificate.';
  $('#review-output').classList.remove('hidden');
}));
$('#feedback-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  const attemptId = form.get('attemptId').trim();
  const feedback = await request(ownOrg(`/academy/attempts/${encodeURIComponent(attemptId)}/feedback`),
    { method: 'PUT', body: { summary: form.get('summary').trim(), nextSteps: form.get('nextSteps').trim() } });
  $('#feedback-output').textContent = `Draft saved for attempt ${feedback.attemptId}.`;
  $('#feedback-output').classList.remove('hidden'); notice('Feedback draft saved.');
}); });
$('#publish-feedback').addEventListener('click', () => run(async () => {
  const attemptId = new FormData($('#feedback-form')).get('attemptId').trim();
  if (!attemptId) throw new Error('Enter an attempt ID first.');
  const feedback = await request(ownOrg(`/academy/attempts/${encodeURIComponent(attemptId)}/feedback/publish`),
    { method: 'POST' });
  $('#feedback-output').textContent = `Feedback published for attempt ${feedback.attemptId}.`;
  $('#feedback-output').classList.remove('hidden'); notice('Learner can now view the feedback.');
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
  event.currentTarget.reset(); notice('Invitation created; email delivery requested.');
}); });
