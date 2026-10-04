// Opt-in, read-only browser check against the real local Spring/Mongo demo.
// Unlike run-journeys.js, this never installs API fixtures. It signs in through
// the page and saves screenshots only under ignored target/frontend-live-evidence.
// Required: DEMO_EMAIL, DEMO_PASSWORD, DEMO_OWNER_EMAIL, DEMO_BASE_URL (loopback).
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const base = new URL(process.env.DEMO_BASE_URL || 'http://127.0.0.1:8095/');
if (!['127.0.0.1', 'localhost'].includes(base.hostname) || base.protocol !== 'http:') {
  throw new Error('The live browser check is restricted to a loopback HTTP demo.');
}
for (const key of ['DEMO_EMAIL', 'DEMO_PASSWORD', 'DEMO_OWNER_EMAIL']) {
  if (!process.env[key]) throw new Error(`${key} is required in the private environment.`);
}
const evidence = path.join(__dirname, '..', '..', '..', 'target', 'frontend-live-evidence');
const candidates = [process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe'];
const chrome = candidates.find((candidate) => candidate && fs.existsSync(candidate));
if (!chrome) throw new Error('Chrome or Edge was not found; set CHROME_PATH.');
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'jachai-live-'));
  const child = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run',
    '--no-default-browser-check', '--remote-allow-origins=*', '--remote-debugging-port=0',
    `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore', windowsHide: true });
  let socket;
  try {
    let target;
    for (let i = 0; i < 100; i += 1) {
      const portFile = path.join(profile, 'DevToolsActivePort');
      if (fs.existsSync(portFile)) {
        const [port] = fs.readFileSync(portFile, 'utf8').split('\n');
        const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        target = targets.find((entry) => entry.type === 'page');
        if (target) break;
      }
      await wait(100);
    }
    if (!target) throw new Error('Browser DevTools page did not start.');
    socket = new WebSocket(target.webSocketDebuggerUrl);
    const pending = new Map();
    const errors = [];
    let nextId = 1;
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', reject, { once: true });
    });
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && pending.has(message.id)) {
        const entry = pending.get(message.id);
        pending.delete(message.id);
        message.error ? entry.reject(new Error(JSON.stringify(message.error))) : entry.resolve(message.result);
      }
      if (message.method === 'Runtime.exceptionThrown') {
        errors.push(message.params.exceptionDetails?.text || 'JavaScript exception');
      }
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      socket.send(JSON.stringify({ id, method, params }));
    });
    const evaluate = async (expression) => {
      const reply = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (reply.exceptionDetails) throw new Error(reply.exceptionDetails.text || 'Browser evaluation failed');
      return reply.result.value;
    };
    const until = async (expression, label) => {
      for (let i = 0; i < 150; i += 1) {
        if (await evaluate(expression)) return;
        await wait(100);
      }
      throw new Error(`Timed out waiting for ${label}`);
    };
    const screenshot = async (name) => {
      const { data } = await send('Page.captureScreenshot', { format: 'png' });
      fs.mkdirSync(evidence, { recursive: true });
      fs.writeFileSync(path.join(evidence, `${name}.png`), Buffer.from(data, 'base64'));
    };
    const login = async (email) => {
      await send('Page.navigate', { url: base.href });
      await until("!!document.querySelector('#login-form input[name=email]')", 'login form');
      const values = JSON.stringify({ email, password: process.env.DEMO_PASSWORD });
      await evaluate(`(() => { const values = ${values}; const form = document.querySelector('#login-form');
        form.elements.email.value = values.email; form.elements.password.value = values.password;
        form.requestSubmit(); return true; })()`);
      await until("!document.querySelector('#app').classList.contains('hidden') && document.querySelector('#organization-select').options.length > 0", 'signed-in workspace');
    };

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride',
      { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    await login(process.env.DEMO_EMAIL);
    await evaluate("document.querySelector('.topbar .brand').click(); true");
    await until("location.hash === '#/overview' && !document.querySelector('#app').classList.contains('hidden')",
      'brand returning to Overview without sign-out');
    await evaluate("document.querySelector('#workspace-nav button[data-screen=\"my-assessments\"]').click(); true");
    await until("document.querySelector('#assignments-list')?.textContent.includes('Assigned through a batch')", 'real batch assignment');
    const opened = await evaluate(`(() => { const row = [...document.querySelectorAll('#assignments-list .item')]
      .find((entry) => entry.textContent.includes('Assigned through a batch'));
      const button = [...row.querySelectorAll('button')].find((entry) => entry.textContent.includes('Open assessment'));
      if (!button) return false; button.click(); return true; })()`);
    if (!opened) throw new Error('The batch assessment did not offer an Open assessment action.');
    await until("document.querySelector('#assessment-detail-attempts')?.textContent.includes('SUBMITTED')", 'submitted attempt');
    const resultOpened = await evaluate(`(() => { const button = [...document.querySelectorAll('#assessment-detail-attempts button')]
      .find((entry) => entry.textContent.includes('View result'));
      if (!button) return false; button.click(); return true; })()`);
    if (!resultOpened) throw new Error('The submitted attempt did not offer a result action.');
    await until("!!document.querySelector('#assessment-detail-result .result-hero')", 'released result');
    const score = await evaluate("document.querySelector('#assessment-detail-result .result-score').textContent");
    const topics = await evaluate("document.querySelectorAll('#assessment-detail-result .topic-list > *').length");
    if (!Number.isInteger(Number(score)) || Number(score) < 0 || Number(score) > 100 || topics < 1) {
      throw new Error(`Unexpected live result: ${score}%, ${topics} topics.`);
    }
    await evaluate("document.documentElement.setAttribute('data-theme', 'light'); document.querySelector('#assessment-detail-result').scrollIntoView(); true");
    await wait(200);
    await screenshot('learner-result-light');
    await evaluate("document.documentElement.setAttribute('data-theme', 'dark'); true");
    await wait(200);
    await screenshot('learner-result-dark');

    await login(process.env.DEMO_OWNER_EMAIL);
    const selected = await evaluate(`(() => { const select = document.querySelector('#organization-select');
      const option = [...select.options].find((entry) => entry.textContent.toLowerCase().includes('academy'));
      if (!option) return false; select.value = option.value;
      select.dispatchEvent(new Event('change', { bubbles: true })); return true; })()`);
    if (!selected) throw new Error('Owner has no Academy organization.');
    await until("!!document.querySelector('#workspace-nav button[data-screen=\"academy\"]')", 'Academy navigation');
    await evaluate("document.querySelector('#workspace-nav button[data-screen=\"academy\"]').click(); true");
    await until("!!document.querySelector('#screen-tabs button[data-view=\"academy-batch\"]')", 'batch workspace tab');
    await evaluate("document.querySelector('#screen-tabs button[data-view=\"academy-batch\"]').click(); true");
    await until("!![...document.querySelectorAll('#academy-batch-assessments button')].find((button) => button.textContent.includes('Progress & analytics'))", 'real batch assessment');
    await evaluate(`(() => { const button = [...document.querySelectorAll('#academy-batch-assessments button')]
      .find((entry) => entry.textContent.includes('Progress & analytics')); button.click(); return true; })()`);
    await until("!!document.querySelector('#academy-batch-analytics .funnel')", 'real cohort funnel');
    const funnel = await evaluate("document.querySelector('#academy-batch-analytics .funnel').textContent");
    if (!funnel.includes('Submitted') || !funnel.includes('Graded')) throw new Error('Cohort funnel omitted real progress.');
    const roster = await evaluate("document.querySelector('#academy-batch-learners').textContent + ' ' + document.querySelector('#academy-batch-progress').textContent");
    if (/Learner [0-9a-f]{8}-[0-9a-f-]{27}/i.test(roster)) {
      throw new Error('The Academy roster still shows raw learner IDs instead of display names.');
    }
    await evaluate("document.documentElement.setAttribute('data-theme', 'light'); document.querySelector('#academy-batch-selected').scrollIntoView(); true");
    await wait(200);
    await screenshot('academy-cohort-light');
    await evaluate("document.documentElement.setAttribute('data-theme', 'dark'); true");
    await wait(200);
    await screenshot('academy-cohort-dark');
    if (errors.length) throw new Error(`Browser JavaScript errors: ${errors.slice(0, 2).join(' | ')}`);
    console.log(`LIVE local browser PASS: learner result ${score}% / ${topics} topics; Academy cohort populated; 4 screenshots in ${evidence}`);
  } finally {
    if (socket) socket.close();
    child.kill();
    await wait(500);
    if (path.dirname(profile) === os.tmpdir() && path.basename(profile).startsWith('jachai-live-')) {
      try { fs.rmSync(profile, { recursive: true, force: true }); } catch { /* Chrome may release files later. */ }
    }
  }
}

main().catch((error) => { console.error(`LIVE local browser FAIL: ${error.message}`); process.exitCode = 1; });
