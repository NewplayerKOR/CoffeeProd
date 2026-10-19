import assert from 'node:assert/strict';
import { approvedSshBaseUrl } from './ssh-probe-target.mjs';
assert.equal(approvedSshBaseUrl(undefined), 'http://127.0.0.1:18080');
assert.equal(approvedSshBaseUrl('http://127.0.0.1:18080'), 'http://127.0.0.1:18080');
assert.equal(approvedSshBaseUrl('http://127.0.0.1:18081'), 'http://127.0.0.1:18081');
assert.equal(approvedSshBaseUrl('http://127.0.0.1:18082'), 'http://127.0.0.1:18082');
for (const url of ['https://api-coffeeprod.ttagyulab.com', 'http://192.168.0.8:8080',
  'http://127.0.0.1:18083', 'http://127.0.0.1:18081/api/v1/auth/login', 'http://user:secret@127.0.0.1:18081',
  'http://127.0.0.1:18081?redirect=1', 'http://127.0.0.1:18081/', 'http://localhost:18081']) {
  assert.throws(() => approvedSshBaseUrl(url));
}
console.log('PASS: 12 fixed target allowlist cases.');
