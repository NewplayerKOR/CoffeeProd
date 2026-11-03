import fs from 'node:fs';
import path from 'node:path';
import {matrix,expectedSamples} from '../../k6/phase2/protocol.mjs';
import {validateSession} from './evidence.mjs';
const root=path.resolve(process.argv[2]||'');
if(!process.argv[2])throw new Error('Usage: node scripts/performance/compare.mjs build/perf-phase2/<run-id>');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const conditions=Object.fromEntries(['before','after'].map(phase=>[phase,read(path.join(root,phase,'conditions.json'))]));
const {before,after}=conditions;
for(const key of ['protocolVersion','mode','baseUrl','owner','measurementHash','fixtureHash','warmupSeconds','warmupPolicy','warmupWriteOperations','ratePerSecond','durationSeconds','repetitions','writeRatePerSecond','statistics','scheduler','k6','localMachine','localProcessors','localOS']){
  if(before[key]===undefined||after[key]===undefined||JSON.stringify(before[key])!==JSON.stringify(after[key]))throw new Error(`Incomparable condition: ${key}`);
}
if(before.protocolVersion!==2||before.repetitions!==3||before.durationSeconds!==120||before.warmupSeconds!==30||before.warmupWriteOperations!==30)throw new Error('Expected protocolVersion 2 with per-repetition warmup; original results remain unchanged');
let selected;
for(const phase of ['before','after']){
  const execution=read(path.join(root,phase,'execution.json'));
  const ids=[...new Set(execution.candidates)].sort();
  if(!ids.length||ids.some(c=>!Number.isInteger(c)||c<1||c>8))throw new Error('Invalid candidates');
  if(selected&&JSON.stringify(ids)!==JSON.stringify(selected))throw new Error('Incomparable candidates');selected=ids;
  const expected=expectedSamples(ids,before.mode).map(s=>s.sample).sort();
  if(execution.protocolVersion!==2||execution.mode!==before.mode||execution.status!=='PASS'||execution.sourceHash!==conditions[phase].sourceHash||execution.measurementHash!==conditions[phase].measurementHash||execution.samples.some(s=>s.result!=='PASS')||JSON.stringify(execution.samples.map(s=>s.sample).sort())!==JSON.stringify(expected))throw new Error(`Incomplete ${phase} evidence`);
}
const median=values=>[...values].sort((a,b)=>a-b)[1];
const rows=[];
if(before.mode!=='Diagnostics')for(const {candidate,variant}of matrix.filter(r=>selected.includes(r.candidate)&&r.candidate!==4)){
  const timings={};
  for(const phase of ['before','after'])timings[phase]=[1,2,3].map(i=>{
    const result=validateSession(path.join(root,phase,`http-c${candidate}-${variant}-r${i}`),candidate,conditions[phase]);
    return result.measurement.metrics.phase2_business_duration.values[candidate===3?'avg':'p(95)'];
  });
  const a=median(timings.before),b=median(timings.after);
  rows.push({candidate,variant,metric:candidate===3?'singleRequestDurationMs':'businessP95Ms',beforeValues:timings.before,afterValues:timings.after,beforeMedian:a,afterMedian:b,changePercent:a===0?null:(b-a)/a*100,result:b<a?'개선':b>a?'회귀':'동등'});
}
const golden=[];
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
if(before.mode!=='Http')for(const {candidate,variant}of matrix.filter(r=>selected.includes(r.candidate)))for(const repeat of [1,2,3]){
  const relative=`diag-c${candidate}-${variant}-r${repeat}/diagnostics/case${candidate}-${variant}/measurements.json`;
  const a=read(path.join(root,'before',relative)),b=read(path.join(root,'after',relative));
  if(candidate===1){if(!a.golden||!b.golden)throw new Error('Missing recommendation business output');golden.push({sample:relative,matches:JSON.stringify(canonical(a.golden))===JSON.stringify(canonical(b.golden))});}
}
if(golden.some(g=>!g.matches))throw new Error('Recommendation business output changed');
const report={protocolVersion:2,mode:before.mode,candidates:selected,scope:'isolated measurements; three-run median; single-request statistics are not percentile distributions',conditionsMatch:true,rows,recommendationGolden:golden,diagnosticsIncluded:before.mode!=='Http',indexDecision:'DEV9 records conditional adoption/hold with plans and write cost; no automatic index adoption',historical27Percent:'not combined with these measurements'};
const output=path.join(root,'comparison');
if(fs.existsSync(output))throw new Error('Comparison evidence already exists; preserve the previous output');
fs.mkdirSync(output);fs.writeFileSync(path.join(output,'comparison.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({rows:rows.length,golden:golden.length,output}));
