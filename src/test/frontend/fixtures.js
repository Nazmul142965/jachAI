// Role-scoped /api/v1 fixtures injected into the page BEFORE app.js runs.
// Every payload is shaped from the real Java records; nothing here is a live backend.
// Usage: installScript({ role: 'no-workspace' | 'learner' | 'academy-staff' | 'talent-recruiter' | 'platform-admin',
//                       storage: 'ok' | 'warn' | 'unavailable' | 'forbidden' })

const ROLES = {
  'no-workspace': { userId: 'u-new', org: null, platformAdmin: false },
  learner: { userId: 'u-learner', org: { id: 'org-a', name: 'Demo Academy', type: 'ACADEMY', myRole: 'LEARNER', owner: false }, platformAdmin: false },
  'academy-staff': { userId: 'u-admin', org: { id: 'org-a', name: 'Demo Academy', type: 'ACADEMY', myRole: 'ORG_ADMIN', owner: true }, platformAdmin: false },
  'talent-recruiter': { userId: 'u-rec', org: { id: 'org-t', name: 'Demo Talent', type: 'TALENT', myRole: 'RECRUITER', owner: false }, platformAdmin: false },
  'platform-admin': { userId: 'u-plat', org: { id: 'org-a', name: 'Demo Academy', type: 'ACADEMY', myRole: 'ORG_ADMIN', owner: true }, platformAdmin: true }
};

const SOURCE = String.raw`(() => {
  // The welcome card is checked on its own; every other journey starts with it already seen.
  try { localStorage.setItem('jachaiWelcomeSeen', '1'); } catch (e) { /* storage may be blocked on purpose */ }
  const ROLE = {
    userId: '__USER__',
    org: __ORG__,
    platformAdmin: __PLATFORM__,
    storage: '__STORAGE__'
  };
  const DEMO = __DEMO__;
  const page = (content) => ({ content, number: 0, totalPages: 1, first: true, last: true,
    totalElements: content.length, numberOfElements: content.length });
  const json = (body, status = 200) => new Response(JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } });
  const error = (status, code, message) => json({ code, message, details: null }, status);

  const assignment = { id: 'asg-1',
    availableFrom: new Date(Date.now() - 3600000).toISOString(),
    availableUntil: new Date(Date.now() + 3600000).toISOString(), attemptsAllowed: 2,
    assessmentTitle: 'Java Basics', durationMinutes: 20, questionCount: 5 };
  const submittedAttempt = { id: 'att-9', attemptNumber: 1, status: 'SUBMITTED',
    deadline: new Date(Date.now() - 600000).toISOString(), submittedAt: new Date(Date.now() - 660000).toISOString() };
  const releasedResult = { attemptId: 'att-9', released: true, percentage: 80, passed: true, state: 'RELEASED',
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
  const caller = { userId: ROLE.userId, role: ROLE.org?.myRole || 'LEARNER', displayName: 'Admin User', email: 'admin@example.com', active: true, owner: ROLE.org?.owner || false };
  const course = { id: 'course-1', name: 'Java Fundamentals', description: 'Core Java for beginners', createdAt: new Date().toISOString() };
  const batch = { id: 'batch-1', courseId: 'course-1', name: 'Batch A',
    startsAt: new Date(Date.now() - 86400000).toISOString(), endsAt: new Date(Date.now() + 2592000000).toISOString(),
    createdAt: new Date().toISOString() };
  const enrollment = { courseId: 'course-1', courseName: 'Java Fundamentals', batchId: 'batch-1', batchName: 'Batch A',
    learnerUserId: 'u-learner', learnerDisplayName: 'Learner One', enrolledAt: new Date().toISOString() };
  // A learner whose account no longer resolves: the server sends a null name
  // and the row must fall back to the id rather than print nothing.
  const enrollmentNoName = { courseId: 'course-1', courseName: 'Java Fundamentals', batchId: 'batch-1',
    batchName: 'Batch A', learnerUserId: 'u-gone', learnerDisplayName: null,
    enrolledAt: new Date().toISOString() };
  const batchAssessment = { id: 'ba-1', courseId: 'course-1', batchId: 'batch-1', assessmentId: 'assess-1',
    assessmentVersionId: 'assess-1:1', assessmentTitle: 'Java Basics', attemptsAllowed: 2,
    availableFrom: new Date(Date.now() - 86400000).toISOString(), availableUntil: new Date(Date.now() + 604800000).toISOString() };
  const batchAnalytics = { batchAssessmentId: 'ba-1', activeLearners: 6, assignedLearners: 5, submittedLearners: 4,
    gradedLearners: 3, passedLearners: 2, averageLatestPercentage: 71.5, completionRatePercent: 66.7, passRatePercent: 66.7 };
  const batchProgress = { learnerUserId: 'u-learner', learnerDisplayName: 'Learner One',
    assignmentId: 'asg-1', attemptId: 'att-9', percentage: 80,
    passed: true, submittedAt: new Date().toISOString() };

  // A published assessment, so the version-history entry point has something
  // to open, and the two frozen versions it would show.
  const publishedAssessment = { id: 'assess-1', organizationId: 'org-a', title: 'Java Basics',
    durationMinutes: 30, passingPercentage: 60, questionIds: ['q1', 'q2'],
    resultReleasePolicy: 'IMMEDIATE', status: 'PUBLISHED', latestVersion: 2,
    createdAt: new Date().toISOString(), revision: 3, instructions: '' };
  const assessmentVersions = [
    { organizationId: 'org-a', assessmentId: 'assess-1', version: 2, title: 'Java Basics',
      durationMinutes: 30, passingPercentage: 60, resultReleasePolicy: 'IMMEDIATE',
      questions: [{ questionId: 'q1', topic: 'Collections', prompt: 'A', type: 'SINGLE_CHOICE', difficulty: 'EASY' },
                  { questionId: 'q2', topic: 'Generics', prompt: 'B', type: 'SHORT_ANSWER', difficulty: 'MEDIUM' }],
      publishedAt: new Date().toISOString(), instructions: '' },
    { organizationId: 'org-a', assessmentId: 'assess-1', version: 1, title: 'Java Basics',
      durationMinutes: 25, passingPercentage: 50, resultReleasePolicy: 'IMMEDIATE',
      questions: [{ questionId: 'q1', topic: 'Collections', prompt: 'A', type: 'SINGLE_CHOICE', difficulty: 'EASY' }],
      publishedAt: new Date(Date.now() - 604800000).toISOString(), instructions: '' }
  ];

  const demoOrder = { id: 'order-1', provider: 'SSLCOMMERZ', status: 'VERIFIED', amountBdt: 100,
    createdAt: new Date(Date.now() - 7200000).toISOString(),
    verifiedAt: new Date(Date.now() - 3600000).toISOString(), appliedAt: null };
  const organizations = ROLE.org ? [ROLE.org] : [];

  window.__calls = [];
  let practiceUsed = 0;
  let guided = null;
  let guidedDone = false;
  let practiceGenerated = 0;
  const generatedQuestions = [];
  const practiceAnswers = [];
  window.fetch = async (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url;
    const method = (init.method || 'GET').toUpperCase();
    const full = url.replace(location.origin, '');
    const path = full.split('?')[0];
    window.__calls.push(method + ' ' + full);
    const org = '/api/v1/organizations/' + (ROLE.org?.id || 'none');

    if (path === '/api/v1/auth/login') return json({ accessToken: 'fixture-access', refreshToken: 'fixture-refresh' });
    if (path === '/api/v1/demo/accounts') return DEMO
      ? json([{ key: 'learner', label: 'Learner', role: 'LEARNER', workspace: 'Academy' },
          { key: 'academy-admin', label: 'Academy owner', role: 'ORG_ADMIN', workspace: 'Academy' }])
      : error(404, 'NOT_FOUND', 'Demo mode is not active');
    if (path === '/api/v1/demo/login/learner' && DEMO && method === 'POST')
      return json({ accessToken: 'fixture-access', refreshToken: 'fixture-refresh' });
    if (path === '/api/v1/auth/email-delivery-mode') return json({ mode: 'LOCAL_CONSOLE' });
    if (path === '/api/v1/auth/me') return json({ email: 'learner@example.com', displayName: 'Learner One',
      emailVerified: true, passwordSet: true, googleLinked: false, createdAt: new Date().toISOString() });
    if (path === '/api/v1/auth/request-password-reset' && method === 'POST')
      return json({ message: 'If the account is eligible, a reset email has been queued' });
    if (path === '/api/v1/auth/me/avatar' && method === 'POST') return json({ email: 'learner@example.com',
      displayName: 'Learner One', emailVerified: true, passwordSet: true, googleLinked: false,
      avatarDataUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', createdAt: new Date().toISOString() });
    if (path === '/api/v1/auth/me/avatar' && method === 'DELETE') return json({});
    if (path === '/api/v1/support/tickets' && method === 'GET') return json({ content: [{ referenceNumber: 'SUP-1001',
      status: 'OPEN', subject: 'Fixture ticket', category: 'TECHNICAL' }] });
    if (path === '/api/v1/auth/google/config') return json({ clientId: '' });
    if (path === '/api/v1/platform/me') return json({ userId: ROLE.userId, platformAdmin: ROLE.platformAdmin });
    if (path === '/api/v1/organizations' && method === 'POST') {
      const body = JSON.parse(init.body);
      const created = { id: 'org-new', name: body.name, type: body.type,
        myRole: 'ORG_ADMIN', owner: true };
      organizations.push(created);
      return json(created, 201);
    }
    if (path === '/api/v1/organizations') return json(organizations);
    if (path === '/api/v1/practice/questions') return json([...generatedQuestions, { id: 'java-record', topic: 'Java',
      prompt: 'Which statement about a Java record is correct?', options: [
        { id: 'a', text: 'It cannot implement interfaces' },
        { id: 'b', text: 'Its components generate accessors and a canonical constructor' },
        { id: 'c', text: 'Its components are mutable by default' }] }]);
    if (path === '/api/v1/practice/questions/generate' && method === 'POST') {
      if (practiceGenerated >= 20) return error(429, 'PRACTICE_AI_LIMIT_REACHED', 'Daily generation limit reached');
      const body = JSON.parse(init.body);
      const question = { id: 'pg-' + (++practiceGenerated), topic: body.topic,
        prompt: 'Which ' + body.difficulty.toLowerCase() + ' concept matters in ' + body.topic + '?', options: [
          { id: 'a', text: 'A relevant concept' }, { id: 'b', text: 'An unrelated concept' },
          { id: 'c', text: 'A third concept' }, { id: 'd', text: 'A fourth concept' }] };
      generatedQuestions.unshift(question);
      return json(question, 201);
    }
    if (path === '/api/v1/practice/paths') return json([{ id: 'baseline', title: 'Starting skill check', questionCount: 4 },
      { id: 'java', title: 'Java', questionCount: 4 }, { id: 'spring', title: 'Spring', questionCount: 3 }]);
    if (path === '/api/v1/practice/next') return json(guidedDone
      ? { pathId: 'spring', reason: 'Lowest accuracy in your recent completed sessions', answered: 4, correct: 1 }
      : { pathId: 'baseline', reason: 'Start with a short skill check', answered: 0, correct: 0 });
    if (path === '/api/v1/practice/sessions' && method === 'POST') {
      const body = JSON.parse(init.body);
      guided = { id: 'ps-1', pathId: body.pathId, results: [], complete: false, correctCount: 0, createdAt: new Date().toISOString(),
        questions: [1, 2, 3, 4].map((n) => ({ id: 'q' + n, topic: 'Java', prompt: 'Guided question ' + n,
          options: [{ id: 'a', text: 'Right ' + n }, { id: 'b', text: 'Wrong ' + n }] })) };
      return json(guided, 201);
    }
    if (path === '/api/v1/practice/sessions/ps-1/answers' && method === 'POST') {
      const body = JSON.parse(init.body);
      guided.results.push({ questionId: body.questionId, selectedOptionId: body.optionId, correct: body.optionId === 'a',
        answeredAt: new Date().toISOString(), correctOptionId: 'a', explanation: 'Fixture explanation.' });
      guided.correctCount = guided.results.filter((r) => r.correct).length;
      guided.complete = guided.results.length === guided.questions.length;
      if (guided.complete) guidedDone = true;
      return json(guided);
    }
    if (path === '/api/v1/practice/usage') return json({ utcDay: '2026-10-05', used: practiceUsed,
      limit: 20, remaining: 20 - practiceUsed, generated: practiceGenerated,
      generationLimit: 20, generationRemaining: 20 - practiceGenerated });
    if (path === '/api/v1/practice/answers/mine') return json(page(practiceAnswers));
    if (path === '/api/v1/practice/answers' && method === 'POST') {
      const body = JSON.parse(init.body);
      const existing = practiceAnswers.find((answer) => answer.id === ROLE.userId + ':' + body.requestId);
      if (existing) return json({ ...existing });
      if (practiceUsed >= 20) return error(429, 'PRACTICE_LIMIT_REACHED', 'Daily limit reached');
      practiceUsed += 1;
      const answer = { id: ROLE.userId + ':' + body.requestId, questionId: body.questionId,
        selectedOptionId: body.optionId, correctOptionId: 'b', correct: body.optionId === 'b',
        explanation: 'Records generate component accessors and a canonical constructor.',
        answeredAt: new Date().toISOString() };
      practiceAnswers.unshift(answer);
      return json(answer);
    }

    // Platform administration
    if (path === '/api/v1/platform/dashboard') return json({ generatedAt: new Date().toISOString(),
      sandboxAppliedOrders: 2, sandboxAppliedAmountBdt: 200, activeTeamWorkspaces: 1,
      openSupportTickets: 1, failedEmailJobs: 2, paymentsAwaitingVerification: 0,
      monthlySandboxOrders: [{ month: '2026-10', orders: 2 }] });
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
    if (path.includes('/platform/organizations/org-a/billing/manual-plan/audit')) return json(page([]));
    if (path.includes('/platform/organizations/org-a/billing/manual-plan/revoke')) return json({ organizationId: 'org-a', active: false, latestAction: 'REVOKED', expiresAt: null, revision: 2, changedAt: new Date().toISOString() });
    if (path.includes('/platform/organizations/org-a/billing/manual-plan')) return json({ organizationId: 'org-a', active: false, latestAction: null, expiresAt: null, revision: 0, changedAt: null });
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
    // Tenant-scoped quota reconciliation. One window is out of step so the
    // repair control has a reason to be enabled; POST reports it as repaired.
    if (path === org + '/billing/entitlements/usage-reconciliation') return json({
      organizationId: 'org-a', generatedAt: new Date().toISOString(), tier: 'FREE',
      downwardGraceSeconds: 60, lookbackMonths: 3, repaired: method === 'POST',
      entries: [
        { metric: 'INVITATION', window: new Date().toISOString().slice(0, 7), windowClosed: false,
          status: method === 'POST' ? 'IN_SYNC' : 'STRANDED',
          recordedUsage: 12, provenMinimumUsage: 9, provenMaximumUsage: 9, planLimit: 30,
          detail: 'Three invitations were counted twice.' },
        { metric: 'CERTIFICATE', window: new Date().toISOString().slice(0, 7), windowClosed: false, status: 'IN_SYNC',
          recordedUsage: 2, provenMinimumUsage: 2, provenMaximumUsage: 2, planLimit: 30, detail: '' }
      ],
      manualSteps: method === 'POST' ? [] : ['Confirm the duplicated invitations with the instructor.']
    });
    if (path === org + '/billing/entitlements') return json({ tier: 'FREE', retentionDays: 90,
      maxPublishedAssessments: 3, monthlyInvitations: 30, dailyUserAiRequests: 5, monthlyCertificates: 30 });
    if (path === org + '/billing/options') return json({ sandboxOnly: true, sslCommerzAvailable: false,
      directBkashAvailable: false, demoTeamPriceBdt: 100, demoCheckoutAvailable: true });
    if (path === org + '/billing/orders') return json(page([demoOrder]));
    // Verified with the provider but not yet applied to the plan: the gap the
    // order detail exists to show.
    if (path === org + '/billing/orders/order-1') return json(demoOrder);
    if (path === org + '/settings') return json({ defaultResultReleasePolicy: 'IMMEDIATE' });
    if (path === org + '/certificate-design') return json({ id: 'design-1', heading: 'Certificate of achievement',
      message: 'Has successfully completed the assessment', signatoryName: 'Dr Example',
      logoDataUrl: null, signatureDataUrl: null, createdAt: new Date().toISOString() });
    if (path === org + '/certificates/mine') return json(page([{ id: 'cert-1', verificationCode: 'VC-123',
      status: 'ISSUED', issuedAt: new Date().toISOString(), revokedAt: null }]));
    if (path === org + '/certificates/cert-1/display') return json({ verificationCode: 'VC-123',
      status: 'ISSUED', organizationName: 'Demo Academy', holderName: 'Learner One',
      assessmentTitle: 'Java Basics', issuedAt: new Date().toISOString(),
      design: { id: 'design-1', heading: 'Certificate of achievement',
        message: 'Has successfully completed the assessment', signatoryName: 'Dr Example',
        logoDataUrl: null, signatureDataUrl: null } });
    if (path === org + '/members') return json(page([caller, member]));
    if (path.includes('/members/u-learner/audit')) return json(page([]));
    if (path === org + '/invitations') return json(page([]));
    if (path === org + '/ownership/audit') return json(page([]));
    if (path === org + '/questions') return json(page([]));
    if (path === org + '/assessments') return json(page([publishedAssessment]));
    if (path.includes('/assessments/assess-1/versions')) return json(page(assessmentVersions));
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
    if (path === org + '/academy/courses/course-1/batches/batch-1/learners') return json(page([enrollment, enrollmentNoName]));
    if (path === org + '/academy/courses/course-1/batches/batch-1/assessments') return json(page([batchAssessment]));
    if (path.endsWith('/assessments/ba-1/reconcile')) return json({ batchAssessmentId: 'ba-1', createdAssignments: 2, alreadyAssigned: 3 });
    if (path.endsWith('/assessments/learning-progress')) return json(page([{ batchAssessmentId: 'ba-1',
      assessmentTitle: 'Java Basics', activeLearners: 4, assignedLearners: 4, submittedLearners: 3, gradedLearners: 2,
      passedLearners: 1, completionRatePercent: 75, passRatePercent: 50 }]));
    if (path.includes('/assessments/ba-1/analytics')) return json(batchAnalytics);
    if (path.includes('/assessments/ba-1/progress')) return json(page([batchProgress]));

    if (path === org + '/assignments/mine') return json(page(ROLE.org.myRole === 'LEARNER' ? [assignment] : []));
    if (path === org + '/assignments/asg-1') return json(assignment);
    if (path === org + '/assignments/asg-1/attempts/mine') return json(ROLE.org.myRole === 'LEARNER' ? [submittedAttempt] : []);
    if (path.endsWith('/attempts/att-9/result')) return json(releasedResult);
    if (path.includes('/academy/attempts/att-9/feedback/mine')) return json(learnerFeedback);
    if (path.endsWith('/attempts/att-9/tutor')) return json({ explanation: 'Records are a compact immutable carrier for data.' });
    if (path.endsWith('/assignments/asg-1/attempts') && method === 'POST') {
      return json({ ...attempt, deadline: new Date(Date.now() + 6000).toISOString() }, 201);
    }
    if (path.includes('/answers/')) return new Response(null, { status: 204 });
    if (path.endsWith('/submit')) return json({ attemptId: 'att-1', released: false,
      percentage: null, passed: null, state: 'GRADING_PENDING', topics: [] });
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
    if (path === org + '/talent/campaigns/camp-1/funnel') return json({ campaignId: 'camp-1', retainedEmailInvitations: 5,
      retainedAcceptedEmailInvitations: 3, assignedCandidates: 4, submittedCandidates: 3, gradedCandidates: 3,
      shortlistedCandidates: 2, hiredCandidates: 1, rejectedCandidates: 1 });
    if (path === org + '/talent/campaigns') return json(page([{ id: 'camp-1', title: 'Backend Engineer',
      assessmentId: 'assess-1', assessmentVersionId: 'ver-1', open: true, createdAt: new Date().toISOString() }]));

    return json(page([]));
  };
})();`;

function installScript({ role = 'learner', storage = 'ok', demo = false } = {}) {

  const chosen = ROLES[role];
  if (!chosen) throw new Error(`unknown role: ${role}`);
  return SOURCE
    .replace('__USER__', chosen.userId)
    .replace('__ORG__', JSON.stringify(chosen.org))
    .replace('__PLATFORM__', String(chosen.platformAdmin))
    .replace('__STORAGE__', storage)
    .replace('__DEMO__', String(demo));
}

module.exports = { installScript, ROLES };
