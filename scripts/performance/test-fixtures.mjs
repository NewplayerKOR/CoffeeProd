import fs from 'node:fs';
import path from 'node:path';
import {matrix,expectedSamples} from '../../k6/phase2/protocol.mjs';
export const fixtureIdentity={sourceHash:'a'.repeat(64),measurementHash:'b'.repeat(64),owner:'11111111-1111-4111-8111-111111111111',instanceId:'22222222-2222-4222-8222-222222222222',processStartedAt:'2026-11-02T00:00:00Z'};
export function summary(candidate,variant,stage,value=100,identity=fixtureIdentity){
  const count=stage==='warmup'?(candidate===3?4:candidate===6?30:300):(candidate===3?1:candidate===6?120:1200);
  return {protocolVersion:2,candidate,variant,stage,...identity,metrics:{checks:{values:{passes:count*2,fails:0}},http_req_failed:{values:{rate:0}},dropped_iterations:{values:{count:0}},phase2_completed:{values:{count}},phase2_business_duration:{values:{count,avg:value,'p(95)':value}}},state:{testRunDurationMs:stage==='warmup'?30001:candidate===3?100:120001}};
}
export function writeJson(file,value){fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,JSON.stringify(value));}
export function comparisonFixture(mode='All'){
  fs.mkdirSync('build/dev-task8',{recursive:true});const root=fs.mkdtempSync(path.resolve('build/dev-task8/comparison-v2-'));
  const conditions={...fixtureIdentity,protocolVersion:2,mode,baseUrl:'http://192.168.0.8:18080',fixtureHash:'c'.repeat(64),warmupSeconds:30,warmupPolicy:'restore, warmup, measurement; same app instance per repetition',warmupWriteOperations:30,ratePerSecond:10,durationSeconds:120,repetitions:3,writeRatePerSecond:1,statistics:'30 second serial warmup; single measured operation per repetition',scheduler:'45s',k6:'fixed',localMachine:'synthetic',localProcessors:4,localOS:'synthetic'};
  for(const phase of ['before','after']){
    writeJson(path.join(root,phase,'conditions.json'),conditions);
    const samples=expectedSamples(undefined,mode).map(s=>({...s,result:'PASS'}));
    writeJson(path.join(root,phase,'execution.json'),{protocolVersion:2,mode,status:'PASS',allEightCandidates:mode==='All',candidates:[1,2,3,4,5,6,7,8],...fixtureIdentity,samples});
    for(const {sample,kind,candidate:c,variant:v}of samples){
      const folder=path.join(root,phase,sample);
      if(kind==='diagnostic'){
        const data=c===1?{golden:phase==='before'?[{id:1,score:5}]:[{score:5,id:1}]}:{};
        writeJson(path.join(folder,'diagnostics',`case${c}-${v}`,'measurements.json'),data);continue;
      }
      const points=['beforeWarmup','afterWarmup','beforeMeasurement','afterMeasurement'];
      writeJson(path.join(folder,'lifecycle.json'),{protocolVersion:2,candidate:c,variant:v,status:'PASS',checkpoints:points.map((point,i)=>({point,...fixtureIdentity,protocolVersion:2,diagnostic:false,disposable:true,observedAtUtc:new Date(Date.UTC(2026,10,2,0,0,i*31)).toISOString()}))});
      for(const stage of ['warmup','measurement']){
        writeJson(path.join(folder,stage,'summary.json'),summary(c,v,stage,phase==='before'?100:70));
        for(const [name,value]of [['timeseries.json','{"type":"fixture"}\n'],['stdout.log','synthetic test evidence'],['stderr.log','']])fs.writeFileSync(path.join(folder,stage,name),value);
        writeJson(path.join(folder,stage,'process.json'),{stage,exitCode:0,startedAtUtc:'2026-11-02T00:00:00Z',finishedAtUtc:'2026-11-02T00:02:30Z'});
      }
      if(c===6){const n=Number(v.replace('order',''));for(const [stage,delta]of [['before',0],['after',30]])writeJson(path.join(folder,`write-state-${stage}.json`),{orders:150000+delta,orderItems:375000+delta*n,mileageTotal:100000,products:Array.from({length:n},(_,i)=>({productId:i+1,stock:10000000-delta}))});}
    }
  }
  return {root,write:writeJson};
}
