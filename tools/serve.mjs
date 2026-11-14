// Tiny static server for local testing of trigger/. Node built-ins only.
// Usage: node trigger/tools/serve.mjs [port]     (port 0 or omitted: any free port)
// It binds to 127.0.0.1 only and serves nothing outside trigger/.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { dirname, extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
};

const server = createServer(async (request, response) => {
  try {
    let path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (path.endsWith('/')) path += 'index.html';
    const file = normalize(join(ROOT, path));
    const type = TYPES[extname(file)];
    if (!file.startsWith(ROOT + sep) || type === undefined) throw new Error('not served');
    const body = await readFile(file);
    response.writeHead(200, {
      'Content-Type': type,
      'Content-Length': body.length,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    response.end(request.method === 'HEAD' ? undefined : body);
  } catch {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not found\n');
  }
});

server.listen(Number(process.argv[2] ?? 0), '127.0.0.1', () => {
  process.stdout.write(`http://127.0.0.1:${server.address().port}/\n`);
});
