"""Copy existing measurements into a portable, redacted evidence archive. Never runs workloads."""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import re
import statistics
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parents[1]
REPO = HERE.parents[1]
RUN = "qa9-20261103-212226"
BEFORE_REV = "b04bf9e30d5109dc934e230c2a50c80c140099c0"
AFTER_REV = "008d443ba92932c04c609c2d60633344d69bb9cc"
DROP_KEYS = {"password", "accesstoken", "refreshtoken", "authorization", "secret", "privatekey", "owner", "localmachine", "processes", "pid", "originalcontainers", "remaining"}
PRIVATE_HOST = re.compile(r"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|127\.0\.0\.1|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b")


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def clean(value):
    if isinstance(value, dict):
        return {key: clean(item) for key, item in value.items() if key.lower() not in DROP_KEYS}
    if isinstance(value, list):
        return [clean(item) for item in value]
    if isinstance(value, str):
        if re.match(r"^[A-Za-z]:[\\/]", value) or value.startswith("/home/"):
            return "<local-path-omitted>"
        if value.startswith(("http://", "https://")) and PRIVATE_HOST.search(value):
            parsed = urlsplit(value)
            if not parsed.path:
                return parsed.scheme + "://<private-host>" + (":" + str(parsed.port) if parsed.port else "")
            return parsed.path + ("?" + parsed.query if parsed.query else "")
        return PRIVATE_HOST.sub("<private-host>", value)
    return value


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class Archive:
    def __init__(self, source: Path, output: Path):
        if output.exists():
            raise SystemExit("Output exists; preserve it and choose a new archive output.")
        self.source, self.output, self.records = source, output, []
        output.mkdir(parents=True)

    def record(self, source: Path, target: Path, transformation: str, origin=None, **extra):
        if origin is None:
            origin = source.relative_to(self.source).as_posix()
        self.records.append({"file": target.relative_to(self.output).as_posix(), "source": origin,
                             "sourceSha256": digest(source), "sha256": digest(target),
                             "transformation": transformation, **extra})

    def json(self, source: Path, relative: str):
        value = read(source)
        sanitized = clean(value)
        target = self.output / relative
        write_json(target, sanitized)
        self.record(source, target, "json-formatting; private environment fields removed" if sanitized != value else "json-formatting only")
        return sanitized

    def points(self, source: Path, relative: str):
        target = self.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        original = hashlib.sha256()
        points, business = 0, []
        # Keep every numeric metric point; remove host names/IPs and non-whitelisted tags.
        allowed_tags = {"candidate", "variant", "phase", "scenario", "method", "status", "name", "url", "check", "group", "expected_response", "proto", "tls_version"}
        with source.open("rb") as incoming, target.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=6) as zipped:
            for line in incoming:
                original.update(line)
                row = json.loads(line)
                if row.get("type") == "Point":
                    data = row["data"]
                    row = {"type": "Point", "metric": row["metric"], "data": {
                        "time": data["time"], "value": data["value"],
                        "tags": clean({key: value for key, value in data.get("tags", {}).items() if key in allowed_tags})}}
                    points += 1
                    if row["metric"] == "phase2_business_duration":
                        business.append(data["value"])
                elif row.get("type") == "Metric":
                    row = clean(row)
                else:
                    continue
                zipped.write((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
        self.records.append({"file": relative, "source": source.relative_to(self.source).as_posix(),
                             "sourceSha256": original.hexdigest(), "sha256": digest(target),
                             "transformation": "gzip; all numeric metric points retained; whitelisted tags; private URL hosts removed", "points": points})
        return business

    def phase(self, source: Path, target: str, revision: str):
        conditions = self.json(source / "conditions.json", target + "/conditions.json")
        if (source / "execution.json").exists():
            self.json(source / "execution.json", target + "/execution.json")
        diagnostics, sessions = [], []
        for sample in sorted(source.iterdir()):
            if not sample.is_dir():
                continue
            match = re.fullmatch(r"(diag|http)-c([1-8])-(.+)-r([123])", sample.name)
            if not match:
                continue
            kind, candidate, variant, repeat = match.groups()
            candidate, repeat = int(candidate), int(repeat)
            dest = target + "/" + sample.name
            if kind == "diag":
                native = sample / "diagnostics" / f"case{candidate}-{variant}" / "measurements.json"
                xmls = list(native.parent.glob("xml/TEST-*.xml"))
                if not native.exists() or not xmls:
                    continue
                for xml_path in xmls:
                    suite = ET.parse(xml_path).getroot()
                    if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
                        raise ValueError(f"Incomplete diagnostic: {sample.name}")
                self.json(native, dest + "/measurements.json")
                diagnostics.append(sample.name)
                continue
            life_file = sample / "lifecycle.json"
            if not life_file.exists() or read(life_file).get("status") != "PASS":
                continue  # Deliberately interrupted cart r2 is not published as a measurement.
            self.json(life_file, dest + "/lifecycle.json")
            for stage in ("warmup", "measurement"):
                self.json(sample / stage / "summary.json", dest + f"/{stage}-summary.json")
                self.json(sample / stage / "process.json", dest + f"/{stage}-process.json")
            summary = read(sample / "measurement/summary.json")
            values = self.points(sample / "measurement/timeseries.json", dest + "/measurement-points.jsonl.gz")
            if not values:
                raise ValueError(f"No business timings: {sample.name}")
            ordered = sorted(values)
            index = (len(ordered) - 1) * 0.95
            low = int(index)
            p95 = ordered[low] + (ordered[min(low + 1, len(ordered) - 1)] - ordered[low]) * (index - low)
            expected = summary["metrics"]["phase2_business_duration"]["values"]
            if abs(p95 - expected["p(95)"]) > 1e-6:
                raise ValueError(f"Raw/summary p95 mismatch: {sample.name}")
            if candidate == 3 and len(values) != 1:
                raise ValueError("Statistics is one measured request, not a percentile distribution")
            sessions.append({"sample": sample.name, "candidate": candidate, "variant": variant, "repeat": repeat,
                             "businessOperations": len(values), "meanMs": statistics.mean(values), "p95Ms": p95,
                             "percentileInterpretationAllowed": candidate != 3})
        index_file = self.output / target / "archive-index.json"
        write_json(index_file, {"sourceRevision": revision,
                   "sourceHash": conditions["sourceHash"], "measurementHash": conditions["measurementHash"],
                   "fixtureHash": conditions["fixtureHash"], "diagnostics": diagnostics, "sessions": sessions,
                   "scope": "Completed evidence only; missing after conditions remain unmeasured"})
        self.records.append({"file": index_file.relative_to(self.output).as_posix(), "sha256": digest(index_file),
                             "transformation": "archive index derived from completed source sessions and diagnostics"})
        return {"diagnostics": len(diagnostics), "httpSessions": len(sessions)}

    def junit(self, folder: Path, relative: str, origin_prefix: str | None = None):
        suites = ET.Element("testsuites")
        originals = []
        total = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        for xml_file in sorted(folder.rglob("TEST-*.xml")):
            old = ET.parse(xml_file).getroot()
            new = ET.SubElement(suites, "testsuite", {key: clean(old.get(key, "0" if key != "name" else "")) for key in ("name", "tests", "failures", "errors", "skipped", "time")})
            for key in total:
                total[key] += int(old.get(key, "0"))
            for case in old.findall("testcase"):
                element = ET.SubElement(new, "testcase", {key: clean(case.get(key, "")) for key in ("name", "classname", "time")})
                for result in case:
                    if result.tag in ("failure", "error", "skipped"):
                        ET.SubElement(element, result.tag, {key: clean(value) for key, value in result.attrib.items() if key in ("type", "message")})
            originals.append({"source": (origin_prefix + "/" + xml_file.relative_to(folder).as_posix()) if origin_prefix else xml_file.relative_to(self.source).as_posix(), "sha256": digest(xml_file)})
        if not originals:
            raise ValueError(f"No JUnit evidence: {folder}")
        target = self.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        ET.indent(suites)
        ET.ElementTree(suites).write(target, encoding="utf-8", xml_declaration=True)
        self.records.append({"file": relative, "sha256": digest(target), "sources": originals,
                             "transformation": "merged JUnit; testcase outcomes retained; system-out, system-err and environment properties omitted"})
        return total


def export(args):
    archive = Archive(args.source_root.resolve(), args.output.resolve())
    root = archive.source
    qa = root / "build/perf-phase2" / RUN
    counts = {"qa9Before": archive.phase(qa / "before", "qa9/before", BEFORE_REV),
              "qa9After": archive.phase(qa / "source-after/build/perf-phase2" / RUN / "after", "qa9/after", AFTER_REV),
              "dev8V2Before": archive.phase(root / "build/perf-phase2/dev8-v2-before-01/before", "dev8-v2/before", BEFORE_REV)}
    original_diag = root / "build/perf-phase2/dev8-20261102-01/before"
    old_count = 0
    for native in sorted(original_diag.glob("diag-c*-r*/diagnostics/case*/measurements.json")):
        sample = native.parents[2].name
        archive.json(native, "dev8-v1-diagnostics/" + sample + "/measurements.json")
        old_count += 1
    counts["dev8OriginalDiagnostics"] = old_count
    native_files = {
        "cart": ("cart-20261103-105753", ["validation.json"]),
        "recommendation": ("recommendation-20261103-104036", ["validation.json"]),
        "statistics": ("statistics-20261103-112207", ["validation.json", "structural-metrics.json"]),
        "schedulers": ("schedulers-20261103-145410", ["validation.json"]),
        "orders": ("orders-20261103-152639", ["validation.json", "metrics.json"]),
        "order-create": ("order-create-20261103-155409", ["validation.json", "metrics.json"]),
        "jwt": ("jwt-20261103-164148", ["validation.json", "metrics.json", "measurement-lineage.json"]),
        "indexes": ("indexes-20261103-171815", ["validation.json", "metrics.json", "decisions.json", "combination-check.json", "migration-validation.json"]),
    }
    for family, (directory, names) in native_files.items():
        for name in names:
            archive.json(root / "build/dev-task9" / directory / name, f"dev9/{family}/{name}")
    for name in ("existing-results-summary.json", "regression-summary.json", "cart-existing-diagnostics.json", "before-summary.json", "user-stop.json", "preservation-after-stop.json"):
        archive.json(qa / name, "qa9/" + name)
    counts["localH2"] = archive.junit(qa / "regression-02/test-xml", "regression/local-h2.xml")
    counts["localHarness"] = archive.junit(qa / "regression-02/harness-xml", "regression/local-harness.xml")
    if args.ci_artifact:
        ci = args.ci_artifact.resolve()
        for name in ("results.json", "revision.json"):
            source = next(ci.rglob(name))
            target = archive.output / "ci/41743023340" / name
            write_json(target, clean(read(source)))
            archive.record(source, target, "json-formatting only", origin="github-actions:41743023340/" + source.relative_to(ci).as_posix())
        counts["mergeCI"] = archive.junit(ci, "ci/41743023340/junit.xml", "github-actions:41743023340")
    status = {"archiveVersion": 1, "asOf": "2026-11-04", "qa9Acceptance": "incomplete; further tests prohibited by user",
              "completedEvidence": counts, "excludedAbortedSamples": ["qa9/after/http-c2-catalog-r2"],
              "notExecuted": ["remaining QA9 after workloads", "final mixed load", "prepared extra PostgreSQL/Redis reruns"],
              "originalLocations": ["dedicated home-server measurement stack", "collected local build/ results"],
              "renderNeedsServer": False, "appTestsExecutedByArchive": False,
              "ciURL": "https://github.com/NewplayerKOR/CoffeeProd/actions/runs/41743023340",
              "ciNote": "650/651 passed; one KST/leap-day statistics assertion failed; image publication skipped",
              "historical27Percent": "Already versioned in improvement/1차개선; separate dataset, never added to QA9 percentages"}
    write_json(archive.output / "status.json", status)
    archive.records.append({"file": "status.json", "sha256": digest(archive.output / "status.json"), "transformation": "archive scope metadata; no invented measurements"})
    write_json(archive.output / "manifest.json", {"archiveVersion": 1, "createdUtc": datetime.now(timezone.utc).isoformat(),
               "purpose": "Offline reuse of existing measurements; no test execution", "privacy": "No credentials, users.json, databases, machine names, full logs or private hosts included",
               "files": archive.records})
    print(json.dumps({"archive": str(archive.output), "files": len(archive.records), "counts": counts}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=REPO)
    parser.add_argument("--output", type=Path, required=True, help="New staging directory; existing directories are refused")
    parser.add_argument("--ci-artifact", type=Path, help="Existing downloaded CI artifact; never downloads or reruns it")
    export(parser.parse_args())
