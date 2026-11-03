import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';
import { validateTarget,validateRunId,scenarioFor } from './protocol.mjs';
const base=validateTarget(__ENV.PERF_BASE_URL);
validateRunId(__ENV.PERF_RUN_ID);
const candidate=Number(__ENV.PERF_CANDIDATE), variant=__ENV.PERF_VARIANT;
if(!/^[1-8]$/.test(String(candidate)))throw new Error('Invalid candidate');
if(!/^[a-f0-9]{64}$/.test(__ENV.PERF_SOURCE_HASH||'')||!/^[a-f0-9]{64}$/.test(__ENV.PERF_MEASUREMENT_HASH||''))throw new Error('Source identity required');
const users=JSON.parse(open(__ENV.PERF_USERS_FILE));
const productIds=open(__ENV.PERF_PRODUCTS_FILE).trim().split(/\s+/).map(Number);
const duration=new Trend('phase2_business_duration',true);
const completed=new Counter('phase2_completed');
const stage=__ENV.PERF_STAGE;
const tag=stage==='warmup'?'warmup':'measure';
const scenario=scenarioFor(candidate,stage);
if(!/^[a-f0-9-]{36}$/.test(__ENV.PERF_INSTANCE_ID||''))throw new Error('App instance identity required');
export const options={
  scenarios:{[stage]:scenario},
  thresholds:{checks:['rate==1'],[`http_req_failed{phase:${tag}}`]:['rate==0'],dropped_iterations:['count==0']},
  summaryTrendStats:['avg','med','p(95)','p(99)','min','max','count'],
};
function params(token,phase=tag){return{headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},tags:{phase,candidate:String(candidate),variant},timeout:'10m'};}
function ok(response,status=200){const pass=check(response,{[`status ${status}`]:r=>r.status===status,'common response':r=>{try{return r.json().status===200&&r.json().errors===null;}catch{return false;}}});if(!pass)fail('HTTP contract failed (response/credentials omitted)');return response.json().data;}
function login(email,password){const r=http.post(`${base}/api/v1/auth/login`,JSON.stringify({email,password}),{headers:{'Content-Type':'application/json'},tags:{phase:'setup'}});return ok(r).accessToken;}
export function setup(){
  const admin=login('loadtest-admin@coffeeprod.local','password');
  const response=http.get(`${base}/api/v1/admin/performance/manifest`,params(admin,'setup'));
  if(response.status!==200)fail('Dedicated manifest unavailable');const m=response.json();
  if(m.protocolVersion!==2||m.instanceId!==__ENV.PERF_INSTANCE_ID||Date.parse(m.processStartedAt)!==Date.parse(__ENV.PERF_PROCESS_STARTED_AT)||m.sourceHash!==__ENV.PERF_SOURCE_HASH||m.measurementHash!==__ENV.PERF_MEASUREMENT_HASH||m.owner!==__ENV.PERF_OWNER||m.diagnostic!==false||!m.disposable)fail('Remote measurement identity mismatch');
  const actors=users.slice(0,20).map(u=>({...u,token:login(u.email,u.password)}));
  return{admin,actors};
}
export default function(data){
  // k6 can schedule the boundary iteration too. Keep warmup writes exactly reproducible.
  if(stage==='warmup'&&candidate!==3&&exec.scenario.iterationInTest>=(candidate===6?30:300))return;
  const actor=data.actors[(exec.vu.idInTest-1)%data.actors.length],p=params(actor.token),start=Date.now();
  if(candidate===1){const rows=ok(http.post(`${base}/api/v1/coffee-recommendations`,JSON.stringify({limit:10,preferredAcidity:3,preferredBody:4}),p));check(rows,{'recommendation limit':r=>Array.isArray(r)&&r.length<=10});}
  else if(candidate===2||candidate===7){const cart=ok(http.get(`${base}/api/v1/carts`,p));check(cart,{'cart structure':c=>Array.isArray(c.items)&&Number.isInteger(c.totalQuantity)});}
  else if(candidate===3){const days=Number(variant.replace('days',''));const end=new Date('2026-06-30T00:00:00Z'),from=new Date(end.getTime()-(days-1)*86400000).toISOString().slice(0,10);ok(http.post(`${base}/api/v1/admin/statistics/sales/aggregate/range?from=${from}&to=2026-06-30`,null,params(data.admin)));}
  else if(candidate===5){const size=Number(variant.replace('page',''));for(const [route,t] of [['orders',actor.token],['admin/orders',data.admin]]){const page=ok(http.get(`${base}/api/v1/${route}?size=${size}`,params(t)));check(page,{'page structure':x=>Array.isArray(x.content)&&x.content.length<=size&&Number.isInteger(x.totalElements)});}}
  else if(candidate===6){const size=Number(variant.replace('order',''));ok(http.del(`${base}/api/v1/carts`,null,p));for(const id of productIds.slice(0,size))ok(http.post(`${base}/api/v1/carts/items`,JSON.stringify({productId:id,quantity:1,grindType:'WHOLE_BEAN'}),p));const order=ok(http.post(`${base}/api/v1/orders`,JSON.stringify({addressId:actor.addressId,usedMileage:0}),p),201);check(order,{'order lines':x=>x.orderItems.length===size});}
  else if(candidate===8){const i=exec.scenario.iterationInTest%6;const routes=[`/api/v1/products?size=10`,`/api/v1/products?size=10&keyword=${encodeURIComponent('에티오피아')}`,`/api/v1/products/${productIds[0]}/reviews?size=10`,`/api/v1/products/${productIds[0]}/qnas?size=10`,`/api/v1/admin/orders?size=50`,`/api/v1/admin/statistics/sales?unit=DAILY&from=2026-06-01&to=2026-06-30`];ok(http.get(base+routes[i],i>=4?params(data.admin):p));}
  else fail('Scheduler is a server diagnostic, not an HTTP workload');
  duration.add(Date.now()-start,{candidate:String(candidate),variant});
  completed.add(1);
}
export function teardown(data){
  const r=http.get(`${base}/api/v1/admin/performance/manifest`,params(data.admin,'control'));
  if(r.status!==200)fail('App lifecycle probe failed');
  const m=r.json();
  if(m.instanceId!==__ENV.PERF_INSTANCE_ID||Date.parse(m.processStartedAt)!==Date.parse(__ENV.PERF_PROCESS_STARTED_AT))fail('App restarted during HTTP stage');
}
export function handleSummary(data){const {metrics,root_group,state}=data;return{[__ENV.PERF_SUMMARY_FILE]:JSON.stringify({protocolVersion:2,stage,candidate,variant,sourceHash:__ENV.PERF_SOURCE_HASH,measurementHash:__ENV.PERF_MEASUREMENT_HASH,instanceId:__ENV.PERF_INSTANCE_ID,processStartedAt:__ENV.PERF_PROCESS_STARTED_AT,metrics,root_group,state},null,2)};}
