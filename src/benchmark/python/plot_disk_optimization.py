"""归档优化后的实测，保留原有基准结果并生成前后对照图。"""
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import zipfile

import plot_disk_benchmarks as base
from matplotlib import font_manager
import matplotlib.pyplot as plt
from matplotlib.ticker import MaxNLocator

ROOT = base.ROOT
COUNTS = {"plain": 8, "item-variants": 4, "fluid-variants": 4, "mixed": 4, "large-nbt": 4, "new-entry": 4}
OUTPUT = ROOT / "docs/benchmarks/2026-10-01-optimized"
RAW = OUTPUT / "raw"


def records():
    RAW.mkdir(parents=True, exist_ok=True)
    result = {}
    for name, count in COUNTS.items():
        source = ROOT / f"build/benchmark/optimized/{name}.json"
        shutil.copyfile(source, RAW / source.name)
        rows = json.loads(source.read_text(encoding="utf-8"))
        assert len(rows) == count
        for row in rows:
            m = row["primaryMetric"]
            assert row["mode"] == "avgt" and row["forks"] == 2
            assert len(m["rawData"]) == 2 and all(len(f) == 5 for f in m["rawData"])
            assert math.isfinite(m["score"]) and m["scoreUnit"] == "ns/op"
        result[name] = rows
    shutil.copyfile(ROOT / "build/benchmark/optimized/limits/latest-result.json", RAW / "limit-result.json")
    limits = json.loads((RAW / "limit-result.json").read_text())
    assert limits["status"] == "PASS" and limits["allRestoredAmountsVerified"]
    assert limits["itemEntries"] == limits["fluidEntries"] == 262144
    shutil.copyfile(ROOT / "docs/benchmarks/2026-10-01/raw/environment.json", RAW / "environment.json")
    sources = OUTPUT / "sources"
    for package in ("disk/core", "disk/rs", "disk/persistence"):
        shutil.copytree(ROOT / f"src/main/java/com/huanghuang/rsintegration/{package}", sources / "production" / package, dirs_exist_ok=True)
    for source in (ROOT / "src/benchmark/java").rglob("*.java"):
        sources.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, sources / source.name)
    for file in ("build.gradle", "src/main/java/com/huanghuang/rsintegration/storage/StorageIdentityBytes.java", "src/main/java/com/huanghuang/rsintegration/storage/StorageItemKey.java", "src/benchmark/python/plot_disk_benchmarks.py"):
        shutil.copyfile(ROOT / file, sources / Path(file).name)
    shutil.copyfile(Path(__file__), sources / Path(__file__).name)
    return result, limits


def panel(ax, after, name, method, entries, kind, title, divisor=1, unit="ns/op"):
    rows = [base.select(after, name, method, entries, kind, "unifiedKeyPath"),
            base.select(after, name, method, entries, kind, "rs")]
    labels = ["统一盘", "RS 无限盘"]
    colors = ["#138A83", "#D47538"]
    scores = [r["primaryMetric"]["score"] / divisor for r in rows]
    errors = [r["primaryMetric"]["scoreError"] / divisor for r in rows]
    limit = max(s + e for s, e in zip(scores, errors)) * 1.38
    for y, (score, error, color) in enumerate(zip(scores, errors, colors)):
        ax.barh(y, score, height=.40, color=color, zorder=3)
        ax.errorbar(score, y, xerr=error, fmt="none", color="#344454", capsize=3, linewidth=1, zorder=4)
        ax.text(score + error + limit * .02, y, f"{score:,.2f}", va="center", fontsize=11, color="#233346")
    ax.set_yticks(range(2), labels)
    ax.set_ylim(1.6, -.6); ax.set_xlim(0, limit)
    ax.set_title(title, fontsize=12, loc="left", pad=12, fontweight="bold")
    ax.set_xlabel(unit + " · 越短越快", fontsize=10, color="#526274")
    ax.xaxis.set_major_locator(MaxNLocator(nbins=4))
    ax.grid(axis="x", color="#E5EAF0"); ax.set_axisbelow(True)
    ax.tick_params(length=0, labelsize=9, colors="#526274")
    for spine in ax.spines.values(): spine.set_visible(False)


def chart(after, name, title, specifications):
    nrows = math.ceil(len(specifications) / 2)
    height = nrows * 3.1 + 1.8
    fig, axes = plt.subplots(nrows, 2, figsize=(13, height), squeeze=False)
    fig.set_facecolor("#FAFCFE")
    for ax, specification in zip(axes.flat, specifications):
        panel(ax, after, **specification)
    for ax in list(axes.flat)[len(specifications):]: ax.set_visible(False)
    fig.suptitle(title, x=.06, y=1 - .15/height, ha="left", fontsize=19, fontweight="bold", color="#162B40")
    fig.text(.06, 1 - .65/height, "同批次实测。每面板独立线性刻度，从 0 开始；误差线为 99.9% 置信区间。", fontsize=10, color="#526274")
    fig.text(.06, .13/height, "单线程 JMH · 2 forks × 5 次测量 · i7-12700H / Java 17 / 2026-10-01\n盘数据路径；未包含真实挂载、网络缓存、终端和 IO。后台负载未固定。", fontsize=9, color="#526274")
    fig.subplots_adjust(left=.13, right=.97, top=1 - 1.23/height, bottom=1.05/height, hspace=.8, wspace=.5)
    for ext in ("png", "svg"): fig.savefig(OUTPUT / f"{name}.{ext}", dpi=150, facecolor=fig.get_facecolor())
    plt.close(fig)


def main():
    after, limits = records()
    before = base.load(ROOT / "docs/benchmarks/2026-10-01/raw")
    font = Path("C:/Windows/Fonts/msyh.ttc")
    if font.is_file():
        font_manager.fontManager.addfont(str(font))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["svg.fonttype"] = "none"
    chart(after, "plain-vs-rs", "普通无 NBT 资源：统一盘 vs 原版无限盘", [
        base.spec("plain", method, 1, kind, f"{'物品' if kind == 'item' else '流体'} · {base.METHODS[method]}")
        for kind in ("item", "fluid") for method in ("existingInsert", "existingExtract")])
    chart(after, "tagged-vs-rs", "统一盘 vs 原版无限盘：NBT 变体与分散类型", [
        base.spec(name, method, count, kind, label + " · " + ("插入" if method == "existingInsert" else "提取"), divisor=1000, unit="µs/op")
        for name, count, kind, label in (("item-variants", 50000, "item", "50,000 同类物品 Key"),
                                      ("fluid-variants", 262144, "fluid", "262,144 同类流体 Key"),
                                      ("mixed", 50000, "item", "50,000 Key / 512 类型"),
                                      ("large-nbt", 10000, "item", "10,000 Key / 4 KiB NBT"))
        for method in ("existingInsert", "existingExtract")])
    chart(after, "new-entry-vs-rs", "统一盘 vs 原版无限盘：插入新的 NBT 身份", [
        base.spec("new-entry", "newEntryInsert", 50000, kind, "物品" if kind == "item" else "流体", divisor=1000, unit="µs/op")
        for kind in ("item", "fluid")])
    fields = ["dataset", "operation", "kind", "entries", "implementation", "mean_ns_op", "error_ns_op", "alloc_bytes_op", "before_ns_op", "before_alloc_bytes_op"]
    with (OUTPUT / "summary.csv").open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields); writer.writeheader()
        for name, rows in after.items():
            for r in rows:
                p, metric = r["params"], r["primaryMetric"]
                method = r["benchmark"].split(".")[-1]
                old = base.select(before, name, method, int(p["entries"]), p["kind"], p["implementation"])
                alloc = r["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
                writer.writerow(dict(dataset=name, operation=method, kind=p["kind"], entries=p["entries"], implementation=p["implementation"],
                                     mean_ns_op=metric["score"], error_ns_op=metric["scoreError"], alloc_bytes_op=alloc,
                                     before_ns_op=old["primaryMetric"]["score"], before_alloc_bytes_op=old["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]))
                if p["implementation"] == "unifiedKeyPath":
                    print(name, method, p["kind"], f"{old['primaryMetric']['score']:.2f} -> {metric['score']:.2f} ns/op; {alloc:.2f} B/op")
    readme = "# 统一盘优化后实测\n\n28 个正式 JMH 组合，2 forks × 5 次测量；原始 JSON 含 rawData 与 GC 指标。\n\n"
    readme += "优化前原始数据保留在相邻的 2026-10-01/raw，未覆盖。summary.csv 精确保存前后结果。\n"
    readme += "前后批次不同，功耗和后台负载未固定；原版无限盘本批次同时复测。比较范围为盘数据路径，不能推导整网/终端性能。\n"
    readme += "新增身份基准在计时外删除上次身份，准备会影响缓存热度和 GC 指标；不代表不断增长填盘的均摊成本。\n"
    readme += "完整解释见 ../../UNIFIED_DISK_BENCHMARK_2026-10-01.md 的优化结果节。sources 为测量源码快照；原版 RS 1.12.4。\n"
    readme += f"\n最大盘物品/流体各 262,144 Key，保存恢复逐项验证：{limits['status']}。\n"
    (OUTPUT / "README.md").write_text(readme, encoding="utf-8")
    files = sorted(p for p in OUTPUT.rglob("*") if p.is_file() and p.name != "SHA256SUMS.txt")
    (OUTPUT / "SHA256SUMS.txt").write_text("\n".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(OUTPUT).as_posix()}" for p in files) + "\n", encoding="utf-8")
    with zipfile.ZipFile(OUTPUT.parent / "2026-10-01-optimized.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(OUTPUT.rglob("*")):
            if p.is_file(): archive.write(p, p.relative_to(OUTPUT))
    print("Archived 28 cases, limit PASS, CSV, 3 PNG/SVG charts and sources")


if __name__ == "__main__": main()
