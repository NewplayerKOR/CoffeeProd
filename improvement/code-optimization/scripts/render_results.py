"""Rebuild tables and figures from committed evidence. No server, SSH, Gradle or k6 calls."""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import os
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "evidence"


def read(name):
    return json.loads((DATA / name).read_text(encoding="utf-8-sig"))


def verify():
    manifest = read("manifest.json")
    for item in manifest["files"]:
        path = DATA / item["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Evidence checksum mismatch: " + item["file"])
    return len(manifest["files"])


def comparisons():
    rows = []
    def add(group, label, unit, before, after, sources, note):
        a, b = statistics.median(before), statistics.median(after)
        rows.append(dict(group=group, label=label, unit=unit, before=before, after=after,
                         beforeMedian=a, afterMedian=b, reductionPercent=(a-b)/a*100 if a else None,
                         sources=sources, note=note))
    for variant, label in (("catalog", "192 catalog products"), ("recommendation1000", "1,000 eligible candidates")):
        values, entities, sources, golden = {}, {}, [], {}
        for phase in ("before", "after"):
            values[phase], entities[phase], golden[phase] = [], [], []
            for repeat in (1, 2, 3):
                summary = f"qa9/{phase}/http-c1-{variant}-r{repeat}/measurement-summary.json"
                report = f"qa9/{phase}/diag-c1-{variant}-r{repeat}/measurements.json"
                values[phase].append(read(summary)["metrics"]["phase2_business_duration"]["values"]["p(95)"])
                diag = read(report)
                entities[phase].append(next(x for x in diag["samples"] if x["name"] == "recommend-decaf-null-10")["entities"])
                golden[phase].append(diag["golden"])
                sources.extend((summary, report))
        if golden["before"] != golden["after"]:
            raise ValueError("Recommendation business output differs")
        add("recommendation-http", label, "ms", values["before"], values["after"], sources,
            "Median of three run-level workflow p95s; authenticated limit-10 requests; shared JWT changes included")
        add("recommendation-entities", label, "ORM entity loads", entities["before"], entities["after"], sources,
            "Service-call diagnostic; all entity types; not total HTTP request loading")
    cart = {phase: [read(f"qa9/{phase}/diag-c2-catalog-r{i}/measurements.json") for i in (1,2,3)] for phase in ("before","after")}
    for name, label in (("existing-cart-20", "Existing cart: 20 concurrent reads"), ("first-creation-20", "First cart: 20 concurrent creates")):
        vals = {phase: [next(x for x in r["samples"] if x["name"] == name)["hibernateStatements"] for r in cart[phase]] for phase in cart}
        add("cart-sql", label, "SQL statements", vals["before"], vals["after"],
            [f"qa9/{p}/diag-c2-catalog-r{i}/measurements.json" for p in cart for i in (1,2,3)],
            "Median and observed range of three diagnostics; first-create cost increased")
    stats = next(x for x in read("dev9/statistics/structural-metrics.json")["postgres-01"] if x["days"] == 366 and x["state"] == "existing")
    add("statistics-jdbc", "366 days; existing rows", "JDBC execution calls", [stats["legacyExecutions"]], [stats["optimizedExecutions"]],
        ["dev9/statistics/structural-metrics.json"], "Existing local PostgreSQL fixture; includes 366 date locks; not network round trips")
    order = read("dev9/orders/metrics.json")["rows"]
    for size in (10,50):
        old = next(x for x in order["before-postgres-01"] if not x["admin"] and x["size"] == size)
        new = next(x for x in order["postgres-01"] if not x["admin"] and x["size"] == size)
        for key, group, unit in (("sql","order-list-sql","SQL statements"),("entities","order-list-entities","ORM entity loads")):
            add(group, f"Member page size {size}", unit, [old[key]], [new[key]], ["dev9/orders/metrics.json"], "Existing local PostgreSQL service-call fixture; not HTTP latency")
    create = read("dev9/order-create/metrics.json")["rows"]
    for count in (1,5,20):
        old = next(x for x in create["before-postgres-01"] if x["products"] == count)
        new = next(x for x in create["postgres-01"] if x["products"] == count)
        add("order-create-sql", f"{count} distinct product(s)", "SQL statements", [old["sql"]], [new["sql"]],
            ["dev9/order-create/metrics.json"], "Existing local PostgreSQL service-call fixture; conditional stock updates preserved")
    jwt = read("dev9/jwt/metrics.json")["samples"]
    for scope in ("filter","reissue"):
        old = next(x for x in jwt["before-jwt-01"]["counts"] if x["scope"] == scope)
        new = next(x for x in jwt["targeted-01"]["counts"] if x["scope"] == scope)
        add("jwt-verifications", scope.title(), "signature verifications", [old["parseSignedClaims"]], [new["parseSignedClaims"]],
            ["dev9/jwt/metrics.json"], "Count per token/request; not CPU or latency improvement percentage")
    for goal in read("dev9/indexes/metrics.json")["goals"]:
        add("index-sql-replay", goal["name"], "ms", [goal["beforeMs"]], [goal["candidateMs"]],
            ["dev9/indexes/metrics.json"], "Existing local PostgreSQL final-combination SQL replay; 20 warmups, 100 calls x 3; not HTTP")
    cart_after = read("qa9/after/http-c2-catalog-r1/measurement-summary.json")["metrics"]["phase2_business_duration"]["values"]["p(95)"]
    cart_before = read("qa9/before/http-c2-catalog-r1/measurement-summary.json")["metrics"]["phase2_business_duration"]["values"]["p(95)"]
    add("cart-http-reference", "One matched cart HTTP pair", "ms", [cart_before], [cart_after],
        ["qa9/before/http-c2-catalog-r1/measurement-summary.json","qa9/after/http-c2-catalog-r1/measurement-summary.json"],
        "One completed pair only; shared JWT/cart changes; not a repeat-stability claim; excluded from default figures")
    return rows


def tables(rows):
    result = {"schemaVersion": 1, "scope": "Existing evidence only; partial QA9 acceptance; no new tests", "rows": rows,
              "scheduler": {"status": "no comparable fixed-45-second after measurement", "source": "dev9/schedulers/validation.json"},
              "coverage": read("status.json")}
    (DATA / "comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    with (DATA / "comparison.csv").open("w", encoding="utf-8", newline="") as stream:
        keys = ["group","label","unit","beforeMedian","afterMedian","reductionPercent","beforeRuns","afterRuns","note"]
        writer = csv.DictWriter(stream, fieldnames=keys); writer.writeheader()
        for row in rows:
            writer.writerow({**{key:row[key] for key in keys if key in row}, "beforeRuns":len(row["before"]), "afterRuns":len(row["after"])})


def figures(rows):
    cache = ROOT / ".cache" / "matplotlib"
    cache.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("MPLCONFIGDIR", str(cache))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch
    matplotlib.rcParams.update({"font.family":"DejaVu Sans", "font.size":11, "svg.hashsalt":"coffeeprod-existing-evidence-v1", "svg.fonttype":"path"})
    titles = {"recommendation-http":"Recommendation HTTP workflow latency", "recommendation-entities":"Recommendation entity loading",
              "cart-sql":"Cart SQL: read benefit and creation tradeoff", "statistics-jdbc":"Statistics range JDBC calls",
              "order-list-sql":"Order list SQL statements", "order-list-entities":"Order list entity loading",
              "order-create-sql":"Order creation SQL statements", "jwt-verifications":"JWT signature verification counts",
              "index-sql-replay":"Selected-index SQL replay latency"}
    out = ROOT / "figures"; out.mkdir(exist_ok=True)
    generated = []
    for group, title in titles.items():
        selected = [x for x in rows if x["group"] == group]
        maximum = max(max(x["before"]+x["after"]) for x in selected)
        fig, ax = plt.subplots(figsize=(11, 2.8+0.82*len(selected)), dpi=160)
        fig.subplots_adjust(left=.28, right=.94, bottom=.24, top=.78)
        for i, row in enumerate(selected):
            for shift, phase, color in ((-.18,"before","#7b8794"),(.18,"after","#2563a6")):
                value = row[phase+"Median"]; samples = row[phase]
                if phase == "after" and value > row["beforeMedian"]: color="#b7791f"
                ax.barh(i+shift,value,height=.28,color=color,zorder=3)
                if value == 0: ax.plot([0],[i+shift],marker="|",markersize=14,color=color)
                if len(samples)>1 and max(samples)>min(samples):
                    ax.errorbar(value,i+shift,xerr=[[value-min(samples)],[max(samples)-value]],fmt="none",ecolor="#253244",capsize=3,zorder=4)
                label=f"{value:,.4f}".rstrip("0").rstrip(".") if row["unit"]=="ms" else f"{value:,.0f}"
                if phase=="after": label+=f"  ({-row['reductionPercent']:+.1f}%)"
                ax.text(max(samples)+maximum*.025,i+shift,label,va="center",fontsize=10)
        ax.set_yticks(range(len(selected)),[x["label"] for x in selected]);ax.invert_yaxis()
        ax.set_xlim(0,maximum*1.5);ax.set_xlabel(selected[0]["unit"])
        ax.grid(axis="x",color="#d9dfe7",linewidth=.7);ax.set_axisbelow(True)
        ax.spines[["top","right"]].set_visible(False)
        ax.legend(handles=[Patch(color="#7b8794",label="Before"),Patch(color="#2563a6",label="After (increase shown in amber)")],loc="lower left",bbox_to_anchor=(0,1.03),ncol=2,frameon=False,fontsize=9)
        fig.suptitle(title,x=.04,y=.96,ha="left",fontsize=16,fontweight="bold")
        note="Existing measurements only. Whiskers show observed run range, not confidence intervals."
        if group=="recommendation-http":note+="\nPrivate LAN; authenticated limit-10 requests; median of 3 run-level p95s; shared JWT changes apply."
        else:note+="\n"+selected[0]["note"]
        fig.text(.04,.035,note,fontsize=8.5,color="#4b5563")
        fig.canvas.draw()
        for extension in ("png","svg"):
            file=out/(group+"."+extension)
            metadata={"Software":"CoffeeProd existing-evidence renderer"} if extension=="png" else {"Date":None,"Creator":"CoffeeProd existing-evidence renderer"}
            fig.savefig(file,metadata=metadata)
            generated.append({"file":file.name,"group":group,"sha256":hashlib.sha256(file.read_bytes()).hexdigest()})
        plt.close(fig)
    (out/"manifest.json").write_text(json.dumps({"renderer":"Matplotlib 3.10.8","source":"../evidence/comparison.json","figures":generated},indent=2)+"\n",encoding="utf-8")
    return len(generated)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--data-only",action="store_true");args=parser.parse_args()
    files=verify();rows=comparisons();tables(rows)
    count=0 if args.data_only else figures(rows)
    print(json.dumps({"evidenceFilesVerified":files,"comparisonRows":len(rows),"images":count,"workloadsExecuted":0}))
