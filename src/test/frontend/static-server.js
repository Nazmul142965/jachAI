// Minimal dependency-free static server for the frontend fixture checks.
// Serves the real templates and static assets exactly as the app does (the page controller only
// returns the view name, so the bytes a browser sees are the same), without booting Spring.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png' };

function startServer(root, port = 0) {
  const templates = path.join(root, 'templates');
  const statics = path.join(root, 'static');
  const server = http.createServer((request, response) => {
    const requested = decodeURIComponent((request.url || '/').split('?')[0]);
    const name = requested === '/' ? 'index.html' : requested.replace(/^\/+/, '');
    const candidates = [path.join(templates, name), path.join(statics, name)];
    const file = candidates.find((candidate) => fs.existsSync(candidate) && fs.statSync(candidate).isFile());
    if (!file) { response.writeHead(404, { 'Content-Type': 'text/plain' }); response.end('not found'); return; }
    response.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    response.end(fs.readFileSync(file));
  });
  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => {
      resolve({ url: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((done) => server.close(done)) });
    });
  });
}

module.exports = { startServer };

if (require.main === module) {
  const root = path.join(__dirname, '..', '..', 'main', 'resources');
  startServer(root).then(({ url }) => console.log(`serving ${root} at ${url}`));
}
