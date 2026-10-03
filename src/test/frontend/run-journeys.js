// Reproducible browser checks for the JachAI frontend.
//
// WHAT THIS IS: the real page, CSS and app.js served by a static server, driven in a real browser
// engine (headless Chrome/Edge over the DevTools Protocol), against MOCKED /api/v1 fixtures whose
// shapes come from the real Java records. It proves the frontend rendering, state handling and
// guards. It is NOT a live backend walkthrough: no Spring and no MongoDB are involved.
//
// Run:  node src/test/frontend/run-journeys.js
// Env:  CHROME_PATH=<browser executable>   (optional; common paths are auto-detected)
//       EVIDENCE_DIR=<dir>                 (optional; default target/frontend-evidence)
// Exit: 0 when every check passes, 1 on failure, 2 when no browser could be launched.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { startServer } = require('./static-server');
const { installScript } = require('./fixtures');

const RESOURCES = path.join(__dirname, '..', '..', 'main', 'resources');
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || path.join(__dirname, '..', '..', '..', 'target', 'frontend-evidence');
const CHROME_CANDIDATES = [process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome', '/usr/bin/chromium-browser', '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'];

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const all = [];
let failures = 0;

function check(journey, name, ok, detail = '') {
  all.push({ journey, name, ok: !!ok, detail: String(detail).slice(0, 160) });
  if (!ok) failures += 1;
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${journey}] ${name}${detail ? `  [${String(detail).slice(0, 96)}]` : ''}`);
}

function findBrowser() {
  return CHROME_CANDIDATES.find((candidate) => candidate && fs.existsSync(candidate));
}

async function launchBrowser() {
  const executable = findBrowser();
  if (!executable) throw new Error(`no browser found; set CHROME_PATH. Tried: ${CHROME_CANDIDATES.filter(Boolean).join(', ')}`);
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'jachai-frontend-'));
  const child = spawn(executable, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-allow-origins=*', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
    { stdio: 'ignore', windowsHide: true });
  const portFile = path.join(profile, 'DevToolsActivePort');
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (fs.existsSync(portFile)) {
      const [port] = fs.readFileSync(portFile, 'utf8').split('\n');
      const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const page = targets.find((target) => target.type === 'page');
      if (page) return { executable, child, profile, wsUrl: page.webSocketDebuggerUrl };
    }
    await wait(100);
  }
  child.kill();
  throw new Error('browser started but no DevTools page target appeared');
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    const pending = new Map();
    const errors = [];
    let nextId = 1;
    ws.addEventListener('open', () => resolve({
      errors,
      send(method, params = {}) {
        const id = nextId++;
        ws.send(JSON.stringify({ id, method, params }));
        return new Promise((res, rej) => pending.set(id, { res, rej }));
      },
      async evaluate(expression) {
        const result = await this.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
        if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
        return result.result.value;
      },
      close: () => ws.close()
    }));
    ws.addEventListener('error', reject);
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && pending.has(message.id)) {
        const entry = pending.get(message.id); pending.delete(message.id);
        message.error ? entry.rej(new Error(JSON.stringify(message.error))) : entry.res(message.result);
        return;
      }
      if (message.method === 'Runtime.exceptionThrown') {
        errors.push(message.params.exceptionDetails?.exception?.description || message.params.exceptionDetails?.text || 'exception');
      }
      if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') errors.push('console.error');
    });
  });
}

async function main() {
  const server = await startServer(RESOURCES);
  console.log(`static server: ${server.url} (serving ${RESOURCES}; no Spring, no MongoDB)`);
  const browser = await launchBrowser();
  console.log(`browser: ${browser.executable}`);
  const page = await connect(browser.wsUrl);
  await page.send('Runtime.enable');
  await page.send('Page.enable');

  const ev = (expression) => page.evaluate(expression);
  const WANT_SCREENS = process.argv.includes('--screens');
  const shot = async (name) => {
    if (!WANT_SCREENS) return;
    const { data } = await page.send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
    fs.writeFileSync(path.join(EVIDENCE_DIR, `${name}.png`), Buffer.from(data, 'base64'));
  };
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await page.send('Page.navigate', { url: `${server.url}/` });
  await wait(1500);
  await shot('landing-signed-out');
  const APP = `${server.url}/`;
  const view = (label) => ev(`(() => { const tab = [...document.querySelectorAll('#workspace-nav button')]
    .find((b) => b.textContent === ${JSON.stringify(label)}); if (!tab) return false; tab.click(); return true; })()`);
  const clickText = (scope, text) => ev(`(() => { const el = [...document.querySelectorAll('${scope} button')]
    .find((b) => b.textContent.includes(${JSON.stringify(text)})); if (!el) return false; el.click(); return true; })()`);
  const dialogText = () => ev(`document.querySelector('.modal') ? document.querySelector('.modal').textContent : ''`);
  const clickDialog = (kind) => ev(`(() => { const modal = document.querySelector('.modal'); if (!modal) return false;
    modal.querySelector('.button.${kind}').click(); return true; })()`);

  async function load(role, storage = 'ok') {
    await page.send('Page.addScriptToEvaluateOnNewDocument', { source: installScript({ role, storage }) });
    await page.send('Page.navigate', { url: APP });
    await wait(2200);
    await ev(`(() => {
      document.querySelector('#login-form input[name=email]').value = 'user@example.com';
      document.querySelector('#login-form input[name=password]').value = 'fixture-password-123';
      document.querySelector('#login-form').dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));
      return true; })()`);
    await wait(1500);
    await shot(`overview-${role}${storage === 'ok' ? '' : `-${storage}`}`);
  }

  // ---------------------------------------------------------------- learner journey
  await load('learner');
  const journey = 'learner';
  const nav = await ev(`[...document.querySelectorAll('#workspace-nav button')].map((b) => b.textContent)`);
  check(journey, 'sign-in reaches the workspace shell', await ev(`!document.querySelector('#app').classList.contains('hidden')`));
  check(journey, 'nav is learner-scoped', nav.includes('My assessments') && !nav.includes('Platform'), nav.join(', '));
  await view('My assessments'); await wait(1200);
  check(journey, 'assignment list renders', (await ev(`document.querySelector('#assignments-list').textContent`)).includes('Start / resume'));
  await clickText('#assignments-list', 'Start / resume'); await wait(600);
  const rules = await ev(`document.querySelector('#attempt-panel').textContent`);
  check(journey, 'instructions precede attempt creation', rules.includes('Before you begin')
    && (await ev(`window.__calls.filter((c) => c.startsWith('POST') && c.endsWith('/attempts')).length`)) === 0);
  check(journey, 'instructions explain the server timer', rules.includes('timer is controlled by the server'));
  await clickText('#attempt-panel', 'Begin attempt'); await wait(900);
  const started = await ev(`(() => { const p = document.querySelector('#attempt-panel');
    return { blocks: p.querySelectorAll('.question-block').length,
      timer: p.querySelector('.pill[role=timer]') ? p.querySelector('.pill[role=timer]').textContent : '',
      pills: [...p.querySelectorAll('.question-actions .pill')].map((x) => x.textContent) }; })()`);
  check(journey, 'attempt renders every question', started.blocks === 2, `blocks=${started.blocks}`);
  check(journey, 'server timer is displayed', /Time remaining: \d+:\d\d/.test(started.timer), started.timer);
  check(journey, 'answers start unsaved', started.pills.every((p) => p === 'Not saved'), started.pills.join('/'));
  await shot('learner-attempt');
  await ev(`(() => { const b = document.querySelectorAll('#attempt-panel .question-block')[0];
    b.querySelector('input[type=radio]').click();
    [...b.querySelectorAll('button')].find((x) => x.textContent === 'Save answer').click(); return true; })()`);
  await wait(900);
  check(journey, 'saved answer reports Saved', (await ev(`[...document.querySelectorAll('#attempt-panel .question-actions .pill')].map((p) => p.textContent)`))[0] === 'Saved');
  await wait(8000);
  const expired = await ev(`(() => { const p = document.querySelector('#attempt-panel');
    return { text: p.textContent, disabled: [...p.querySelectorAll('[data-attempt-save]')].every((b) => b.disabled),
      submit: [...p.querySelectorAll('button')].map((b) => b.textContent).join('|') }; })()`);
  check(journey, 'expiry announces time is up', expired.text.includes('Time is up'));
  check(journey, 'expiry disables answer saving', expired.disabled);
  check(journey, 'submit stays available after expiry', expired.submit.includes('time is up'));
  await clickText('#attempt-panel', 'Submit assessment'); await wait(500);
  check(journey, 'submit opens an accessible dialog', (await ev(`document.querySelector('.modal')?.getAttribute('role')`)) === 'dialog');
  check(journey, 'dialog counts unsaved answers', /1 answer is not saved/.test(await dialogText()));
  await clickDialog('primary'); await wait(1500);
  const result = await ev(`document.querySelector('#result-panel').textContent`);
  check(journey, 'pending-release result hides the score', result.includes('Your score stays hidden') && !/%/.test(result));
  check(journey, 'pending result offers Check again', await ev(`[...document.querySelectorAll('#result-panel button')].some((b) => b.textContent === 'Check again')`));

  // Learner assessment detail: window, attempts, result, feedback and tutor on one screen.
  await view('Assessment detail'); await wait(1600);
  check(journey, 'assessment detail opens from the nav for the learner assignment',
    /Assignment asg-1/.test(await ev(`document.querySelector('#assessment-detail-view').textContent`)));
  check(journey, 'attempt usage is counted from the server attempt list',
    /1 used of 2 allowed/.test(await ev(`document.querySelector('#assessment-detail-summary').textContent`)));
  check(journey, 'an assessment name the API does not return is reported, not invented',
    /does not return them to a learner/.test(await ev(`document.querySelector('#assessment-detail-summary').textContent`)));
  await clickText('#assessment-detail-attempts', 'View result, feedback and tutor'); await wait(1400);
  check(journey, 'released result and topic feedback render in the detail screen',
    /4 of 5 correct/.test(await ev(`document.querySelector('#assessment-detail-result').textContent`)));
  check(journey, 'the AI tutor entry point is offered with a released result',
    await ev(`!!document.querySelector('#assessment-detail-result form textarea')`));
  await clickText('#assessment-detail-result', 'View instructor feedback'); await wait(1000);
  check(journey, 'instructor feedback is reachable from the released result',
    /Practise streams/.test(await ev(`document.querySelector('#assessment-detail-result').textContent`)));
  await shot('learner-assessment-detail');
  await ev(`document.querySelector('#assessment-detail-back').click()`); await wait(1300);
  check(journey, 'assessment detail back navigation returns to My assessments',
    await ev(`!document.querySelector('#attempts-view').classList.contains('hidden')`));

  // ---------------------------------------------------------------- academy staff journey
  await load('academy-staff');
  const staff = 'academy-staff';
  await view('Members'); await wait(1200);
  check(staff, 'members render with a page filter', (await ev(`document.querySelector('#members-list').textContent`)).includes('Learner One')
    && await ev(`!!document.querySelector('input[data-filter-for="#members-list"]')`));
  await ev(`(() => { const select = [...document.querySelectorAll('#members-list select')].find((s) => s.value !== 'ORG_ADMIN');
    select.value = 'INSTRUCTOR'; const row = select.closest('.stack') || select.closest('.item');
    [...row.querySelectorAll('button')].find((b) => b.textContent === 'Save role').click(); return true; })()`);
  await wait(500);
  check(staff, 'role change names the transition in plain words', /from learner to instructor/.test(await dialogText()), await dialogText());
  await clickDialog('quiet'); await wait(300);
  await view('Assessments'); await wait(1600);
  check(staff, 'review queue lists a releasable result', (await ev(`document.querySelector('#review-queue').textContent`)).includes('Java Basics'));
  await clickText('#review-queue', 'Release result'); await wait(500);
  const releaseText = await dialogText();
  check(staff, 'release dialog names attempt and score', /att-9/.test(releaseText) && /80%/.test(releaseText));
  check(staff, 'release dialog states the learner is emailed', /result email/.test(releaseText));
  await clickDialog('primary'); await wait(1200);
  check(staff, 'release reports a queued email, not delivery', (await ev(`document.querySelector('#notice').textContent`)).includes('queued'));
  await ev(`(() => { document.querySelector('#review-attempt-id').value = 'att-9';
    document.querySelector('#inspect-attempt').click(); return true; })()`);
  await wait(1000);
  const reviewDetail = await ev(`document.querySelector('#attempt-review-detail').textContent`);
  check(staff, 'staff review exposes the answer key', /Answer key/.test(reviewDetail) && /A: Java/.test(reviewDetail));
  await shot('academy-staff-review');
  await clickText('#attempt-review-questions', 'Mark correct'); await wait(500);
  check(staff, 'grading confirms it is final and audited', /final and audited/.test(await dialogText()));
  await clickDialog('primary'); await wait(1000);
  await clickText('#assessments-view', 'Issue certificate'); await wait(900);
  const issued = await ev(`document.querySelector('#review-output').textContent`);
  check(staff, 'issue shows code and public path', /VC-123/.test(issued) && /\/certificates\/VC-123/.test(issued));
  check(staff, 'issue does not claim delivery', /cannot confirm delivery/.test(issued));
  await clickText('#assessments-view', 'Revoke certificate'); await wait(500);
  check(staff, 'revoke confirms it is terminal', /terminal/.test(await dialogText()));
  await clickDialog('primary'); await wait(900);
  check(staff, 'revoke reports the code is now invalid', /verifies as invalid/.test(await ev(`document.querySelector('#review-output').textContent`)));

  // Academy batch workspace: a distinct screen reached from the nav and from the batch list.
  await view('Batch workspace'); await wait(1500);
  const workspace = await ev(`document.querySelector('#academy-batch-view').textContent`);
  check(staff, 'batch workspace is reachable from the nav and selects a course and batch',
    /Java Fundamentals/.test(workspace) && /Batch A/.test(workspace));
  check(staff, 'batch summary counts the server roster',
    /Enrolled learners/.test(await ev(`document.querySelector('#academy-batch-summary').textContent`)));
  check(staff, 'batch assessments offer progress and reconciliation',
    await ev(`[...document.querySelectorAll('#academy-batch-assessments button')].some((b) => b.textContent === 'Reconcile assignments')`));
  await clickText('#academy-batch-assessments', 'Progress & analytics'); await wait(1400);
  const analyticsText = await ev(`document.querySelector('#academy-batch-analytics').textContent`);
  check(staff, 'cohort analytics render for the selected assessment',
    /Cohort coverage/.test(analyticsText) && /66.7%/.test(analyticsText));
  check(staff, 'per-learner progress rows render',
    /u-learner/.test(await ev(`document.querySelector('#academy-batch-progress').textContent`)));
  await clickText('#academy-batch-assessments', 'Reconcile assignments'); await wait(500);
  check(staff, 'reconcile confirms it only adds missing assignments', /never changed or removed/.test(await dialogText()));
  await clickDialog('primary'); await wait(1000);
  check(staff, 'reconcile reports created and already-present counts',
    /2 assignment\(s\) added; 3 already present/.test(await ev(`document.querySelector('#notice').textContent`)));
  await shot('academy-batch-workspace');
  await ev(`document.querySelector('#academy-batch-back').click()`); await wait(1200);
  check(staff, 'back navigation returns to Courses & batches',
    await ev(`!document.querySelector('#academy-view').classList.contains('hidden')`));
  check(staff, 'opening a view moves focus to the page heading',
    (await ev(`document.activeElement.id`)) === 'page-title', await ev(`document.activeElement.id`));

  // ---------------------------------------------------------------- talent journey
  await load('talent-recruiter');
  const talent = 'talent-recruiter';
  await view('Hiring campaigns'); await wait(1600);
  check(talent, 'campaign list renders', (await ev(`document.querySelector('#campaigns-list').textContent`)).includes('Backend Engineer'));
  await clickText('#campaigns-list', 'Compare candidates'); await wait(1300);
  await clickText('#campaign-candidates', 'Integrity signals'); await wait(900);
  check(talent, 'integrity summary carries its caveat', /unverified and need human judgement/.test(await ev(`document.querySelector('#campaign-candidates').textContent`)));
  await clickText('#campaign-candidates', 'Event log'); await wait(900);
  const events = await ev(`document.querySelector('#campaign-candidates').textContent`);
  check(talent, 'raw integrity event log is exposed', /recorded event/.test(events) && /tab hidden/.test(events));
  check(talent, 'event log warns the signals are not proof', /not proof of misconduct/.test(events));
  check(talent, 'review decision control is offered', await ev(`(() => { const f = document.querySelector('#campaign-candidates form');
    return !!f && !!f.querySelector('select'); })()`));
  await shot('talent-integrity');

  // Talent campaign workspace: a distinct screen reached from the nav and from the campaign row.
  await view('Campaign workspace'); await wait(1600);
  const campaignWorkspace = await ev(`document.querySelector('#talent-campaign-view').textContent`);
  check(talent, 'campaign workspace opens with the campaign and its frozen version',
    /Backend Engineer/.test(campaignWorkspace) && /Frozen version ver-1/.test(campaignWorkspace));
  check(talent, 'candidate comparison renders inside the workspace',
    /u-cand/.test(await ev(`document.querySelector('#talent-campaign-comparison').textContent`)));
  await ev(`(() => { const select = document.querySelector('#talent-campaign-candidate');
    select.value = 'u-cand'; select.dispatchEvent(new Event('change')); return true; })()`); await wait(1500);
  check(talent, 'workspace integrity summary carries the server caveat',
    /unverified and need human judgement/.test(await ev(`document.querySelector('#talent-campaign-integrity').textContent`)));
  const workspaceEvents = await ev(`document.querySelector('#talent-campaign-events').textContent`);
  check(talent, 'paged event log renders and warns the signals are not proof',
    /recorded event/.test(workspaceEvents) && /not proof of misconduct/.test(workspaceEvents));
  check(talent, 'review decision form is offered for a graded candidate',
    await ev(`!!document.querySelector('#talent-campaign-review form select')`));
  check(talent, 'review history area renders', await ev(`!!document.querySelector('#talent-campaign-history')`));
  await shot('talent-campaign-workspace');
  await ev(`document.querySelector('#talent-campaign-back').click()`); await wait(1300);
  check(talent, 'campaign workspace back navigation returns to the list',
    await ev(`!document.querySelector('#campaigns-view').classList.contains('hidden')`));

  // ---------------------------------------------------------------- platform journey + storage states
  await load('platform-admin', 'ok');
  const platform = 'platform-admin';
  await view('Platform'); await wait(1600);
  const platformText = await ev(`document.querySelector('#platform-view').textContent`);
  check(platform, 'operations render with a timestamp', /Counts generated/.test(platformText));
  check(platform, 'storage estimate is labelled as an estimate', /Storage estimate/.test(platformText));
  check(platform, 'storage shows the estimated size and breakdown', /Estimated logical size/.test(platformText) && /Data .* · Indexes/.test(platformText));
  check(platform, 'storage states the threshold source', /ATLAS_STORAGE_WARNING_BYTES/.test(platformText));
  check(platform, 'storage explains it is not the official quota', /not Atlas's official quota/.test(await ev(`document.querySelector('#platform-view').textContent`)));
  await shot('platform');
  check(platform, 'organizations carry owner and creation date', /Owner u-admin/.test(platformText));
  await ev(`(() => { document.querySelector('#platform-organization-id').value = 'org-a';
    document.querySelector('#platform-inspect').click(); return true; })()`);
  await wait(1200);
  const manualPlanText = await ev(`document.querySelector('#platform-manual-plan').textContent`);
  check(platform, 'manual TEAM plan panel states it is not a payment', /Manual grant — not a payment/.test(manualPlanText));
  check(platform, 'manual TEAM plan shows no active grant initially', /No active manual TEAM grant/.test(manualPlanText));
  check(platform, 'revoke button is disabled when no active grant', await ev(`document.querySelector('#platform-manual-revoke-submit').disabled`));
  await ev(`(() => { document.querySelector('#platform-user-id').value = 'u-target';
    document.querySelector('#platform-inspect-user').click(); return true; })()`);
  await wait(1200);
  await ev(`document.querySelector('#platform-grant-role').click()`); await wait(500);
  const grantText = await dialogText();
  check(platform, 'role dialog names the person, not just an id', /Target Person/.test(grantText) && /target@example.com/.test(grantText));
  check(platform, 'role dialog states the from-to transition', /no platform role to platform administrator/.test(grantText));
  await clickDialog('quiet'); await wait(300);

  await load('platform-admin', 'warn');
  await view('Platform'); await wait(1600);
  const warnText = await ev(`document.querySelector('#platform-storage').textContent`);
  check(platform, 'storage warning state is explicit, not colour-only', /Warning:/.test(warnText) && /Above the configured threshold/.test(warnText));
  check(platform, 'warning advises review, not a quota claim', /not as proof that a quota has been exceeded/.test(warnText));

  await load('platform-admin', 'unavailable');
  await view('Platform'); await wait(1600);
  const unavailable = await ev(`document.querySelector('#platform-storage').textContent`);
  check(platform, 'storage failure shows the server message', /Database storage metrics are unavailable/.test(unavailable), unavailable.slice(0, 90));
  check(platform, 'storage failure offers a retry', await ev(`[...document.querySelectorAll('#platform-storage button')].some((b) => b.textContent === 'Retry')`));

  // ---------------------------------------------------------------- mobile widths
  const mobile = 'mobile';
  await load('academy-staff');
  // Members rows carry the small action buttons, so the tap-target check measures a real control.
  await view('Members'); await wait(1200);
  check(mobile, 'page exposes a visible small action button to measure', await ev(`[...document.querySelectorAll('.button.small')]
    .some((b) => b.offsetParent !== null && b.getBoundingClientRect().height > 0)`));
  for (const width of [320, 375]) {
    await page.send('Emulation.setDeviceMetricsOverride', { width, height: 720, deviceScaleFactor: 1, mobile: false });
    await wait(600);
    const layout = await ev(`(() => ({ scroll: document.documentElement.scrollWidth, inner: window.innerWidth,
      nav: getComputedStyle(document.querySelector('.side-nav')).display,
      columns: getComputedStyle(document.querySelector('.side-nav')).gridTemplateColumns.split(' ').length,
      small: (() => { const b = [...document.querySelectorAll('.button.small')]
        .find((x) => x.offsetParent !== null && x.getBoundingClientRect().height > 0);
        return b ? Math.round(b.getBoundingClientRect().height) : null; })() }))()`);
    check(mobile, `${width}px viewport has no horizontal overflow`, layout.scroll <= layout.inner + 1, `scroll=${layout.scroll} inner=${layout.inner}`);
    check(mobile, `${width}px sidebar nav is a grid`, layout.nav === 'grid' && layout.columns >= 2, `${layout.nav}/${layout.columns}`);
    check(mobile, `${width}px small action buttons reach 44px`, layout.small !== null && layout.small >= 44, `height=${layout.small}`);
    await shot(`members-${width}px`);
  }
  await page.send('Emulation.clearDeviceMetricsOverride');

  check('all', 'no uncaught JavaScript exceptions in any journey', page.errors.length === 0, page.errors.slice(0, 2).join(' | '));

  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const evidenceFile = path.join(EVIDENCE_DIR, 'journeys.json');
  fs.writeFileSync(evidenceFile, JSON.stringify({
    note: 'MOCKED-FIXTURE browser checks: static server plus real app.js in headless Chrome. Not a live backend walkthrough.',
    ranAt: new Date().toISOString(), node: process.version, browser: browser.executable,
    servedFrom: RESOURCES, total: all.length, failed: failures, checks: all
  }, null, 2));

  console.log('');
  console.log(`${all.length - failures}/${all.length} checks passed across ${new Set(all.map((c) => c.journey)).size} journeys`);
  console.log(`evidence: ${evidenceFile}`);
  page.close();
  browser.child.kill();
  await server.close();
  process.exit(failures ? 1 : 0);
}

main().catch((error) => { console.error('harness error:', error.message); process.exit(2); });
