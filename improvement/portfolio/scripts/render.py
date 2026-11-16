"""기존 증빙으로 한국어 PNG 10장과 설명 문서를 만든다. 서버·부하 실행 없음."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import warnings
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".cache/matplotlib"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.font_manager import FontProperties
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Rectangle
from PIL import Image

from build_data import build, digest

W, H, DPI = 2400, 1800, 200
C = dict(bg="#FFFFFF", ink="#202D3C", muted="#536273", grid="#DDE3EA", faint="#F3F6F9",
         before="#7E8A96", after="#007E87", amber="#A36518", amber_bg="#FFF5E7")
FONT = None
BOLD = None


def fmt(value, digits=2):
    return f"{value:,.{digits}f}" if digits else f"{value:,.0f}"


class Page:
    def __init__(self, name):
        self.name = name
        self.fig = plt.figure(figsize=(W/DPI, H/DPI), dpi=DPI, facecolor=C["bg"])
        self.ax = self.fig.add_axes([0, 0, 1, 1], xlim=(0, W), ylim=(H, 0))
        self.ax.axis("off")
        self.texts = []
        self.body_layout = None

    def expand_body(self, top, bottom, target_bottom):
        """글꼴과 가로축은 유지하고 삭제한 하단 공간을 세로 간격에 배분한다."""
        self.body_layout = (top, (target_bottom-top)/(bottom-top))

    def map_y(self, y):
        if self.body_layout is None:
            return y
        top, scale = self.body_layout
        return top + (y-top)*scale

    def measure(self, value, size, bold=False):
        prop = (BOLD if bold else FONT).copy()
        prop.set_size(size * 72 / DPI)
        return self.fig.canvas.get_renderer().get_text_width_height_descent(value, prop, False)[0]

    def wrap(self, value, width, size, bold=False):
        result = []
        for paragraph in value.split("\n"):
            line = ""
            for word in paragraph.split(" "):
                candidate = (line + " " + word).strip()
                if line and self.measure(candidate, size, bold) > width:
                    result.append(line)
                    line = word
                else:
                    line = candidate
                while self.measure(line, size, bold) > width:
                    split = len(line) - 1
                    while split > 1 and self.measure(line[:split], size, bold) > width:
                        split -= 1
                    result.append(line[:split])
                    line = line[split:]
            result.append(line)
        return "\n".join(result)

    def text(self, x, y, value, size=36, color=None, bold=False, width=None, align="left", lineheight=1.38):
        value = str(value)
        if width:
            value = self.wrap(value, width, size, bold)
        prop = (BOLD if bold else FONT).copy()
        prop.set_size(size*72/DPI)
        obj = self.ax.text(x, self.map_y(y), value, fontproperties=prop,
                           color=color or C["ink"], va="top", ha=align, linespacing=lineheight,
                           zorder=8)
        self.texts.append(obj)
        return len(value.split("\n")) * size * lineheight

    def line(self, x1, y1, x2, y2, color=None, lw=1.5, **kwargs):
        self.ax.plot([x1, x2], [self.map_y(y1), self.map_y(y2)], color=color or C["grid"], linewidth=lw*72/DPI,
                     zorder=1, **kwargs)

    def rect(self, x, y, w, h, color=None, edge=None, radius=0):
        y, h = self.map_y(y), h * (self.body_layout[1] if self.body_layout else 1)
        if radius:
            patch = FancyBboxPatch((x, y), w, h, boxstyle=f"round,pad=0,rounding_size={radius}",
                                   facecolor=color or C["faint"], edgecolor=edge or "none", linewidth=.6)
        else:
            patch = Rectangle((x, y), w, h, facecolor=color or C["faint"], edgecolor=edge or "none", linewidth=.6)
        self.ax.add_patch(patch)

    def arrow(self, x1, y1, x2, y2, color=None):
        self.ax.add_patch(FancyArrowPatch((x1, self.map_y(y1)), (x2, self.map_y(y2)), arrowstyle="-|>",
                         mutation_scale=13, linewidth=1.4, color=color or C["after"]))

    def save(self):
        with warnings.catch_warnings(record=True) as captured:
            warnings.simplefilter("always")
            self.fig.canvas.draw()
            missing = [str(x.message) for x in captured if "Glyph" in str(x.message)]
        if missing:
            raise ValueError("폰트 누락: " + "; ".join(missing))
        renderer = self.fig.canvas.get_renderer()
        bounds = []
        for obj in self.texts:
            box = obj.get_window_extent(renderer)
            if box.x0 < 36 or box.y0 < 18 or box.x1 > W-36 or box.y1 > H-18:
                raise ValueError(f"이미지 밖 텍스트: {self.name}: {obj.get_text()}")
            bounds.append(dict(text=obj.get_text(), x=round(box.x0, 2), y=round(H-box.y1, 2),
                               width=round(box.width, 2), height=round(box.height, 2)))
        overlaps = []
        for i, a in enumerate(bounds):
            for b in bounds[i+1:]:
                dx = min(a["x"]+a["width"], b["x"]+b["width"]) - max(a["x"], b["x"])
                dy = min(a["y"]+a["height"], b["y"]+b["height"]) - max(a["y"], b["y"])
                if dx > 3 and dy > 3:
                    overlaps.append([a["text"], b["text"]])
        if overlaps:
            raise ValueError(f"텍스트 겹침: {self.name}: {overlaps[:4]}")
        out = ROOT / "figures"
        out.mkdir(exist_ok=True)
        path = out / (self.name + ".png")
        self.fig.savefig(path, dpi=DPI, facecolor=C["bg"], metadata={"Software": "CoffeeProd portfolio / Matplotlib", "Description": "Historical evidence; no new load tests"})
        plt.close(self.fig)
        image = Image.open(path).convert("RGB")
        preview = ROOT / "previews" / path.name
        preview.parent.mkdir(exist_ok=True)
        image.resize((960, 720), Image.Resampling.LANCZOS).save(preview)
        return dict(file="figures/"+path.name, preview="previews/"+path.name, width=W, height=H,
                    sha256=digest(path), previewSha256=digest(preview), textBounds=bounds,
                    textOverlaps=overlaps, footerDisplayed=False)


def row(group, index=0):
    return group[index]["beforeMedian"], group[index]["afterMedian"]


def hero(case):
    m, ident = case["metrics"], case["id"]
    if ident == "01-order-index":
        return "27.0% 감소", f"{m['before']:.2f} → {m['after']:.2f} ms"
    if ident == "02-recommendation":
        a, b = row(m["http"])
        return "32.0% 감소", f"{a:g} → {b:g} ms · 192개 카탈로그"
    if ident == "03-cart":
        return "66.7% 감소", "60 → 20회 · 기존 장바구니 조회"
    if ident == "04-statistics":
        a, b = row(m["rows"])
        return "74.3% 감소", f"{a:,.0f} → {b:,.0f}회 · 366일"
    if ident == "05-schedulers":
        return "5/5 검증 통과", "독립 스케줄러 2개 · 성능 개선율 미산출"
    if ident == "06-order-summary":
        a, b = row(m["entities"], 1)
        return f"{a:,.0f} → {b:g}개", "목록 전용 조회로 엔티티 로딩 제거"
    if ident == "07-order-create":
        a, b = row(m["rows"], 2)
        return "15.4% 감소", f"{a:g} → {b:g}회 · 상품 20종"
    if ident == "08-jwt":
        a, b = row(m["rows"])
        return f"{a:g} → {b:g}회", "토큰별 서명 검증 횟수 50% 감소"
    return "리뷰 42.9% 감소", "문의 40.2% 감소 · SQL replay"


def scaffold(case, index):
    p = Page(case["id"])
    p.text(104, 64, "COFFEEPROD / PERFORMANCE ENGINEERING", 28, C["muted"], True)
    p.text(2296, 64, f"CASE {index:02d} / 09", 28, C["muted"], align="right")
    p.text(104, 126, case["title"], 74, bold=True)
    p.text(104, 230, case["metric"] + "  |  " + case["scope"], 34, C["muted"])
    p.rect(104, 310, 2192, 132, C["faint"], radius=12)
    p.rect(104, 310, 6, 132, C["after"])
    emphasis, value = hero(case)
    p.text(138, 339, emphasis, 65, C["after"], True)
    p.text(760, 349, value, 46, width=1485)
    p.expand_body(488, 1496, 1720)
    for y, (title, key) in zip([488, 735, 982, 1229], [("01  문제점", "problem"), ("02  코드 개선", "change"), ("03  검증 조건", "conditions"), ("04  결과", "result")]):
        p.text(104, y, title, 32, C["after"], True)
        height = p.text(104, y+52, case[key], 36, width=770, lineheight=1.35)
        if height > 195:
            raise ValueError(f"설명 영역 초과: {case['id']}/{key}: {height}")
    p.line(912, 488, 912, 1475)
    return p


def pair(p, title, before, after, y, maximum=None, unit="회", digits=0, after_samples=None):
    p.text(960, y, title, 37, bold=True)
    x0, span = 1110, 1110
    maximum = maximum or max(before, after, *(after_samples or [0])) * 1.28
    maximum = max(maximum, 1)
    axis_y = y+170
    for t in range(5):
        value = maximum * t / 4
        x = x0 + span * t / 4
        p.line(x, y+54, x, y+151, C["grid"])
        p.text(x, axis_y+8, fmt(value, 1 if unit == "ms" else 0), 27, C["muted"], align="center")
    p.line(x0, axis_y, x0+span, axis_y)
    for name, val, bar_y, color in [("적용 전", before, y+60, C["before"]), ("적용 후", after, y+112, C["amber"] if after > before else C["after"])]:
        p.text(960, bar_y+3, name, 30, C["muted"])
        width = span * val / maximum
        if width:
            p.rect(x0, bar_y, width, 36, color)
        else:
            p.line(x0, bar_y-2, x0, bar_y+40, color, 4)
        label_value = max([val] + (after_samples or [])) if name == "적용 후" else val
        p.text(x0+span*label_value/maximum+17, bar_y-1, fmt(val, digits)+" "+unit, 36, color, True)
        if name == "적용 후" and after_samples and max(after_samples) > min(after_samples):
            lo, hi = min(after_samples), max(after_samples)
            a, b = x0+span*lo/maximum, x0+span*hi/maximum
            p.line(a, bar_y+19, b, bar_y+19, C["ink"], 2)
            p.line(a, bar_y+9, a, bar_y+29, C["ink"], 2)
            p.line(b, bar_y+9, b, bar_y+29, C["ink"], 2)


def callout(p, y, title, message, warning=False):
    p.rect(960, y, 1336, 145, C["amber_bg"] if warning else C["faint"], radius=12)
    p.text(990, y+22, title, 33, C["amber"] if warning else C["after"], True)
    p.text(990, y+73, message, 34, width=1275)


def repeat_plot(p, m):
    p.text(960, 888, "6회 측정 · p95·p99(ms)", 38, bold=True)
    x0, x1, y0, y1 = 1085, 2205, 1000, 1335
    for value in (0, 100, 200, 300):
        y = y1-(y1-y0)*value/350
        p.line(x0, y, x1, y)
        p.text(x0-25, y-17, str(value), 28, C["muted"], align="right")
    p.line(x0, y0, x0, y1)
    records = m["runs"]["before"] + m["runs"]["after"]
    for i, r in enumerate(records):
        x = x0 + 72 + i*195
        color = C["before"] if i < 3 else C["after"]
        for key, marker in [("order_p95_ms", "o"), ("order_p99_ms", "s")]:
            y = y1-(y1-y0)*r[key]/350
            p.ax.plot(x, p.map_y(y), marker=marker, markersize=8, color=color,
                      markerfacecolor=color if marker == "o" else "white", markeredgewidth=1.6)
        p.text(x, y1+20, r["display_id"]+("*" if r["supplemental"] else ""), 31, color, True, align="center")
    p.text(1140, 950, "● p95     □ p99", 30, C["muted"])
    p.text(2080, 1007, "A3* p99\n303.79ms", 31, C["amber"], True, align="center")
    p.text(960, 1420, "A3*는 별도 세션의 보충 실행 · 전체 혼합 지연 개선은 미확인", 29, C["muted"])


def scheduler_diagram(p):
    p.text(960, 488, "스케줄러 구성", 41, bold=True)
    p.text(960, 560, "적용 전 · 공유 스케줄러", 37, C["before"], True)
    p.rect(960, 625, 1336, 270, C["faint"], radius=12)
    p.text(1000, 650, "하나의 실행 경로", 32, C["muted"])
    p.rect(1000, 717, 520, 108, "#E5EBF0", radius=10)
    p.text(1260, 752, "결제 복구 · PG 대기", 36, align="center")
    p.arrow(1545, 772, 1670, 772, C["before"])
    p.rect(1695, 717, 555, 108, C["amber_bg"], radius=10)
    p.text(1972, 752, "통계 작업도 대기 가능", 36, C["amber"], align="center")
    p.text(960, 968, "적용 후 · 독립 스케줄러 2개", 37, C["after"], True)
    for y, title, message in [(1040, "결제 복구 · 스레드 1개", "PG 응답 대기"), (1200, "통계 · 스레드 1개", "별도 실행 경로 유지")]:
        p.rect(960, y, 630, 114, C["faint"], radius=10)
        p.text(1275, y+37, title, 35, align="center")
        p.arrow(1610, y+58, 1720, y+58)
        p.text(1760, y+36, message, 35, C["after"], True)
    p.text(960, 1395, "검증: PG 차단 중 통계 진행 / 통계 차단 중 결제 복구", 31, C["muted"])
    p.text(960, 1448, "구성 3개 + 실제 예약 2개 통과 · 테스트 전용 50ms 조건", 31, C["muted"])


def draw_case(case, index):
    p = scaffold(case, index)
    m, ident = case["metrics"], case["id"]
    if ident == "01-order-index":
        p.text(960, 488, "주문 목록 지연", 41, bold=True)
        pair(p, "전후 각 3회 p95의 중앙값", m["before"], m["after"], 575, unit="ms", digits=2)
        repeat_plot(p, m)
    elif ident == "02-recommendation":
        p.text(960, 488, "추천 p95 · ms", 41, bold=True)
        for i, (r, title) in enumerate(zip(m["http"], ["192개 카탈로그 · 32.0% 감소", "후보 1,000개 · 42.9% 감소"])):
            pair(p, title, r["beforeMedian"], r["afterMedian"], 555+i*222, maximum=46, unit="ms", digits=0)
        p.text(960, 1010, "ORM 엔티티 로딩", 38, bold=True)
        for i, (r, title) in enumerate(zip(m["entities"], ["192개 카탈로그", "후보 1,000개"])):
            pair(p, title, r["beforeMedian"], r["afterMedian"], 1070+i*222, maximum=2450, unit="개")
    elif ident == "03-cart":
        p.text(960, 488, "조회·생성 SQL", 41, bold=True)
        for i, (r, title) in enumerate(zip(m["sql"], ["기존 장바구니 · 20개 동시 조회", "최초 생성 · 20개 동시 요청"])):
            pair(p, title, r["beforeMedian"], r["afterMedian"], 600+i*300, maximum=120, after_samples=r["after"])
        callout(p, 1240, "최초 생성 SQL 중앙값 25.0% 증가", "after 78 / 69 / 75회 · 생성의 잠금·재확인 비용", True)
        p.text(960, 1435, "단회 HTTP 참고: p95 10→7ms · 반복 안정성 판정에 사용하지 않음", 29, C["muted"])
    elif ident == "04-statistics":
        p.text(960, 488, "JDBC 실행 호출", 41, bold=True)
        a, b = row(m["rows"])
        pair(p, "366일 · 기존 통계 행 재집계", a, b, 615, maximum=2000)
        p.text(960, 980, "개선 후 구성 · 376회", 40, bold=True)
        for y, value, label in [(1065, "366회", "날짜 잠금 · 정합성 유지"), (1160, "10회", "범위 집계 1 + 통계 조회 1 + 갱신 배치 8")]:
            p.text(980, y, value, 57, C["after"], True)
            p.text(1220, y+12, label, 34)
        p.line(980, 1265, 2250, 1265)
        p.text(980, 1320, "1,464 → 376회", 61, C["after"], True)
        p.text(980, 1420, "전체 롤백·승인·환불 정합성은 별도 검증 근거와 연결", 31, C["muted"])
    elif ident == "05-schedulers":
        scheduler_diagram(p)
    elif ident == "06-order-summary":
        p.text(960, 488, "목록 조회 · 회원 페이지 50개", 41, bold=True)
        a, b = row(m["entities"], 1)
        pair(p, "ORM 엔티티 로딩", a, b, 620, maximum=1600, unit="개")
        p.text(960, 1000, "ORM 로딩 제거", 37, bold=True)
        for x, label, value in [(960, "SQL 실행", "4 → 3회"), (1660, "컬렉션 로딩", "100 → 0개")]:
            p.rect(x, 1110, 630, 230, C["faint"], radius=12)
            p.text(x+30, 1140, label, 35, C["muted"])
            p.text(x+30, 1220, value, 62, C["after"], True)
        p.text(960, 1420, "페이지·응답·소유권·대표 상품·결제 상태 검증 근거 보존", 30, C["muted"])
    elif ident == "07-order-create":
        p.text(960, 488, "상품별 SQL 수", 41, bold=True)
        for i, r in enumerate(m["rows"]):
            pair(p, f"상품 { [1,5,20][i] }종 주문", r["beforeMedian"], r["afterMedian"], 585+i*267, maximum=160)
        p.text(960, 1440, "조건부 재고 UPDATE 1 / 5 / 20회 유지 · SELECT만 축소", 31, C["muted"])
    elif ident == "08-jwt":
        p.text(960, 488, "서명 검증 횟수", 41, bold=True)
        for i, r in enumerate(m["rows"]):
            pair(p, ["인증 필터", "Refresh Token 재발급"][i], r["beforeMedian"], r["afterMedian"], 595+i*285, maximum=4)
        callout(p, 1220, "parser 재사용", "요청 중 2→0회 · 애플리케이션 초기화 시 1회 생성")
        p.text(960, 1440, "검증 횟수와 parser 생성 수를 CPU·HTTP 개선율과 구분", 31, C["muted"])
    else:
        p.text(960, 488, "SQL replay · 10개 목록", 41, bold=True)
        for i, r in enumerate(m["rows"][:2]):
            pair(p, ["상품 리뷰 · 42.9% 감소", "상품 문의 · 40.2% 감소"][i], r["beforeMedian"], r["afterMedian"], 610+i*300, maximum=4.5, unit="ms", digits=4)
        size = sum(int(x["bytes"]) for x in m["details"]["newIndexes"]) / 1024**2
        callout(p, 1240, "인덱스 2개 · 쓰기 비용", f"추가 크기 {size:.2f} MiB · 롤백 쓰기 악화 최대 1.07%")
        p.text(960, 1440, "다른 후보는 조건부 보류 · 최종 조합 72조건 비교", 31, C["muted"])
    return p.save()


def summary(data):
    p = Page("00-summary")
    p.text(104, 64, "COFFEEPROD / PERFORMANCE ENGINEERING", 28, C["muted"], True)
    p.text(104, 126, "개선 사항", 73, bold=True)
    p.text(104, 240, "9개 개선 사례", 38, C["muted"])
    p.expand_body(365, 1555, 1720)
    for i, case in enumerate(data["cases"]):
        col, r = i % 3, i // 3
        x, y = 104+col*748, 365+r*410
        p.rect(x, y, 704, 370, C["faint"], radius=12)
        p.text(x+28, y+25, f"{i+1:02d}  {case['summary']}", 39, bold=True)
        a, b = hero(case)
        if i == 8:
            a, b = "최대 42.9% 감소", "리뷰 42.9% · 문의 40.2%"
        elif i == 5:
            b = "엔티티 로딩 · SQL 4→3회"
        elif i == 4:
            b = "독립 스케줄러 2개"
        elif i == 7:
            b = "서명 검증 횟수 · CPU/지연과 구분"
        elif i == 1:
            b = "25→17ms · 후보 1,000개는 42.9% 감소"
        elif i == 2:
            b = "조회 SQL 60→20회 · 생성 비용 증가"
        elif i == 6:
            b = "SQL 130→110회 · 상품 20종"
        p.text(x+28, y+106, a, 62, C["after"], True)
        p.text(x+28, y+212, b, 35, width=650)
        p.text(x+28, y+307, case["category"], 29, C["muted"])
    return p.save()


def documents(data):
    lines = ["# CoffeeProd 성능 개선 사례 설명", "", "측정은 2026년 10월 7~8일 및 11월 3~4일에 수행했다. 이번 제작에서는 보관 증빙을 읽어 수치와 이미지만 재구성했다. 최신 JDK·프레임워크의 새 성능 측정이 아니다.", "",
             "HTTP p95는 실행별 분위수의 중앙값이며 모든 요청을 합친 p95가 아니다. SQL 수·JDBC 호출·엔티티 로딩·서명 검증 횟수는 서로 다른 지표다.", ""]
    for i, case in enumerate(data["cases"], 1):
        lines += [f"## {i:02d}. {case['title']}", "", f"![{case['title']}](figures/{case['id']}.png)", "",
                  f"- **문제점:** {case['problem']}", f"- **코드 개선:** {case['change']}", f"- **검증 조건:** {case['conditions']}", f"- **결과:** {case['result']}", ""]
        rows = []
        if case["id"] == "01-order-index":
            m = case["metrics"]
            rows = [("주문 목록 p95 중앙값", m["before"], m["after"], "ms")]
        else:
            for key in ("http", "entities", "sql", "rows"):
                value = case["metrics"].get(key)
                if isinstance(value, list):
                    rows += [(r["label"], r["beforeMedian"], r["afterMedian"], r["unit"]) for r in value if "beforeMedian" in r]
        if rows:
            lines += ["| 계측 항목 | 적용 전 | 적용 후 | 단위 |", "|---|---:|---:|---|"]
            for name, before, after, unit in rows:
                digits = 4 if unit == "ms" else 0
                lines.append(f"| {name} | {fmt(before, digits)} | {fmt(after, digits)} | {unit} |")
            lines.append("")
        if case["id"] == "01-order-index":
            lines += ["| 실행 | 주문 p95(ms) | 주문 p99(ms) | 주문 표본 |", "|---|---:|---:|---:|"]
            for phase in ("before", "after"):
                for r in case["metrics"]["runs"][phase]:
                    lines.append(f"| {r['display_id']}{'*' if r['supplemental'] else ''} | {r['order_p95_ms']:.2f} | {r['order_p99_ms']:.2f} | {r['order_samples']} |")
            lines += ["", "전체 혼합 p95 중앙값은 215.58→231.50ms(+7.4%), p99 중앙값은 266.26→309.07ms(+16.1%)였다. 환경 차이가 있어 인덱스의 악영향으로 단정하지 않으며 전체 지연 개선도 주장하지 않는다.", ""]
        lines += ["**해석 범위**", ""] + [f"- {n}" for n in case["notes"]] + ["", "**코드 근거**", ""]
        for code in case["code"]:
            lines.append(f"- [{Path(code['path']).name}]({ '../../' + code['path'] }):{code['line']} — `{code['anchor']}`")
        primary = case["sources"][:]
        if case["id"] != "01-order-index":
            primary = [x for x in primary if x.endswith("comparison.json") or "/dev9/" in x or x.endswith("cart-existing-diagnostics.json")]
        lines += ["", "**증빙**", ""] + [f"- [{p.split('evidence/')[-1] if 'evidence/' in p else Path(p).name}]({'../../'+p})" for p in primary if not p.startswith("src/")]
        lines += ["", "**포트폴리오 삽입 문구**", "", "> " + case["caption"], ""]
    lines += ["## 공통 측정·인용 경계", "", "- 10월 주문 인덱스 사례와 11월 코드 최적화는 환경·revision이 다른 독립 사례다. 개선율을 합산하지 않는다.",
              "- 10월은 Git에 보관된 정제 JSON을 사용한다. 원시 요청 전체가 현재 체크아웃에 있는 것은 아니다.",
              "- 11월 QA9는 before 진단 42개·HTTP 39세션, after 진단 9개·HTTP 7세션이 완료됐다. 중단된 장바구니 r2와 남은 after·최종 혼합 부하는 제외한다.",
              "- 로컬 H2 651/651·harness 16/16 통과와 과거 병합 CI 650/651 통과(통계 검증 1개 실패)를 구분한다. 전체 테스트 통과나 이번 신규 검증으로 표현하지 않는다.",
              "- HTTP에는 일괄 개선의 영향이 포함된다. 개별 코드 변경의 단독 인과 효과나 통계적 유의성을 주장하지 않는다.",
              "- DEV 구현·사용자 배포·QA 검증 및 AI 활용을 실제 본인 역할에 맞게 설명한다.", ""]
    (ROOT / "CASES.md").write_text("\n".join(lines), encoding="utf-8")
    readme = ["# 성능 개선 포트폴리오 이미지", "", "요약 1장과 사례별 9장, 총 10장의 한국어 PNG다. 각 원본은 2400×1800이며 가로 960px 미리보기를 함께 제공한다.", "",
              "![전체 요약](previews/00-summary.png)", "", "## 등재용 파일", "", "| 이미지 | 원본 PNG | 960px 미리보기 |", "|---|---|---|",
              "| 전체 요약 | [원본](figures/00-summary.png) | [미리보기](previews/00-summary.png) |"]
    readme += [f"| {i:02d}. {c['summary']} | [원본](figures/{c['id']}.png) | [미리보기](previews/{c['id']}.png) |" for i, c in enumerate(data["cases"], 1)]
    readme += ["", "문제점·코드 개선·측정 조건·정량 결과·삽입 문구는 [사례 설명](CASES.md)을 사용한다. [검증 기록](QA.md)과 [수치·출처](data/portfolio.json)도 함께 보관한다.", "",
               "## 다른 PC에서 재생성", "", "Python 3.12와 한글 폰트가 필요하다. Windows 기본값은 맑은 고딕이다. 의존성·폰트를 갖춘 뒤 생성기는 네트워크·서버·DB·SSH·k6·Gradle을 사용하지 않는다.", "",
               "```powershell", "py -3.12 -m venv improvement/portfolio/.venv", ".\\improvement\\portfolio\\.venv\\Scripts\\python.exe -m pip install -r improvement/portfolio/requirements-render.txt",
               ".\\improvement\\portfolio\\.venv\\Scripts\\python.exe improvement/portfolio/scripts/render.py", ".\\improvement\\portfolio\\.venv\\Scripts\\python.exe improvement/portfolio/scripts/verify.py", "```", "",
               "Linux/macOS에서는 가상환경의 `bin/python`을 사용하고 `render.py --font-file <한글 폰트 경로> --bold-font-file <굵은 한글 폰트 경로>`를 지정한다. 실제 폰트 이름·해시와 렌더러 버전은 `data/figure-manifest.json`에 기록한다. 폰트가 다르면 PNG 해시·줄바꿈이 달라질 수 있다.", "",
               "## 근거와 범위", "", "- `0c26f5d`의 공개용 증빙과 생성 도구를 선별 반영했다. 원래 `build/`, `k6/results`의 비공개 전체 수집본과는 구분한다.",
               "- 641개 보관 증빙의 SHA-256, 비교표 21행과 46개 완료 HTTP 세션의 요청별 p95를 재검산한다. 10월은 기존 정제 자료의 해시·중앙값을 대조한다.",
               "- PNG는 하단 공통 문구를 생략하고 수치·단위·본문 측정 조건을 유지한다. 출처·측정 시점·해석 범위는 CASES.md에 보관한다. 스케줄러는 구조·동작 검증이며 개선율을 만들지 않는다.",
               "- 과거 측정의 재구성이며 현재 JDK·프레임워크 버전의 새 성능 결과가 아니다. 새 부하·배포·운영 변경은 없다.", ""]
    (ROOT / "README.md").write_text("\n".join(readme), encoding="utf-8")


def main():
    global FONT, BOLD
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-file", default="C:/Windows/Fonts/malgun.ttf")
    parser.add_argument("--bold-font-file", default="C:/Windows/Fonts/malgunbd.ttf")
    args = parser.parse_args()
    if not Path(args.font_file).is_file() or not Path(args.bold_font_file).is_file():
        raise SystemExit("한글 일반·굵은 폰트의 실제 파일 경로를 지정해야 한다.")
    FONT, BOLD = FontProperties(fname=args.font_file), FontProperties(fname=args.bold_font_file)
    font_manager.fontManager.addfont(args.font_file)
    font_manager.fontManager.addfont(args.bold_font_file)
    data = build()
    figures = [summary(data)] + [draw_case(case, i) for i, case in enumerate(data["cases"], 1)]
    documents(data)
    manifest = dict(schemaVersion=1, renderer="Matplotlib " + matplotlib.__version__, size=[W, H],
                    font=dict(regular=Path(args.font_file).name, regularSha256=digest(args.font_file), bold=Path(args.bold_font_file).name, boldSha256=digest(args.bold_font_file)),
                    source="data/portfolio.json", sourceSha256=digest(ROOT / "data/portfolio.json"),
                    scripts={str(p.relative_to(ROOT)).replace('\\', '/'): digest(p) for p in sorted((ROOT / "scripts").glob("*.py")) if p.name != "verify.py"},
                    figures=figures, newLoadTests=0, visualReviewRecord="data/visual-review.json")
    (ROOT / "data/figure-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(dict(images=len(figures), size=[W, H], previews=[960, 720], verifiedEvidence=data["archiveFilesVerified"], recalculatedHttpSessions=len(data["rawPointChecks"]), newLoadTests=0)))


if __name__ == "__main__":
    main()
