// Role-scoped /api/v1 fixtures injected into the page BEFORE app.js runs.
// Every payload is shaped from the real Java records; nothing here is a live backend.
// Usage: installScript({ role: 'learner' | 'academy-staff' | 'talent-recruiter' | 'platform-admin',
//                       storage: 'ok' | 'warn' | 'unavailable' | 'forbidden' })

const ROLES = {
  learner: { userId: 'u-learner', org: { id: 'org-a', name: 'Demo Academy', type: 'ACADEMY', myRole: 'LEARNER', owner: false }, platformAdmin: false },
  'academy-staff': { userId: 'u-admin', org: { id: 'org-a', name: 'Demo Academy', type: 'ACADEMY', myRole: 'ORG_ADMIN', owner: true }, platformAdmin: false },
  'talent-recruiter': { userId: 'u-rec', org: { id: 'org-t', name: 'Demo Talent', type: 'TALENT', myRole: 'RECRUITER', owner: false }, platformAdmin: false },
  'platform-admin': { userId: 'u-plat', org: { id: 'org-a', name: 'Demo Academy', type: 'ACADEMY', myRole: 'ORG_ADMIN', owner: true }, platformAdmin: true }
};

const SOURCE = String.raw`(() => {
  const ROLE = {
    userId: '__USER__',
    org: __ORG__,
    platformAdmin: __PLATFORM__,
    storage: '__STORAGE__'
  };
  const page = (content) => ({ content, number: 0, totalPages: 1, first: true, last: true,
    totalElements: content.length, numberOfElements: content.length });
  const json = (body, status = 200) => new Response(JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } });
  const error = (status, code, message) => json({ code, message, details: null }, status);

  const assignment = { id: 'asg-1',
    availableFrom: new Date(Date.now() - 3600000).toISOString(),
    availableUntil: new Date(Date.now() + 3600000).toISOString(), attemptsAllowed: 2 };
  const submittedAttempt = { id: 'att-9', attemptNumber: 1, status: 'SUBMITTED',
    deadline: new Date(Date.now() - 600000).toISOString(), submittedAt: new Date(Date.now() - 660000).toISOString() };
  const releasedResult = { attemptId: 'att-9', released: true, percentage: 80, passed: true,
    submittedAt: new Date(Date.now() - 660000).toISOString(), releasedAt: new Date(Date.now() - 600000).toISOString(),
    topics: [{ topic: 'Java', correct: 4, total: 5 }] };
  const learnerFeedback = { attemptId: 'att-9', learnerUserId: 'u-learner', authorUserId: 'u-admin',
    summary: 'Solid grasp of the core language.', nextSteps: 'Practise streams and collectors.',
    status: 'PUBLISHED', updatedAt: new Date().toISOString(), publishedAt: new Date().toISOString() };
  const attempt = { id: 'att-1', assignmentId: 'asg-1',
    deadline: new Date(Date.now() + 6000).toISOString(),
    questions: [
      { id: 'q1', topic: 'Java', prompt: 'Which of these runs on the JVM?', type: 'SINGLE_CHOICE',
        options: [{ id: 'A', text: 'Java' }, { id: 'B', text: 'COBOL' }] },
      { id: 'q2', topic: 'Java', prompt: 'Explain polymorphism briefly.', type: 'SHORT_ANSWER', options: [] } ],
    savedAnswers: {}, savedTextAnswers: {} };
  const review = { attemptId: 'att-9', participantUserId: 'u-learner', attemptNumber: 1, percentage: 80,
    passed: true, submittedAt: new Date().toISOString(), assessmentTitle: 'Java Basics' };
  const staffReview = { attemptId: 'att-9', assessmentTitle: 'Java Basics', participantUserId: 'u-learner',
    percentage: 80, passed: true, resultReleasedAt: null,
    questions: [
      { questionId: 'q1', type: 'SINGLE_CHOICE', topic: 'Java', prompt: 'Which runs on the JVM?',
        options: [{ id: 'A', text: 'Java' }, { id: 'B', text: 'COBOL' }], selectedOptionIds: ['A'], correctOptionIds: ['A'], correct: true },
      { questionId: 'q2', type: 'SHORT_ANSWER', topic: 'Java', prompt: 'Explain polymorphism.',
        options: [], textAnswer: 'Same interface, many forms.', correct: null } ] };
  const candidate = { candidateUserId: 'u-cand', assignmentId: 'asg-c1', attemptId: 'att-c1', percentage: 70,
    passed: true, submittedAt: new Date().toISOString(), decision: 'HOLD', reviewerNote: '' };
  const member = { userId: 'u-learner', role: 'LEARNER', displayName: 'Learner One', email: 'learner@example.com', active: true, owner: false };
  const caller = { userId: ROLE.userId, role: ROLE.org.myRole, displayName: 'Admin User', email: 'admin@example.com', active: true, owner: ROLE.org.owner };
  const course = { id: 'course-1', name: 'Java Fundamentals', description: 'Core Java for beginners', createdAt: new Date().toISOString() };
  const batch = { id: 'batch-1', courseId: 'course-1', name: 'Batch A',
    startsAt: new Date(Date.now() - 86400000).toISOString(), endsAt: new Date(Date.now() + 2592000000).toISOString(),
    createdAt: new Date().toISOString() };
  const enrollment = { courseId: 'course-1', courseName: 'Java Fundamentals', batchId: 'batch-1', batchName: 'Batch A',
    learnerUserId: 'u-learner', enrolledAt: new Date().toISOString() };
  const batchAssessment = { id: 'ba-1', courseId: 'course-1', batchId: 'batch-1', assessmentId: 'assess-1',
    assessmentVersionId: 'assess-1:1', assessmentTitle: 'Java Basics', attemptsAllowed: 2,
    availableFrom: new Date(Date.now() - 86400000).toISOString(), availableUntil: new Date(Date.now() + 604800000).toISOString() };
  const batchAnalytics = { batchAssessmentId: 'ba-1', activeLearners: 6, assignedLearners: 5, submittedLearners: 4,
    gradedLearners: 3, passedLearners: 2, averageLatestPercentage: 71.5, completionRatePercent: 66.7, passRatePercent: 66.7 };
  const batchProgress = { learnerUserId: 'u-learner', assignmentId: 'asg-1', attemptId: 'att-9', percentage: 80,
    passed: true, submittedAt: new Date().toISOString() };

  window.__calls = [];
  window.fetch = async (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url;
    const method = (init.method || 'GET').toUpperCase();
    const full = url.replace(location.origin, '');
    const path = full.split('?')[0];
    window.__calls.push(method + ' ' + full);
    const org = '/api/v1/organizations/' + ROLE.org.id;

    if (path === '/api/v1/auth/login') return json({ accessToken: 'fixture-access', refreshToken: 'fixture-refresh' });
    if (path === '/api/v1/auth/google/config') return json({ clientId: '' });
    if (path === '/api/v1/platform/me') return json({ userId: ROLE.userId, platformAdmin: ROLE.platformAdmin });
    if (path === '/api/v1/organizations') return json([ROLE.org]);

    // Platform administration
    if (path === '/api/v1/platform/operations/summary') return json({ generatedAt: new Date().toISOString(),
      failedEmailJobs: 2, overdueEmailJobs: 1, oldOpenSandboxOrders: 0, verifiedButUnappliedOrders: 1,
      usageRepairRunsWithoutCompletion: 0 });
    if (path === '/api/v1/platform/operations/storage') {
      if (ROLE.storage === 'unavailable') return error(503, 'STORAGE_METRICS_UNAVAILABLE', 'Database storage metrics are unavailable');
      if (ROLE.storage === 'forbidden') return error(403, 'PLATFORM_ADMIN_REQUIRED', 'Platform administrator access required');
      const dataBytes = 293601280; const indexBytes = 159383552;
      const estimated = dataBytes + indexBytes;
      const threshold = 400000000;
      return json({ generatedAt: new Date().toISOString(), dataBytes, indexBytes, estimatedLogicalBytes: estimated,
        warningThresholdBytes: threshold, warning: ROLE.storage === 'warn' && estimated >= threshold });
    }
    if (path === '/api/v1/platform/organizations') return json(page([{ id: 'org-a', name: 'Demo Academy',
      type: 'ACADEMY', ownerUserId: 'u-admin', createdAt: new Date().toISOString() }]));
    if (path.includes('/platform/organizations/org-a/billing/usage-reconciliation/audit')) return json(page([]));
    if (path.includes('/platform/organizations/org-a/billing/usage-reconciliation')) return json({ tier: 'FREE', lookbackMonths: 3, entries: [], manualSteps: [] });
    if (path.includes('/platform/organizations/org-a/billing/orders')) return json(page([]));
    if (path.includes('/platform/organizations/org-a/billing')) return json({ organizationId: 'org-a', subscriptionPresent: false,
      tier: 'FREE', status: 'NONE', currentPeriodEnd: null, lastPaidOrderId: null, updatedAt: null });
    if (path.includes('/platform/users/u-target/role/audit')) return json(page([]));
    if (path.includes('/platform/users/u-target/role')) return json({ userId: 'u-target', platformAdmin: false, bootstrapAdmin: false });
    if (path.includes('/platform/users/u-target')) return json({ id: 'u-target', email: 'target@example.com',
      displayName: 'Target Person', emailVerified: true, disabled: false });

    // Academy workspace
    if (path === org + '/billing/entitlements') return json({ tier: 'FREE', retentionDays: 90,
      maxPublishedAssessments: 3, monthlyInvitations: 30, dailyUserAiRequests: 5, monthlyCertificates: 30 });
    if (path === org + '/billing/options') return json({ sandboxOnly: true, sslCommerzAvailable: false,
      directBkashAvailable: false, demoTeamPriceBdt: 100 });
    if (path === org + '/billing/orders') return json(page([]));
    if (path === org + '/settings') return json({ defaultResultReleasePolicy: 'IMMEDIATE' });
    if (path === org + '/members') return json(page([caller, member]));
    if (path.includes('/members/u-learner/audit')) return json(page([]));
    if (path === org + '/invitations') return json(page([]));
    if (path === org + '/ownership/audit') return json(page([]));
    if (path === org + '/questions') return json(page([]));
    if (path === org + '/assessments') return json(page([]));
    if (path === org + '/attempts/review/pending') return json(page([review]));
    if (path.includes('/attempts/att-9/review/grades/audit')) return json(page([]));
    if (path.endsWith('/attempts/att-9/review')) return json(staffReview);
    if (path.endsWith('/attempts/att-9/release')) return json({ attemptId: 'att-9', released: true, percentage: 80, passed: true, topics: [] });
    if (path.includes('/review/questions/q2/grade')) return json(staffReview);
    if (path.endsWith('/attempts/att-9/certificate')) return json({ id: 'cert-1', verificationCode: 'VC-123', status: 'ISSUED' });
    if (path.includes('/certificates/cert-1/revoke')) return json({ id: 'cert-1', verificationCode: 'VC-123', status: 'REVOKED' });
    if (path.includes('/certificates/cert-1/audit')) return json(page([]));
    // Academy batch/cohort workspace
    if (path === org + '/academy/courses') return json(page([course]));
    if (path === org + '/academy/courses/course-1/batches') return json(page([batch]));
    if (path === org + '/academy/courses/course-1/batches/batch-1/learners') return json(page([enrollment]));
    if (path === org + '/academy/courses/course-1/batches/batch-1/assessments') return json(page([batchAssessment]));
    if (path.endsWith('/assessments/ba-1/reconcile')) return json({ batchAssessmentId: 'ba-1', createdAssignments: 2, alreadyAssigned: 3 });
    if (path.includes('/assessments/ba-1/analytics')) return json(batchAnalytics);
    if (path.includes('/assessments/ba-1/progress')) return json(page([batchProgress]));

    if (path === org + '/assignments/mine') return json(page(ROLE.org.myRole === 'LEARNER' ? [assignment] : []));
    if (path === org + '/assignments/asg-1/attempts/mine') return json(ROLE.org.myRole === 'LEARNER' ? [submittedAttempt] : []);
    if (path.endsWith('/attempts/att-9/result')) return json(releasedResult);
    if (path.includes('/academy/attempts/att-9/feedback/mine')) return json(learnerFeedback);
    if (path.endsWith('/attempts/att-9/tutor')) return json({ explanation: 'Records are a compact immutable carrier for data.' });
    if (path.endsWith('/assignments/asg-1/attempts') && method === 'POST') return json(attempt, 201);
    if (path.includes('/answers/')) return new Response(null, { status: 204 });
    if (path.endsWith('/submit')) return json({ attemptId: 'att-1', released: false, percentage: null, passed: null, topics: [] });
    if (path.includes('/integrity/events')) return json(page([
      { type: 'TAB_HIDDEN', questionId: null, receivedAt: new Date().toISOString() },
      { type: 'ANSWER_SAVED', questionId: 'q1', receivedAt: new Date().toISOString() } ]));
    if (path.includes('/integrity')) return json({ totalEvents: 2, eventCounts: { TAB_HIDDEN: 1, ANSWER_SAVED: 1 },
      clientReportedRapidAnswerIntervals: 0, interpretation: 'Signals are unverified and need human judgement.' });

    // Talent workspace
    if (path === org + '/members/candidates') return json(page([{ userId: 'u-cand', displayName: 'Cand One',
      email: 'cand@example.com', role: 'CANDIDATE' }]));
    if (path.includes('/talent/campaigns/camp-1/candidates/u-cand/review/audit')) return json(page([]));
    if (path.includes('/talent/campaigns/camp-1/candidates/u-cand/review')) return json(candidate);
    if (path.includes('/talent/campaigns/camp-1/candidates')) return json(page([candidate]));
    if (path.endsWith('/talent/campaigns/camp-1/close')) return json({ id: 'camp-1', title: 'Backend Engineer',
      assessmentId: 'assess-1', assessmentVersionId: 'ver-1', open: false, createdAt: new Date().toISOString() });
    if (path === org + '/talent/campaigns') return json(page([{ id: 'camp-1', title: 'Backend Engineer',
      assessmentId: 'assess-1', assessmentVersionId: 'ver-1', open: true, createdAt: new Date().toISOString() }]));

    return json(page([]));
  };
})();`;

function installScript({ role = 'learner', storage = 'ok' } = {}) {
  const chosen = ROLES[role];
  if (!chosen) throw new Error(`unknown role: ${role}`);
  return SOURCE
    .replace('__USER__', chosen.userId)
    .replace('__ORG__', JSON.stringify(chosen.org))
    .replace('__PLATFORM__', String(chosen.platformAdmin))
    .replace('__STORAGE__', storage);
}

module.exports = { installScript, ROLES };
