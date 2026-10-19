import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const require = createRequire(import.meta.url);
const argument = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : fallback;
};
const { createCanvas, GlobalFonts } = require(argument('--canvas-module', '@napi-rs/canvas'));
const fontDirectory = argument('--font-dir', 'C:/Windows/Fonts');
if (!GlobalFonts.registerFromPath(join(fontDirectory, 'malgun.ttf'), 'ReportSans')) {
  throw new Error('A Korean font is required. Supply --font-dir with malgun.ttf.');
}
if (existsSync(join(fontDirectory, 'malgunbd.ttf'))) GlobalFonts.registerFromPath(join(fontDirectory, 'malgunbd.ttf'), 'ReportSans');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const data = JSON.parse(readFileSync(resolve(root, 'data/metrics.json'), 'utf8'));
const destination = resolve(root, 'figures');
mkdirSync(destination, { recursive: true });
const color = { ink: '#202A30', muted: '#55656E', grid: '#DDE3E5', before: '#79848A',
  after: '#138378', supplement: '#C39222', public: '#386CB0', white: '#FFFFFF' };
const images = [];
let canvas, ctx;
function start(height) {
  canvas = createCanvas(1440, height);
  ctx = canvas.getContext('2d');
  ctx.fillStyle = color.white;
  ctx.fillRect(0, 0, canvas.width, canvas.height);
}
function text(value, x, y, size = 20, fill = color.ink, bold = false, align = 'left') {
  ctx.font = `${bold ? '700' : '400'} ${size}px ReportSans`;
  ctx.textAlign = align;
  ctx.textBaseline = 'middle';
  ctx.fillStyle = fill;
  const width = ctx.measureText(String(value)).width;
  const left = align === 'center' ? x - width / 2 : align === 'right' ? x - width : x;
  if (left < 5 || left + width > canvas.width - 5 || y < size / 2 || y + size / 2 > canvas.height) {
    throw new Error(`Text exceeds image bounds: ${value}`);
  }
  ctx.fillText(String(value), x, y);
}
function line(x1, y1, x2, y2, stroke = color.grid, width = 1.5, dashed = false) {
  ctx.beginPath(); ctx.strokeStyle = stroke; ctx.lineWidth = width;
  ctx.setLineDash(dashed ? [8, 6] : []); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); ctx.setLineDash([]);
}
function rect(x, y, width, height, fill) { ctx.fillStyle = fill; ctx.fillRect(x, y, width, height); }
function circle(x, y, fill, open = false) {
  ctx.beginPath(); ctx.arc(x, y, 7, 0, Math.PI * 2);
  ctx.fillStyle = open ? color.white : fill; ctx.fill(); ctx.strokeStyle = fill; ctx.lineWidth = 3; ctx.stroke();
}
function yAxis(left, top, width, height, maximum, step) {
  for (let value = 0; value <= maximum; value += step) {
    const y = top + height - value / maximum * height;
    line(left, y, left + width, y);
    text(value, left - 14, y, 18, color.muted, false, 'right');
  }
  line(left, top, left, top + height, color.ink);
  line(left, top + height, left + width, top + height, color.ink);
  text('ms', left, top - 20, 18, color.muted);
}
function finish(name, description) {
  const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
  let nonWhiteSamples = 0;
  for (let index = 0; index < pixels.length; index += 4 * 41) {
    if (pixels[index] < 240 || pixels[index + 1] < 240 || pixels[index + 2] < 240) nonWhiteSamples++;
  }
  if (nonWhiteSamples < 200) throw new Error('Blank or nearly blank chart.');
  const body = canvas.toBuffer('image/png');
  writeFileSync(resolve(destination, name), body);
  images.push({ file: `figures/${name}`, width: canvas.width, height: canvas.height,
    bytes: body.length, sha256: createHash('sha256').update(body).digest('hex'), non_white_samples: nonWhiteSamples,
    description, renderer_bounds_check: 'PASS' });
}

start(690);
text('주문 목록: 실행별 p95와 p99', 70, 45, 32, color.ink, true);
text('B: 적용 전 / A: 적용 후. A3*는 별도 재예열 후 보충 실행입니다.', 70, 94, 20, color.muted);
const orderRows = [...data.mixed.before, ...data.mixed.after];
for (const [panel, metric] of ['order_p95_ms', 'order_p99_ms'].entries()) {
  const left = panel === 0 ? 85 : 795, width = 565, top = 190, height = 345;
  const m = data.medians[metric];
  text(`${panel === 0 ? 'p95' : 'p99'} 중앙값 ${m.before.toFixed(2)} → ${m.after.toFixed(2)}ms`, left, 144, 23, color.ink, true);
  yAxis(left, top, width, height, 360, 60);
  const positions = [0, 1, 2, 4, 5, 6];
  const x = (position) => left + (position + 0.5) / 7 * width;
  orderRows.forEach((row, index) => {
    const value = row[metric], center = x(positions[index]), h = value / 360 * height;
    const fill = row.supplemental ? color.supplement : index < 3 ? color.before : color.after;
    rect(center - 24, top + height - h, 48, h, fill);
    text(value.toFixed(1), center, top + height - h - 14, 17, color.ink, true, 'center');
    text(row.display_id + (row.supplemental ? '*' : ''), center, 559, 19, color.ink, true, 'center');
    text(`n=${row.order_samples}`, center, 585, 16, color.muted, false, 'center');
  });
  line(x(0) - 35, top + height - m.before / 360 * height, x(2) + 35, top + height - m.before / 360 * height, color.ink, 2, true);
  line(x(4) - 35, top + height - m.after / 360 * height, x(6) + 35, top + height - m.after / 360 * height, color.ink, 2, true);
}
text('점선은 실행별 분위수의 중앙값입니다. 모든 요청을 합친 분위수가 아닙니다.', 70, 631, 19, color.muted);
text('p95 감소는 관측됐지만, A3의 p99 303.79ms 때문에 안정적인 꼬리 지연 개선은 주장하지 않습니다.', 70, 663, 19, color.muted);
finish('01_order_latency.png', 'Six independent order endpoint repeats, with supplemental repeat and median definitions visible');

start(550);
text('SQL 실행계획: 전체 탐색에서 인덱스 탐색으로', 70, 45, 32, color.ink, true);
text('실행 시간의 비교이며 API p95가 아닙니다. 점의 가로 위치는 로그 눈금입니다.', 70, 91, 20, color.muted);
circle(980, 132, color.before, true); text('적용 전 1회', 998, 132, 19);
circle(1190, 132, color.after); text('적용 후 중앙값', 1208, 132, 19);
const sqlLeft = 405, sqlWidth = 900, low = 0.05, high = 100;
const sqlX = (value) => sqlLeft + Math.log(value / low) / Math.log(high / low) * sqlWidth;
for (const tick of [0.05, 0.1, 0.5, 1, 5, 10, 50, 100]) {
  line(sqlX(tick), 175, sqlX(tick), 411);
  text(tick, sqlX(tick), 442, 18, color.muted, false, 'center');
}
data.sql.queries.forEach((row, index) => {
  const y = 205 + index * 82, before = sqlX(row.before_execution_ms), after = sqlX(row.after_execution_median_ms);
  text(row.name, 70, y, 22, color.ink, true);
  line(after, y, before, y, color.before, 2);
  circle(before, y, color.before, true); circle(after, y, color.after);
  text(`${row.before_execution_ms.toFixed(3)}ms`, before - 12, y - 23, 19, color.ink, false, 'right');
  text(`${row.after_execution_median_ms.toFixed(3)}ms`, after + 12, y + 24, 19, color.after, true);
});
line(sqlLeft, 411, sqlLeft + sqlWidth, 411, color.ink);
text('진단 SQL 시간(ms, 로그 눈금)', 852, 477, 19, color.muted, false, 'center');
text('Before 1회 / After 3회. 캐시·예열 차이와 축약 진단 SQL의 한계 때문에 순수 인덱스 개선 배율로 표현하지 않습니다.', 70, 518, 18, color.muted);
finish('02_sql_access_paths.png', 'SQL execution time on an explicitly logarithmic dot axis, not zero-based bars or API percentiles');

start(580);
text('전체 혼합 요청: 지연 감소를 확인하지 못했습니다', 70, 45, 32, color.ink, true);
text('주문 목록의 개선을 전체 API 개선으로 확대하지 않기 위한 비교입니다.', 70, 92, 20, color.muted);
const overallLeft = 260, overallWidth = 1000, maximum = 360;
for (const tick of [0, 60, 120, 180, 240, 300, 360]) {
  const x = overallLeft + tick / maximum * overallWidth;
  line(x, 160, x, 414); text(tick, x, 446, 18, color.muted, false, 'center');
}
for (const [index, key] of ['overall_p95_ms', 'overall_p99_ms'].entries()) {
  const row = data.medians[key], y = 215 + index * 135;
  text(index === 0 ? 'p95' : 'p99', 70, y, 25, color.ink, true);
  text('적용 전', 235, y - 24, 19, color.muted, false, 'right');
  text('적용 후', 235, y + 24, 19, color.muted, false, 'right');
  rect(overallLeft, y - 40, row.before / maximum * overallWidth, 32, color.before);
  rect(overallLeft, y + 8, row.after / maximum * overallWidth, 32, color.after);
  text(row.before.toFixed(2), overallLeft + row.before / maximum * overallWidth + 12, y - 24, 20);
  text(`${row.after.toFixed(2)} (+${(-row.reduction_percent).toFixed(1)}%)`, overallLeft + row.after / maximum * overallWidth + 12, y + 24, 20);
}
line(overallLeft, 160, overallLeft, 414, color.ink);
line(overallLeft, 414, overallLeft + overallWidth, 414, color.ink);
text('응답 시간(ms). 3회 실행별 분위수의 중앙값이며 전체 요청 통합 분위수가 아닙니다.', 70, 501, 19, color.muted);
text('시간대·네트워크·캐시·OS 이력이 달라, 이 증가를 인덱스의 악영향으로 확정하지 않습니다.', 70, 544, 19, color.muted);
finish('03_overall_tradeoffs.png', 'Zero-based before/after mixed workload bars show that overall latency did not improve');

start(730);
text('전달 경로 비교: 같은 계정의 같은 주문 페이지', 70, 45, 32, color.ink, true);
text('주문 단독 2 iteration/s, 경로별 2분 × 3쌍. 본 주문 1,443건, 실패·누락 0건.', 70, 92, 20, color.muted);
rect(1050, 112, 18, 18, color.public); text('공개 HTTPS', 1080, 121, 19);
rect(1240, 112, 18, 18, color.after); text('SSH 원본', 1270, 121, 19);
for (const [panel, metric] of ['p95_ms', 'p99_ms'].entries()) {
  const left = panel === 0 ? 85 : 795, width = 565, top = 215, height = 310;
  const pub = data.path_comparison.medians.public[metric], origin = data.path_comparison.medians.origin[metric];
  text(`${panel === 0 ? 'p95' : 'p99'} 중앙값 ${pub.toFixed(2)} / ${origin.toFixed(2)}ms`, left, 166, 23, color.ink, true);
  yAxis(left, top, width, height, 220, 40);
  for (let pair = 1; pair <= 3; pair++) {
    const center = left + (pair - 0.5) / 3 * width;
    const rows = ['public', 'origin'].map((route) => data.path_comparison.records.find((row) => row.pair === pair && row.route === route));
    rows.forEach((row, index) => {
      const x = center + (index === 0 ? -34 : 34), value = row[metric], h = value / 220 * height;
      rect(x - 25, top + height - h, 50, h, index === 0 ? color.public : color.after);
      text(value.toFixed(1), x, top + height - h - 14, 17, color.ink, true, 'center');
    });
    text(`${pair}쌍`, center, 552, 20, color.ink, true, 'center');
    text(pair === 2 ? '원본 → 공개' : '공개 → 원본', center, 580, 18, color.muted, false, 'center');
    text(`${rows[0].order_samples} / ${rows[1].order_samples}건`, center, 608, 17, color.muted, false, 'center');
  }
}
text('공개 HTTP2/TLS와 원본 HTTP1.1/SSH의 차이를 포함합니다. 순수 Cloudflare 비용이나 코드 개선율이 아닙니다.', 70, 660, 18, color.muted);
text('양쪽 서버 주문 rolling p99 표본은 약 8~23ms였습니다. 과거 혼합 부하의 큰 tail 원인은 아직 미확정입니다.', 70, 695, 18, color.muted);
finish('04_public_origin_paths.png', 'Zero-based paired path comparison with route order, actual counts and protocol limitations visible');

writeFileSync(resolve(root, 'data/figure-manifest.json'), JSON.stringify({
  generated_at_utc: new Date().toISOString(), source_sha256: createHash('sha256').update(readFileSync(resolve(root, 'data/metrics.json'))).digest('hex'),
  images, visual_inspection: 'Pending human/model image inspection; geometry/pixel checks passed',
}, null, 2) + '\n');
console.log(JSON.stringify({ result: 'PASS', figures: images.map(({ file, bytes }) => ({ file, bytes })) }, null, 2));
