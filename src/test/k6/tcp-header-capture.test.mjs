import assert from 'node:assert/strict';
import { tcpHeader, readCapture } from './tcp-header-capture.mjs';

function frame(link = 276) {
  const layer2 = link === 276 ? 20 : 14, data = Buffer.alloc(layer2 + 40);
  if (link === 276) { data.writeUInt16BE(0x0800, 0); data.writeUInt32BE(3, 4); }
  else data.writeUInt16BE(0x0800, 12);
  data[layer2] = 0x45; data.writeUInt16BE(190, layer2 + 2); data[layer2 + 9] = 6;
  data.set([192, 168, 0, 2], layer2 + 12); data.set([192, 168, 0, 8], layer2 + 16);
  const tcp = layer2 + 20; data.writeUInt16BE(49911, tcp); data.writeUInt16BE(22, tcp + 2);
  data.writeUInt32BE(100, tcp + 4); data.writeUInt32BE(200, tcp + 8); data[tcp + 12] = 0x50; data[tcp + 13] = 0x18;
  return data;
}
let count = 0;
function test(name, action) { action(); count++; console.log(`PASS: ${name}`); }
test('SLL2 metadata and uncaptured original length', () => {
  const data = tcpHeader(frame(), 276); assert.equal(data.sequence, 100); assert.equal(data.acknowledgment, 200);
  assert.equal(data.original_tcp_payload_bytes, 150); assert.equal(data.captured_tcp_payload_bytes, 0);
});
test('Ethernet metadata', () => assert.equal(tcpHeader(frame(1), 1).destination_port, 22));
test('payload is rejected, not silently trimmed', () => assert.throws(() => tcpHeader(Buffer.concat([frame(), Buffer.from('SECRET')]), 276)));
test('unknown link type rejected', () => assert.throws(() => tcpHeader(frame(), 101)));
test('truncated headers rejected', () => assert.throws(() => tcpHeader(frame().subarray(0, 50), 276)));
test('fragmented packets rejected', () => { const data = frame(); data.writeUInt16BE(1, 26); assert.throws(() => tcpHeader(data, 276)); });
test('first fragment with more-fragments bit rejected', () => { const data = frame(); data.writeUInt16BE(0x2000, 26); assert.throws(() => tcpHeader(data, 276)); });
test('invalid TCP offset rejected', () => { const data = frame(); data[52] = 0x30; assert.throws(() => tcpHeader(data, 276)); });
test('classic pcap record parsed', () => {
  const header = Buffer.alloc(24), record = Buffer.alloc(16); header.writeUInt32LE(0xa1b2c3d4); header.writeUInt32LE(276, 20);
  record.writeUInt32LE(1000); record.writeUInt32LE(500000, 4); record.writeUInt32LE(60, 8);
  const data = readCapture(Buffer.concat([header, record, frame()])); assert.equal(data[0].timestamp_unix_seconds, 1000.5);
});
test('empty capture is not PASS', () => { const header = Buffer.alloc(24); header.writeUInt32LE(0xa1b2c3d4); assert.throws(() => readCapture(header)); });
test('malformed block rejected', () => assert.throws(() => readCapture(Buffer.from('0a0d0d0a', 'hex'))));
test('pcapng interface and packet parsed', () => {
  const shb = Buffer.alloc(28); shb.writeUInt32LE(0x0a0d0d0a); shb.writeUInt32LE(28, 4); shb.writeUInt32LE(0x1a2b3c4d, 8); shb.writeUInt32LE(28, 24);
  const idb = Buffer.alloc(20); idb.writeUInt32LE(1); idb.writeUInt32LE(20, 4); idb.writeUInt16LE(1, 8); idb.writeUInt32LE(20, 16);
  const epb = Buffer.alloc(88); epb.writeUInt32LE(6); epb.writeUInt32LE(88, 4); epb.writeUInt32LE(1000000, 16); epb.writeUInt32LE(54, 20);
  frame(1).copy(epb, 28); epb.writeUInt32LE(88, 84);
  assert.equal(readCapture(Buffer.concat([shb, idb, epb]))[0].timestamp_unix_seconds, 1);
});
console.log(`Passed ${count}/${count} offline capture checks.`);
