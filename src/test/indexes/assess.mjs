import fs from 'node:fs';
import path from 'node:path';

// Explicit engineering acceptance thresholds, not a significance test or an HTTP performance claim.
const root = path.resolve(process.argv[2] ?? '');
if (!root.startsWith(path.resolve('build/dev-task9') + path.sep)) throw Error('Expected dedicated evidence directory');
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const change = row => (median(row.candidateNanos) / median(row.beforeNanos) - 1) * 100;
const blocks = (row, side) => row.plans.filter(p => p.mode === 'force_generic_plan')
  .reduce((sum, p) => sum + (p[side][0].Plan['Shared Hit Blocks'] ?? 0) + (p[side][0].Plan['Shared Read Blocks'] ?? 0), 0);
function read(row) {
  const before = median(row.beforeNanos), after = median(row.candidateNanos);
  return { name: row.name, beforeMs: before / 1e6, candidateMs: after / 1e6, changePercent: change(row),
    eachBatchImproved: row.beforeNanos.every((n, i) => row.candidateNanos[i] < n),
    beforeBlocks: blocks(row, 'before'), candidateBlocks: blocks(row, 'candidate'),
    qualifies: after <= before * 0.8 && before - after >= 200000 &&
      row.beforeNanos.every((n, i) => row.candidateNanos[i] < n) && blocks(row, 'candidate') < blocks(row, 'before') };
}
const decisions = [];
for (const family of ['reviews', 'qna', 'qna-admin', 'orders', 'payment', ...(fs.existsSync(path.join(root, 'candidate-orders-cover.json')) ? ['orders-cover'] : [])]) {
  const raw = JSON.parse(fs.readFileSync(path.join(root, `candidate-${family}.json`), 'utf8'));
  if (raw.writes.some(w => w.affectedRowsPerCall !== 1)) throw Error('Incomplete or obsolete write measurements: ' + family);
  const reads = raw.reads.map(read);
  const writes = raw.writes.map(w => ({ name: w.name, beforeMs: median(w.beforeNanos) / 1e6,
    candidateMs: median(w.candidateNanos) / 1e6, changePercent: change(w), affectedRows: w.affectedRowsPerCall }));
  const goals = reads.filter(r => family === 'payment' ? /^payment-(day|group)-(1|30)$/.test(r.name)
    : family === 'qna-admin' ? /^qna-admin-(WAITING|ANSWERED)-(10|50)$/.test(r.name)
      : /^(review|qna|orders)-(10|50)$/.test(r.name));
  const readRegressions = reads.filter(r => r.changePercent > 10);
  const writeRegressions = writes.filter(w => w.changePercent > 10 || w.affectedRows !== 1);
  const accepted = goals.some(r => r.qualifies) && !readRegressions.length && !writeRegressions.length;
  decisions.push({ family, decision: accepted ? 'adopt' : 'hold', goals, reads, writes,
    readRegressions: readRegressions.map(r => r.name), writeRegressions: writeRegressions.map(w => w.name),
    reason: accepted ? 'Read gain, reduced buffers, all measured read/write regressions within 10%'
      : !goals.some(r => r.qualifies) ? 'No stable target gain meeting 20% and 0.2ms with reduced buffers'
        : 'Read or write regression exceeds 10%; retain the original schema' });
}
if (decisions.find(d => d.family === 'orders-cover')?.decision === 'adopt') {
  const plain = decisions.find(d => d.family === 'orders');
  plain.decision = 'hold'; plain.reason = 'The covering alternative supersedes the plain date index';
}
const output = { scope: 'local dedicated PostgreSQL at original fixture scale; no production or HTTP claim',
  thresholds: { readReductionPercent: 20, minimumReadReductionMs: 0.2, maximumRegressionPercent: 10, batches: 3, callsPerBatch: 100, warmups: 20 }, decisions };
fs.writeFileSync(path.join(root, 'decisions.json'), JSON.stringify(output, null, 2) + '\n');
for (const d of decisions) console.log(JSON.stringify({ family: d.family, decision: d.decision, reason: d.reason,
  goals: d.goals.map(g => ({ name: g.name, changePercent: g.changePercent })), readRegressions: d.readRegressions, writeRegressions: d.writeRegressions }));
const finalPath = path.join(root, 'final-combination.json');
if (fs.existsSync(finalPath)) {
  const raw = JSON.parse(fs.readFileSync(finalPath, 'utf8'));
  const reads = raw.reads.map(r => ({ ...read(r), family: r.family }));
  const regressions = reads.filter(r => r.changePercent > 10);
  const writes = raw.writes.map(w => ({ name: w.name, changePercent: change(w), affectedRows: w.affectedRowsPerCall }));
  const writeRegressions = writes.filter(w => w.changePercent > 10 || w.affectedRows !== 1);
  const selected = raw.selectedFamilies ?? decisions.filter(d => d.decision === 'adopt').map(d => d.family);
  const gainMissing = decisions.filter(d => selected.includes(d.family) &&
    !reads.some(r => r.family === (d.family === 'orders-cover' ? 'orders' : d.family) && r.qualifies)).map(d => d.family);
  const check = { passed: !regressions.length && !writeRegressions.length && !gainMissing.length,
    readConditions: reads.length, reads, writes, regressions, writeRegressions, gainMissing };
  fs.writeFileSync(path.join(root, 'combination-check.json'), JSON.stringify(check, null, 2) + '\n');
  console.log(JSON.stringify({ combinationPassed: check.passed, regressions: regressions.map(r => r.name), writeRegressions, gainMissing }));
  for (const decision of decisions) {
    if (gainMissing.includes(decision.family) || decision.decision === 'adopt' && !selected.includes(decision.family)) {
      decision.decision = 'hold';
      decision.reason = 'Final-combination generic-plan buffer reduction was not proven; excluded from the final schema';
    }
  }
  fs.writeFileSync(path.join(root, 'decisions.json'), JSON.stringify(output, null, 2) + '\n');
}
