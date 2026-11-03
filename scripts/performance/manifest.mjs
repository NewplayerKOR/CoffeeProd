import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const inputs=['build.gradle.kts','settings.gradle.kts','gradlew','gradlew.bat','.dockerignore','gradle','src/main','src/test/performance','scripts/performance','scripts/seed/catalog','scripts/seed/commerce/sql','k6/phase2'];
function walk(p){const absolute=path.join(root,p);if(!fs.existsSync(absolute))return [];
  if(fs.lstatSync(absolute).isSymbolicLink())throw new Error('Symlink input refused');
  return fs.statSync(absolute).isDirectory()?fs.readdirSync(absolute).filter(n=>!n.startsWith('.')).flatMap(n=>walk(`${p}/${n}`)):[p];}
export function identity(){const files=[...new Set(inputs.flatMap(walk))].sort();
  const records=Object.fromEntries(files.map(p=>[p,hash(fs.readFileSync(path.join(root,p)))]));
  const canonical=rows=>rows.map(p=>`${p}\0${records[p]}\n`).join('');
  const measurementFiles=files.filter(p=>!p.startsWith('src/main/')&&!p.startsWith('scripts/seed/catalog/')&&!p.startsWith('scripts/seed/commerce/sql/'));
  let revision;try{revision=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8',stdio:['ignore','pipe','ignore']}).trim();}catch{revision=JSON.parse(fs.readFileSync(path.join(root,'scripts/performance/.manifest.json'),'utf8')).baseRevision;}
  return {format:1,baseRevision:revision,sourceHash:hash(canonical(files)),measurementHash:hash(canonical(measurementFiles)),fixtureHash:hash(canonical(files.filter(p=>p.startsWith('scripts/seed/')||p.startsWith('scripts/performance/')&&p.endsWith('.sql')))),records};}
export function verify(manifest){for(const [p,h]of Object.entries(manifest.records)){if(p.includes('..')||path.isAbsolute(p)||!fs.existsSync(path.join(root,p))||hash(fs.readFileSync(path.join(root,p)))!==h)throw new Error(`Source hash mismatch: ${p}`);}const current=identity();if(current.sourceHash!==manifest.sourceHash||current.measurementHash!==manifest.measurementHash||current.fixtureHash!==manifest.fixtureHash)throw new Error('Manifest identity or source file set changed');return true;}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const mode=process.argv[2]||'write';const target=path.join(root,'scripts/performance/.manifest.json');
 if(mode==='verify'){verify(JSON.parse(fs.readFileSync(target,'utf8')));console.log('Source hashes verified');}
 else if(mode==='write'||mode==='bundle'){
  const m=identity();fs.writeFileSync(target,JSON.stringify(m,null,2)+'\n');fs.writeFileSync(path.join(root,'scripts/performance/.source-sha256.txt'),Object.entries(m.records).map(([p,h])=>`${h}  ${p}`).join('\n')+'\n');
  if(mode==='bundle'){const output=path.resolve(process.argv[3]||path.join(root,'build/perf-phase2/source.tar.gz'));if(!output.startsWith(path.join(root,'build/perf-phase2')+path.sep)||fs.existsSync(output))throw new Error('Use a new archive below build/perf-phase2');fs.mkdirSync(path.dirname(output),{recursive:true});
   const list=output+'.files';fs.writeFileSync(list,[...Object.keys(m.records),'scripts/performance/.gitignore','scripts/performance/.manifest.json','scripts/performance/.source-sha256.txt'].join('\n')+'\n');
   execFileSync('tar',['-czf',output,'-T',list],{cwd:root,stdio:'inherit'});console.log(output);
  }console.log(JSON.stringify({sourceHash:m.sourceHash,measurementHash:m.measurementHash,fixtureHash:m.fixtureHash}));
 }else throw new Error('Expected write, verify or bundle');
}
