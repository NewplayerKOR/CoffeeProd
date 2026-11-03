export const matrix=[
  {candidate:1,variant:'catalog',kind:'recommendation'},
  {candidate:1,variant:'recommendation1000',kind:'recommendation'},
  {candidate:2,variant:'catalog',kind:'cart'},
  ...[1,30,366].map(days=>({candidate:3,variant:`days${days}`,kind:'statistics',days})),
  {candidate:4,variant:'catalog',kind:'scheduler'},
  ...[10,50].map(size=>({candidate:5,variant:`page${size}`,kind:'orders',size})),
  ...[1,5,20].map(size=>({candidate:6,variant:`order${size}`,kind:'write',size})),
  {candidate:7,variant:'catalog',kind:'jwt'},
  {candidate:8,variant:'catalog',kind:'search'},
];
export const protocolVersion=2;
export const repetitions=3;
export function expectedSamples(candidates=[1,2,3,4,5,6,7,8],mode='All'){
  if(!['All','Diagnostics','Http'].includes(mode))throw new Error('Unknown execution mode');
  return matrix.filter(row=>candidates.includes(row.candidate)).flatMap(({candidate:c,variant:v})=>[
    ...(mode==='Http'?[]:[1,2,3].map(r=>({sample:`diag-c${c}-${v}-r${r}`,kind:'diagnostic',candidate:c,variant:v}))),
    ...(mode==='Diagnostics'||c===4?[]:[1,2,3].map(r=>({sample:`http-c${c}-${v}-r${r}`,kind:'measurement',candidate:c,variant:v}))),
  ]);
}
export function scenarioFor(candidate,stage){
  if(![1,2,3,5,6,7,8].includes(candidate)||!['warmup','measurement'].includes(stage))throw new Error('Invalid HTTP stage');
  if(candidate===3)return stage==='warmup'
    ?{executor:'constant-vus',vus:1,duration:'30s',gracefulStop:'10m'}
    :{executor:'shared-iterations',vus:1,iterations:1,maxDuration:'10m',gracefulStop:'10m'};
  return {executor:'constant-arrival-rate',rate:candidate===6?1:10,timeUnit:'1s',duration:stage==='warmup'?'30s':'2m',preAllocatedVUs:20,maxVUs:20,gracefulStop:'10m'};
}
export function validateRunId(id){if(!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(id||''))throw new Error('Unsafe run ID');return id;}
export function validateTarget(url){
  const m=/^http:\/\/(\d+)\.(\d+)\.(\d+)\.(\d+):18080$/.exec(url||'');
  if(!m)throw new Error('Use the dedicated private-LAN HTTP port 18080');const a=m.slice(1).map(Number);
  if(a.some(x=>x>255)||!(a[0]===10||a[0]===192&&a[1]===168||a[0]===172&&a[1]>=16&&a[1]<=31||a.join('.')==='127.0.0.1'))throw new Error('Public/operational target refused');return url;
}
export function selectVariant(name){const row=matrix.find(r=>r.variant===name&&(name!=='catalog'||r.candidate===Number(globalThis.__ENV?.PERF_CANDIDATE||1)));if(!row)throw new Error('Unknown measurement variant');return row;}
