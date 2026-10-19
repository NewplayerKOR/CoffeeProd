import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile), body = JSON.stringify({ never_export_this_body: true });
let requests = 0;
const server = createServer((req, res) => {
  requests++;
  res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(body);
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
try {
  const url = `http://127.0.0.1:${server.address().port}/`;
  const args = ['--disable', '--noproxy', '*', '--silent', '--show-error', '--fail', '--fail-early',
    '--max-time', '5', '--http1.1', '--rate', '3/s', '--write-out',
    '{"round":%{urlnum},"status":%{http_code},"num_connects":%{num_connects}}\n'];
  for (let i = 0; i < 3; i++) args.push('--output', 'NUL', url);
  const { stdout, stderr } = await run('curl.exe', args, { timeout: 10000 });
  assert.equal(stderr, ''); assert.equal(requests, 3); assert.ok(!stdout.includes('never_export_this_body'));
  const rows = stdout.trim().split(/\r?\n/).map(JSON.parse);
  assert.equal(rows.length, 3); assert.deepEqual(rows.map((row) => row.round), [0, 1, 2]);
  assert.ok(rows.every((row) => row.status === 200));
  assert.equal(rows.reduce((sum, row) => sum + row.num_connects, 0), 1);
  const fresh = await run('curl.exe', [...args, '--header', 'Connection: close'], { timeout: 10000 });
  assert.equal(fresh.stderr, ''); assert.ok(!fresh.stdout.includes('never_export_this_body'));
  const freshRows = fresh.stdout.trim().split(/\r?\n/).map(JSON.parse);
  assert.equal(freshRows.length, 3); assert.equal(requests, 6);
  assert.ok(freshRows.every((row) => row.status === 200 && row.num_connects === 1));
  console.log('PASS: six local transfers, no body in stdout, ordered JSON, reused one connection versus three fresh connections.');
} finally {
  server.closeAllConnections(); await new Promise((resolve) => server.close(resolve));
}
