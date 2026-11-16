"""PNG·출처·수치·문서 링크를 검증한다. 새 부하·앱 테스트·배포는 실행하지 않는다."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageStat
from build_data import ROOT, WORKSPACE, archive_module, digest, read, verify_points

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def verify():
    data = read(ROOT / "data/portfolio.json")
    manifest = read(ROOT / "data/figure-manifest.json")
    archive = archive_module()
    evidence_count = archive.verify()
    assert archive.comparisons() == data["comparisonRows"], "비교표와 재계산 결과가 다름"
    raw = verify_points()
    assert raw == data["rawPointChecks"], "HTTP p95 재계산 결과가 다름"
    assert digest(ROOT / manifest["source"]) == manifest["sourceSha256"], "그림과 데이터가 일치하지 않음"
    for path, expected in data["sources"].items():
        assert digest(WORKSPACE / path) == expected, f"출처 변경: {path}"
    for path, expected in manifest["scripts"].items():
        assert digest(ROOT / path) == expected, f"생성 코드 변경: {path}"
    assert len(manifest["figures"]) == 10
    assert len(list((ROOT / "figures").glob("*.png"))) == 10
    assert len(list((ROOT / "previews").glob("*.png"))) == 10
    text_count = 0
    for figure in manifest["figures"]:
        for key, size, hash_key in [("file", (2400, 1800), "sha256"), ("preview", (960, 720), "previewSha256")]:
            path = ROOT / figure[key]
            assert digest(path) == figure[hash_key], f"이미지 해시 불일치: {path}"
            with Image.open(path) as image:
                assert image.format == "PNG" and image.size == size
                assert min(ImageStat.Stat(image.convert("RGB")).stddev) > 15, f"빈 이미지: {path}"
        assert not figure["textOverlaps"], f"텍스트 겹침: {figure['file']}"
        text_count += len(figure["textBounds"])
    links = 0
    for path in (ROOT / "README.md", ROOT / "CASES.md", ROOT / "QA.md"):
        text = path.read_bytes().decode("utf-8-sig", errors="strict")
        assert "\ufffd" not in text
        for target in re.findall(r"\]\(([^)]+)\)", text):
            if re.match(r"https?://", target):
                continue
            target_path = path.parent / target.split("#", 1)[0]
            # 이번 명령이 마지막에 생성하는 자기 결과 파일만 생성 후 확인한다.
            if target_path.resolve() != (ROOT / "data/validation.json").resolve():
                assert target_path.exists(), f"끊어진 링크: {path.name}/{target}"
            links += 1
    # 새 연결 정보·자격 증명을 넣지 않는다. 공유된 원래 증빙은 수정하지 않는다.
    snapshot = (ROOT / "data/portfolio.json").read_text(encoding="utf-8")
    assert not re.search(r'"(?:accessToken|refreshToken|password|authorization)"\s*:', snapshot, re.I)
    assert not re.search(r'192\.168\.\d+\.\d+|C:\\\\Users', snapshot)
    visual_path = ROOT / "data/visual-review.json"
    visual = "pending"
    if visual_path.exists():
        review = read(visual_path)
        assert len(review["figures"]) == 10
        for figure, checked in zip(manifest["figures"], review["figures"]):
            assert figure["file"] == checked["file"] and figure["sha256"] == checked["sha256"]
            assert figure["previewSha256"] == checked["previewSha256"]
            assert checked["nativeOpened"] and checked["preview960Opened"]
        visual = review["result"]
    result = dict(result="PASS", evidenceFiles=evidence_count, comparisonRows=len(data["comparisonRows"]),
                  recalculatedHttpSessions=len(raw), images=10, previews=10, checkedTextBounds=text_count,
                  localMarkdownLinks=links, visualReview=visual, newLoadTests=0, applicationTestsExecuted=0)
    (ROOT / "data/validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    assert (ROOT / "data/validation.json").is_file()
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    verify()
