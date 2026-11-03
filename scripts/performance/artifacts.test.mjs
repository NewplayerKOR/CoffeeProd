import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const shell=process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'/bin/sh';
const posix=p=>p.replaceAll('\\','/').replace(/^([A-Za-z]):/,(_,drive)=>`/${drive.toLowerCase()}`);
function fixture(){
  fs.mkdirSync(path.join(root,'build/dev-task8'),{recursive:true});
  const dir=fs.mkdtempSync(path.join(root,'build/dev-task8/artifact-test-'));
  const xml=path.join(dir,'junit'),html=path.join(dir,'html'),out=path.join(dir,'export with spaces');
  fs.mkdirSync(path.join(xml,'binary'),{recursive:true});fs.mkdirSync(path.join(html,'css'),{recursive:true});
  fs.writeFileSync(path.join(xml,'TEST-fixture.xml'),'<testsuite tests="1" failures="0" errors="0" skipped="0"/>',{mode:0o600});
  fs.writeFileSync(path.join(xml,'binary/results-generic.bin'),'private Gradle intermediate',{mode:0o600});
  fs.writeFileSync(path.join(html,'index.html'),'<html>report</html>',{mode:0o600});
  fs.writeFileSync(path.join(html,'css/style.css'),'body {}',{mode:0o600});
  fs.chmodSync(path.join(html,'css'),0o700);
  return {dir,xml,html,out,run:()=>spawnSync(shell,[posix(path.join(root,'scripts/performance/export-reports.sh')),...[xml,html,out].map(posix)],{cwd:root,encoding:'utf8'})};
}
test('exports XML and HTML without the private Gradle binary; all report bytes are preserved',()=>{
  const f=fixture(),r=f.run();assert.equal(r.status,0,r.stderr);
  assert.equal(fs.existsSync(path.join(f.out,'xml/binary')),false);
  for(const [source,destination] of [[path.join(f.xml,'TEST-fixture.xml'),path.join(f.out,'xml/TEST-fixture.xml')],[path.join(f.html,'index.html'),path.join(f.out,'html/index.html')],[path.join(f.html,'css/style.css'),path.join(f.out,'html/css/style.css')]]){
    assert.deepEqual(fs.readFileSync(destination),fs.readFileSync(source));
    if(process.platform!=='win32')assert.equal(fs.statSync(destination).mode&0o777,0o644);
  }
  if(process.platform!=='win32'){
    assert.equal(fs.statSync(path.join(f.out,'html/css')).mode&0o777,0o755);
    assert.equal(fs.statSync(path.join(f.xml,'binary/results-generic.bin')).mode&0o777,0o600);
  }
});
test('missing XML fails while preserving available HTML evidence',()=>{const f=fixture();fs.unlinkSync(path.join(f.xml,'TEST-fixture.xml'));const r=f.run();assert.equal(r.status,2);assert.match(r.stderr,/JUnit XML report missing/);assert.ok(fs.existsSync(path.join(f.out,'html/index.html')));});
test('missing HTML fails while preserving available JUnit evidence',()=>{const f=fixture();fs.unlinkSync(path.join(f.html,'index.html'));const r=f.run();assert.equal(r.status,2);assert.match(r.stderr,/HTML report missing/);assert.ok(fs.existsSync(path.join(f.out,'xml/TEST-fixture.xml')));});
test('existing evidence is rejected without overwriting reports',()=>{const f=fixture();assert.equal(f.run().status,0);fs.writeFileSync(path.join(f.xml,'TEST-fixture.xml'),'replacement');assert.equal(f.run().status,2);assert.notEqual(fs.readFileSync(path.join(f.out,'xml/TEST-fixture.xml'),'utf8'),'replacement');});
function entry(){
  const f=fixture();
  fs.writeFileSync(path.join(f.dir,'gradlew'),`#!/bin/sh
printf 'invoked\\n' >> gradle-invocations
if [ "\${FAKE_REPORTS:-all}" = all ]; then
  mkdir -p build/test-results/perfTest/binary build/reports/tests/perfTest
  printf '<testsuite tests="1" failures="0" errors="0" skipped="0"/>' > build/test-results/perfTest/TEST-fixture.xml
  printf private > build/test-results/perfTest/binary/results-generic.bin
  chmod 0600 build/test-results/perfTest/binary/results-generic.bin
  printf '<html>report</html>' > build/reports/tests/perfTest/index.html
fi
echo 'Gradle fixture console'
exit "\${FAKE_GRADLE_EXIT:-0}"
`,{mode:0o755});
  const env={...process.env,PERF_RESULTS:posix(path.join(f.dir,'results')),PERF_RUN_ID:'run',PERF_PHASE:'before',PERF_SAMPLE:'diag-c1-catalog-r1',PERF_CANDIDATE:'1',PERF_VARIANT:'catalog',FAKE_GRADLE_EXIT:'0',FAKE_REPORTS:'all'};
  const target=path.join(f.dir,'results/run/before/diag-c1-catalog-r1/diagnostics/case1-catalog');
  return {...f,env,target,run:()=>spawnSync(shell,[posix(path.join(root,'scripts/performance/checks.sh'))],{cwd:f.dir,env,encoding:'utf8'})};
}
test('checks entrypoint exports reports readable by the SSH user without exporting binary files',()=>{const f=entry(),r=f.run();assert.equal(r.status,0,r.stderr);assert.ok(fs.existsSync(path.join(f.target,'xml/TEST-fixture.xml')));assert.equal(fs.existsSync(path.join(f.target,'xml/binary')),false);assert.match(fs.readFileSync(path.join(f.target,'console.log'),'utf8'),/Gradle fixture console/);});
test('Gradle failure remains the primary exit when reports are also missing',()=>{const f=entry();f.env.FAKE_GRADLE_EXIT='7';f.env.FAKE_REPORTS='none';const r=f.run();assert.equal(r.status,7);assert.match(r.stderr,/report missing/);assert.ok(fs.existsSync(path.join(f.target,'console.log')));});
test('successful Gradle without required reports fails rather than claiming success',()=>{const f=entry();f.env.FAKE_REPORTS='none';assert.equal(f.run().status,2);});
test('duplicate sample stops before Gradle runs again',()=>{const f=entry();assert.equal(f.run().status,0);assert.notEqual(f.run().status,0);assert.equal(fs.readFileSync(path.join(f.dir,'gradle-invocations'),'utf8'),'invoked\n');});
test('path traversal is rejected before creating result files or running Gradle',()=>{const f=entry();f.env.PERF_SAMPLE='../other';assert.equal(f.run().status,2);assert.equal(fs.existsSync(path.join(f.dir,'gradle-invocations')),false);});
