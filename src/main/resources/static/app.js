const $ = (selector) => document.querySelector(selector);
const state = { accessToken: null, refreshToken: null, organizations: [], organization: null,
  questions: [], assessments: [], campaigns: [], activeAttempt: null, timer: null, view: 'overview' };
const views = ['overview', 'members', 'questions', 'assessments', 'campaigns', 'attempts', 'certificates'];

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
  state.accessToken = null; state.refreshToken = null; state.organization = null;
  state.activeAttempt = null;
  $('#auth').classList.remove('hidden'); $('#app').classList.add('hidden');
  $('#logout').classList.add('hidden');
}
async function signedIn(tokens) {
  state.accessToken = tokens.accessToken;
  state.refreshToken = tokens.refreshToken;
  $('#auth').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#logout').classList.remove('hidden');
  await loadOrganizations();
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
  state.activeAttempt = null;
  window.clearInterval(state.timer);
  for (const view of views) $(`#${view}-view`).classList.add('hidden');
  $('#attempt-panel').classList.add('hidden');
  $('#result-panel').classList.add('hidden');
  $('#campaign-candidates-panel').classList.add('hidden');
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
  $('#academy-feedback-panel').classList.toggle('hidden', state.organization.type !== 'ACADEMY');
  const staff = ['ORG_ADMIN', 'INSTRUCTOR', 'RECRUITER'].includes(role);
  const pages = [['overview', 'Overview']];
  if (role === 'ORG_ADMIN') pages.push(['members', 'Members']);
  if (staff) pages.push(['questions', 'Question bank'], ['assessments', 'Assessments']);
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
  state.view = key;
  for (const view of views) $(`#${view}-view`).classList.toggle('hidden', key !== view);
  for (const tab of $('#workspace-nav').children) tab.classList.toggle('active', tab.dataset.view === key);
  const names = { overview: 'Overview', members: 'Members', questions: 'Question bank',
    assessments: 'Assessments', campaigns: 'Hiring campaigns', attempts: 'My assessments',
    certificates: 'Certificates' };
  $('#page-title').textContent = names[key];
  $('#page-subtitle').textContent = state.organization.name;
  if (key === 'members') await loadMembers();
  if (key === 'questions') await loadQuestions();
  if (key === 'assessments') { await loadQuestions(); await loadAssessments(); }
  if (key === 'campaigns') { await loadAssessments(); await loadCampaigns(); await loadTalentCandidates(); }
  if (key === 'attempts') await loadAssignments();
  if (key === 'certificates') await loadCertificates();
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
async function loadMembers() {
  const members = await request(ownOrg('/members'));
  const roles = state.organization.type === 'ACADEMY' ? ['ORG_ADMIN', 'INSTRUCTOR', 'LEARNER']
    : ['ORG_ADMIN', 'RECRUITER', 'CANDIDATE'];
  $('#member-role').replaceChildren(...roles.map((role) => { const option = node('option', role.replaceAll('_', ' ')); option.value = role; return option; }));
  setList('#members-list', members.map((member) => item(member.displayName,
    `${member.email} · ${member.role} · User ID: ${member.userId}`)), 'No members yet.');
}
async function loadQuestions() {
  state.questions = await request(ownOrg('/questions'));
  setList('#questions-list', state.questions.map((question) => item(question.prompt,
    `${question.topic} · ${question.type.replaceAll('_', ' ')} · ${question.status}`,
    question.status === 'DRAFT' ? [button('Approve', async () => {
      await request(ownOrg(`/questions/${encodeURIComponent(question.id)}/approve`), { method: 'POST' });
      notice('Question approved.'); await loadQuestions();
    })] : [])), 'No questions yet.');
  const approved = state.questions.filter((question) => question.status === 'APPROVED');
  const choices = approved.map((question) => {
    const label = node('label', '', 'check');
    const input = node('input'); input.type = 'checkbox'; input.name = 'questionId'; input.value = question.id;
    label.append(input, node('span', `${question.topic} — ${question.prompt}`)); return label;
  });
  setList('#assessment-questions', choices, 'Approve a question before creating an assessment.');
}
async function loadAssessments() {
  state.assessments = await request(ownOrg('/assessments'));
  setList('#assessments-list', state.assessments.map((assessment) => item(assessment.title,
    `${assessment.status} · ${assessment.durationMinutes} min · ${assessment.questionIds.length} questions · Release: ${assessment.resultReleasePolicy || 'IMMEDIATE'}`,
    assessment.status === 'DRAFT' ? [button('Publish', async () => {
      await request(ownOrg(`/assessments/${encodeURIComponent(assessment.id)}/publish`), { method: 'POST' });
      notice('Assessment published.'); await loadAssessments();
    })] : [])), 'No assessments yet.');
  const select = $('#assign-assessment');
  select.replaceChildren(...state.assessments.filter((assessment) => assessment.status === 'PUBLISHED')
    .map((assessment) => { const option = node('option', assessment.title); option.value = assessment.id; return option; }));
  const campaignSelect = $('#campaign-assessment');
  campaignSelect.replaceChildren(...state.assessments.filter((assessment) => assessment.status === 'PUBLISHED')
    .map((assessment) => { const option = node('option', assessment.title); option.value = assessment.id; return option; }));
}
async function loadCampaigns() {
  const page = await request(ownOrg('/talent/campaigns?page=0'));
  state.campaigns = page.content || [];
  setList('#campaigns-list', state.campaigns.map((campaign) => item(campaign.title,
    `${campaign.open ? 'Open' : 'Closed'} · Assessment version ${campaign.assessmentVersionId}`,
    [button('Compare candidates', () => compareCandidates(campaign.id)),
      ...(campaign.open ? [button('Close campaign', async () => {
        if (!window.confirm('Close this campaign to new invitations?')) return;
        await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaign.id)}/close`), { method: 'POST' });
        notice('Campaign closed.'); await loadCampaigns();
      })] : [])])), 'No campaigns yet.');
  $('#invite-campaign').replaceChildren(...state.campaigns.filter((campaign) => campaign.open)
    .map((campaign) => { const option = node('option', campaign.title); option.value = campaign.id; return option; }));
}
async function loadTalentCandidates() {
  const page = await request(ownOrg('/members/candidates?page=0'));
  $('#invite-candidate').replaceChildren(...(page.content || []).map((candidate) => {
    const option = node('option', `${candidate.displayName} · ${candidate.email}`);
    option.value = candidate.userId; return option;
  }));
}
async function compareCandidates(campaignId) {
  const page = await request(ownOrg(`/talent/campaigns/${encodeURIComponent(campaignId)}/candidates?page=0`));
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
      notice('Review saved.'); await compareCandidates(campaignId);
    }); });
    const wrapper = node('div', '', 'card panel'); wrapper.append(info, form); return wrapper;
  });
  setList('#campaign-candidates', rows, 'No candidates invited yet.');
  $('#campaign-candidates-panel').classList.remove('hidden');
  $('#campaign-candidates-panel').scrollIntoView({ behavior: 'smooth' });
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
function renderAttempt() {
  const attempt = state.activeAttempt;
  const panel = $('#attempt-panel'); panel.replaceChildren(node('h2', 'Assessment in progress'));
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
    [button('Verify publicly', () => window.open(`/api/v1/public/certificates/${encodeURIComponent(certificate.verificationCode)}`, '_blank', 'noopener'))])),
  'No certificates yet.');
}

$('#login-tab').addEventListener('click', () => switchAuth('login'));
$('#register-tab').addEventListener('click', () => switchAuth('register'));
function switchAuth(which) {
  $('#login-form').classList.toggle('hidden', which !== 'login');
  $('#register-form').classList.toggle('hidden', which !== 'register');
  for (const name of ['login', 'register']) {
    const tab = $(`#${name}-tab`); tab.classList.toggle('active', name === which);
    tab.setAttribute('aria-selected', String(name === which));
  }
}
$('#login-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  const tokens = await request('/auth/login', { method: 'POST', auth: false,
    body: { email: form.get('email'), password: form.get('password') } });
  await signedIn(tokens); notice('Signed in.');
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
$('#add-member-form').addEventListener('submit', (event) => { event.preventDefault(); run(async () => {
  const form = new FormData(event.currentTarget);
  await request(ownOrg('/members'), { method: 'POST', body: { email: form.get('email'), role: form.get('role') } });
  event.currentTarget.reset(); await loadMembers(); notice('Member added.');
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
  const form = new FormData(event.currentTarget); const questionIds = form.getAll('questionId');
  if (!questionIds.length) throw new Error('Select at least one approved question.');
  await request(ownOrg('/assessments'), { method: 'POST', body: {
    title: form.get('title'), durationMinutes: Number(form.get('durationMinutes')),
    passingPercentage: Number(form.get('passingPercentage')), resultReleasePolicy: form.get('resultReleasePolicy'), questionIds
  } });
  event.currentTarget.reset(); await loadAssessments(); notice('Assessment draft created.');
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
$('#release-result').addEventListener('click', () => run(async () => {
  const id = $('#review-attempt-id').value.trim(); if (!id) throw new Error('Enter an attempt ID.');
  const result = await request(ownOrg(`/attempts/${encodeURIComponent(id)}/release`), { method: 'POST' });
  $('#review-output').textContent = `Released: ${result.percentage}% · ${result.passed ? 'Passed' : 'Not passed'}`;
  $('#review-output').classList.remove('hidden'); notice('Result released.');
}));
$('#issue-certificate').addEventListener('click', () => run(async () => {
  if (state.organization.type !== 'ACADEMY') throw new Error('Certificates are available in Academy workspaces.');
  const id = $('#review-attempt-id').value.trim(); if (!id) throw new Error('Enter an attempt ID.');
  const certificate = await request(ownOrg(`/attempts/${encodeURIComponent(id)}/certificate`), { method: 'POST' });
  $('#review-output').textContent = `Certificate issued. Verification code: ${certificate.verificationCode}`;
  $('#review-output').classList.remove('hidden'); notice('Certificate issued.');
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
