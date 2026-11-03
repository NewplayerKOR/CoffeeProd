import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const requireThat=(condition,message)=>{if(!condition)throw new Error(message);};
export function validateStage(data,candidate,stage){
  requireThat(data.protocolVersion===2&&data.stage===stage,'Missing/mismatched HTTP stage protocol');
  requireThat(data.candidate===candidate,'Mismatched candidate');
  const m=data.metrics||{},count=m.phase2_business_duration?.values?.count;
  requireThat(m.checks?.values?.passes>0&&m.checks.values.fails===0,'Missing/passing checks evidence');
  requireThat(m.http_req_failed?.values?.rate===0,'HTTP errors or missing HTTP evidence');
  requireThat((m.dropped_iterations?.values?.count??0)===0,'Dropped iterations invalidate the offered load');
  requireThat(Number.isInteger(count)&&count>0,'Missing completed business operations');
  requireThat(m.phase2_completed?.values?.count===count,'Business operation completion mismatch');
  const elapsed=data.state?.testRunDurationMs;
  requireThat(Number.isFinite(elapsed)&&elapsed>0,'Missing k6 execution duration');
  if(stage==='warmup'){
    requireThat(elapsed>=30000,'Warmup did not last 30 seconds');
    if(candidate!==3)requireThat(count===(candidate===6?30:300),'Incomplete/excess warmup business operations');
  }else if(candidate===3)requireThat(count===1,'Statistics requires exactly one measured request');
  else requireThat(elapsed>=120000&&count>=(candidate===6?120:1200),'Incomplete two-minute measurement');
  requireThat(Number.isFinite(m.phase2_business_duration.values.avg)&&Number.isFinite(m.phase2_business_duration.values['p(95)']),'Missing timing values');
  return data;
}
export function validateWriteState(before,after,size){
  requireThat([1,5,20].includes(size),'Invalid order size');
  requireThat(after.orders-before.orders===30,'Warmup must create exactly 30 orders');
  requireThat(after.orderItems-before.orderItems===30*size,'Warmup order-item delta mismatch');
  requireThat(before.mileageTotal===after.mileageTotal,'Zero-mileage warmup changed member mileage');
  requireThat(before.products?.length===size&&after.products?.length===size,'Missing warmup stock evidence');
  for(let i=0;i<size;i++)requireThat(before.products[i].productId===after.products[i].productId&&before.products[i].stock-after.products[i].stock===30,'Warmup stock delta mismatch');
}
export function validateSession(folder,candidate,identity){
  const life=read(path.join(folder,'lifecycle.json'));
  requireThat(life.protocolVersion===2&&life.candidate===candidate&&life.status==='PASS','Incomplete HTTP lifecycle');
  const points=['beforeWarmup','afterWarmup','beforeMeasurement','afterMeasurement'];
  requireThat(Array.isArray(life.checkpoints)&&JSON.stringify(life.checkpoints.map(p=>p.point))===JSON.stringify(points),'Missing lifecycle checkpoints');
  const first=life.checkpoints[0];
  requireThat(/^[a-f0-9-]{36}$/.test(first.instanceId)&&Number.isFinite(Date.parse(first.processStartedAt)),'Missing app process identity');
  let last=-Infinity;
  for(const p of life.checkpoints){
    const at=Date.parse(p.observedAtUtc);
    requireThat(Number.isFinite(at)&&at>=last,'Out-of-order lifecycle checkpoints');last=at;
    requireThat(p.instanceId===first.instanceId&&p.processStartedAt===first.processStartedAt,'App restarted between warmup and measurement');
    requireThat(p.sourceHash===identity.sourceHash&&p.measurementHash===identity.measurementHash&&p.owner===identity.owner&&p.protocolVersion===2&&p.diagnostic===false&&p.disposable===true,'Lifecycle source/owner mismatch');
  }
  const warmup=validateStage(read(path.join(folder,'warmup/summary.json')),candidate,'warmup');
  const measurement=validateStage(read(path.join(folder,'measurement/summary.json')),candidate,'measurement');
  for(const [stage,data]of [['warmup',warmup],['measurement',measurement]]){
    requireThat(data.variant===life.variant&&data.sourceHash===identity.sourceHash&&data.measurementHash===identity.measurementHash&&data.instanceId===first.instanceId&&data.processStartedAt===first.processStartedAt,'HTTP summary identity mismatch');
    for(const name of ['timeseries.json','stdout.log','stderr.log'])requireThat(fs.existsSync(path.join(folder,stage,name)),`Missing HTTP artifact: ${stage}/${name}`);
    requireThat(fs.statSync(path.join(folder,stage,'timeseries.json')).size>0,'Empty raw HTTP data');
    const process=read(path.join(folder,stage,'process.json'));
    requireThat(process.stage===stage&&process.exitCode===0&&Number.isFinite(Date.parse(process.startedAtUtc))&&Date.parse(process.finishedAtUtc)>=Date.parse(process.startedAtUtc),'Missing/failed HTTP process evidence');
  }
  if(candidate===6)validateWriteState(read(path.join(folder,'write-state-before.json')),read(path.join(folder,'write-state-after.json')),Number(life.variant.replace('order','')));
  return {life,warmup,measurement};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const [command,folder,candidate,stage]=process.argv.slice(2);
  if(command==='stage')validateStage(read(path.join(folder,'summary.json')),Number(candidate),stage);
  else if(command==='write')validateWriteState(read(path.join(folder,'write-state-before.json')),read(path.join(folder,'write-state-after.json')),Number(candidate));
  else if(command==='session')validateSession(folder,Number(candidate),read(path.join(folder,'identity.json')));
  else throw new Error('Expected stage, write or session validation');
  console.log('Evidence verified');
}
