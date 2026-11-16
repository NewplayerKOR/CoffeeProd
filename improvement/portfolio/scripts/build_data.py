"""보관 증빙을 검증하고 이미지·설명에 사용할 데이터와 출처를 생성한다."""
from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import math
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[1]
ARCHIVE = WORKSPACE / "improvement/code-optimization/evidence"
FIRST = WORKSPACE / "improvement/1차개선/data/metrics.json"


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def archive_module():
    path = WORKSPACE / "improvement/code-optimization/scripts/render_results.py"
    spec = importlib.util.spec_from_file_location("archived_renderer", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def percentile(values, q):
    ordered = sorted(values)
    index = (len(ordered) - 1) * q
    lower, upper = math.floor(index), math.ceil(index)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def verify_points():
    checks = []
    for points in sorted((ARCHIVE / "qa9").glob("*/http-*/measurement-points.jsonl.gz")):
        values = []
        with gzip.open(points, "rt", encoding="utf-8") as stream:
            for line in stream:
                row = json.loads(line)
                if row["type"] == "Point" and row["metric"] == "phase2_business_duration":
                    values.append(row["data"]["value"])
        summary = read(points.with_name("measurement-summary.json"))
        actual = percentile(values, .95)
        expected = summary["metrics"]["phase2_business_duration"]["values"]["p(95)"]
        if not math.isclose(actual, expected, abs_tol=1e-7, rel_tol=1e-9):
            raise ValueError(f"p95 불일치: {points}")
        checks.append(dict(file=str(points.relative_to(WORKSPACE)).replace("\\", "/"),
                           points=len(values), recalculatedP95=actual, summaryP95=expected))
    if len(checks) != 46:
        raise ValueError(f"QA9 완료 HTTP 46세션을 기대했으나 {len(checks)}개 확인")
    return checks


def build():
    from content import CASES
    legacy = archive_module()
    verified = legacy.verify()
    comparisons = legacy.comparisons()
    if comparisons != read(ARCHIVE / "comparison.json")["rows"]:
        raise ValueError("보관 비교표와 증빙 재계산이 다름")
    points = verify_points()
    first = read(FIRST)
    first_manifest = read(WORKSPACE / "improvement/final-report/data/evidence-manifest.json")
    expected = next(x["sha256"] for x in first_manifest["sources"] if x["path"].endswith("1차개선/data/metrics.json"))
    if digest(FIRST) != expected:
        raise ValueError("1차 정제 증빙 해시 불일치")
    for key, declared in first["medians"].items():
        for phase in ("before", "after"):
            if statistics.median(x[key] for x in first["mixed"][phase]) != declared[phase]:
                raise ValueError(f"1차 중앙값 불일치: {key}/{phase}")
    rows = lambda group: [x for x in comparisons if x["group"] == group]
    scheduler = read(ARCHIVE / "dev9/schedulers/validation.json")
    metrics = {
        "01-order-index": dict(before=first["medians"]["order_p95_ms"]["before"], after=first["medians"]["order_p95_ms"]["after"],
                               reduction=first["medians"]["order_p95_ms"]["reduction_percent"], runs=first["mixed"], overall=first["medians"],
                               sql=first["sql"]["queries"]),
        "02-recommendation": dict(http=rows("recommendation-http"), entities=rows("recommendation-entities")),
        "03-cart": dict(sql=rows("cart-sql"), http=rows("cart-http-reference")),
        "04-statistics": dict(rows=rows("statistics-jdbc"), details=next(x for x in read(ARCHIVE / "dev9/statistics/structural-metrics.json")["postgres-01"] if x["days"] == 366 and x["state"] == "existing")),
        "05-schedulers": dict(tests=scheduler["postgres"], execution=scheduler["execution"], comparable45SecondAfter=False),
        "06-order-summary": dict(sql=rows("order-list-sql"), entities=rows("order-list-entities"),
                                 rows=read(ARCHIVE / "dev9/orders/metrics.json")["rows"]),
        "07-order-create": dict(rows=rows("order-create-sql"), diagnostics=read(ARCHIVE / "dev9/order-create/metrics.json")["rows"]),
        "08-jwt": dict(rows=rows("jwt-verifications"), counts=read(ARCHIVE / "dev9/jwt/metrics.json")["samples"]),
        "09-review-qna-index": dict(rows=rows("index-sql-replay"), details=read(ARCHIVE / "dev9/indexes/metrics.json")),
    }
    provenance = {}
    output = []
    for original in CASES:
        case = dict(original, metrics=metrics[original["id"]])
        sources = set(case["sources"])
        for value in case["metrics"].values():
            if isinstance(value, list):
                for row in value:
                    if isinstance(row, dict):
                        sources.update("improvement/code-optimization/evidence/" + p for p in row.get("sources", []))
        case["code"] = []
        for path, anchor in original["code"]:
            lines = (WORKSPACE / path).read_text(encoding="utf-8-sig").splitlines()
            line = next((i+1 for i, value in enumerate(lines) if anchor in value), None)
            if line is None:
                raise ValueError(f"코드 설명 근거를 찾을 수 없음: {path}/{anchor}")
            case["code"].append(dict(path=path, line=line, anchor=anchor))
            sources.add(path)
        case["sources"] = sorted(sources)
        for path in sources:
            provenance[path] = digest(WORKSPACE / path)
        output.append(case)
    result = dict(schemaVersion=1, archiveCommit="0c26f5d", archiveFilesVerified=verified,
                  measuredPeriods=["2026-10-07~08", "2026-11-03~04"], newLoadTests=0,
                  comparisonRows=comparisons, qa9Coverage=read(ARCHIVE / "status.json"),
                  rawPointChecks=points, cases=output, sources=provenance,
                  limits=["10월 원시 요청은 현 체크아웃에 없으며 보관된 정제 JSON을 사용한다.",
                          "11월은 완료된 QA9 표본과 기존 DEV9 로컬 진단을 구분한다.",
                          "측정 후 JDK·프레임워크 업데이트의 성능을 재측정한 자료가 아니다.",
                          "과거 병합 CI 650/651 통과와 로컬 651/651 통과를 구분한다. 새 테스트 결과로 표현하지 않는다."])
    (ROOT / "data").mkdir(exist_ok=True)
    (ROOT / "data/portfolio.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    result = build()
    print(json.dumps(dict(evidenceFilesVerified=result["archiveFilesVerified"], httpSessionsRecalculated=len(result["rawPointChecks"]), cases=len(result["cases"]), newLoadTests=0)))
