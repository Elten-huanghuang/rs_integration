"""归档第二轮优化的全部数据，绘图仅比较当前统一盘和同批 RS。"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import zipfile
import xml.etree.ElementTree as ET

import plot_disk_benchmarks as base
from matplotlib import font_manager
from matplotlib.ticker import MaxNLocator
import matplotlib.pyplot as plt

ROOT = base.ROOT
OUTPUT = ROOT / "docs/benchmarks/2026-10-01-comprehensive"
EXPECTED = {"plain": 8, "item-variants": 4, "fluid-variants": 4, "mixed": 4,
            "large-nbt": 4, "new-entry": 4, "batch-item": 6, "batch-fluid": 6,
            "uniform-variants": 4, "uniform-low-total": 4, "materials": 2,
            "simulation": 4, "miss": 4, "full-reject": 2, "new-plain": 4,
            "new-material": 2, "cache-item": 2, "cache-fluid": 2, "uniform-percentiles": 2}


def validate(raw):
    records = {}
    for name, count in EXPECTED.items():
        rows = json.loads((raw / f"{name}.json").read_text(encoding="utf-8"))
        assert len(rows) == count, (name, len(rows), count)
        for r in rows:
            m = r["primaryMetric"]
            assert math.isfinite(m["score"]) and m["scoreUnit"] == "ns/op"
            assert r["forks"] == 2 and r["measurementIterations"] == 5
            assert r["mode"] == ("sample" if name == "uniform-percentiles" else "avgt")
            if r["mode"] == "avgt":
                assert len(m["rawData"]) == 2 and all(len(f) == 5 for f in m["rawData"])
        records[name] = rows
    assert sum(map(len, records.values())) == 72
    limits = json.loads((raw / "limit-result.json").read_text())
    assert limits["status"] == "PASS" and limits["allRestoredAmountsVerified"]
    old = json.loads((raw / "compatibility.json").read_text())
    assert old["status"] == "PASS" and old["allOldAmountsVerified"]
    growth = json.loads((raw / "growth.json").read_text())
    assert len(growth) == 24 and all(r["everyAmountVerified"] for r in growth)
    return records, limits


def archive():
    raw = OUTPUT / "raw"
    if raw.exists():
        raise RuntimeError("已有原始归档；请直接重绘，或使用新的归档目录，不能覆盖历史原始数据")
    raw.mkdir(parents=True)
    source = ROOT / "build/benchmark/comprehensive"
    for name in EXPECTED:
        shutil.copyfile(source / f"{name}.json", raw / f"{name}.json")
    for name in ("growth.json", "compatibility.json"):
        shutil.copyfile(source / name, raw / name)
    shutil.copyfile(source / "limits/latest-result.json", raw / "limit-result.json")
    shutil.copyfile(ROOT / "build/benchmark/comprehensive-suite.log", raw / "suite.log")
    # 诊断批次也保留，避免把内存回退与波动数据从研究记录中删去。
    initial = raw / "diagnostic-initial"; initial.mkdir()
    for file in (ROOT / "build/benchmark/comprehensive-initial").glob("*.json"):
        shutil.copyfile(file, initial / file.name)
    shutil.copyfile(ROOT / "build/benchmark/comprehensive-initial/limits/latest-result.json", initial / "limit-result.json")
    shutil.copyfile(ROOT / "build/benchmark/comprehensive-initial-suite.log", initial / "suite.log")
    for name in ("comprehensive-final-build.log", "memory-check.log"):
        shutil.copyfile(ROOT / "build/benchmark" / name, raw / name)
    totals = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    for file in (ROOT / "build/test-results/test").glob("TEST-*.xml"):
        for name in totals: totals[name] += int(ET.parse(file).getroot().get(name, 0))
    assert totals["tests"] == 2017 and totals["failures"] == totals["errors"] == 0
    (raw / "test-totals.json").write_text(json.dumps(totals, indent=2), encoding="utf-8")
    shutil.copyfile(ROOT / "docs/benchmarks/2026-10-01/raw/environment.json", raw / "environment.json")
    sources = OUTPUT / "sources"
    for package in ("disk/core", "disk/rs", "disk/persistence"):
        shutil.copytree(ROOT / f"src/main/java/com/huanghuang/rsintegration/{package}", sources / "production" / package)
    shutil.copytree(ROOT / "src/benchmark/java", sources / "benchmark")
    shutil.copytree(ROOT / "src/test/java/com/huanghuang/rsintegration/disk", sources / "tests")
    for file in ("build.gradle", "src/main/java/com/huanghuang/rsintegration/storage/StorageIdentityBytes.java",
                 "src/main/java/com/huanghuang/rsintegration/storage/StorageItemKey.java", "src/benchmark/python/plot_disk_benchmarks.py"):
        shutil.copyfile(ROOT / file, sources / Path(file).name)
    shutil.copyfile(Path(__file__), sources / Path(__file__).name)
    refs = sources / "references"; refs.mkdir()
    for file in (ROOT / "build/benchmark").glob("reference-*.java"):
        shutil.copyfile(file, refs / file.name)
    for file in ("util/storage/InfinityDataStorage.java", "api/storage/InfinityBigIntegerCellInventory.java"):
        shutil.copyfile(Path("D:/sd/ExtendedAE_Plus-develop-1.20.1/src/main/java/com/extendedae_plus") / file, refs / Path(file).name)
    shutil.copyfile(ROOT / "docs/UNIFIED_DISK_REFERENCE_RESEARCH_2026-10-01.md", refs / "research.md")


def select(records, dataset, method, kind, implementation, amount=None):
    rows = [r for r in records[dataset] if r["benchmark"].endswith("." + method)
            and r["params"]["kind"] == kind and r["params"]["implementation"] == implementation
            and (amount is None or r["params"].get("batchAmount") == str(amount))]
    assert len(rows) == 1, (dataset, method, kind, implementation, amount)
    return rows[0]


def panel(ax, records, spec):
    dataset, method, kind, title = spec[:4]
    amount = spec[4] if len(spec) > 4 else None
    divisor = spec[5] if len(spec) > 5 else 1000
    unit = "ns/op" if divisor == 1 else "µs/op"
    rows = [select(records, dataset, method, kind, impl, amount) for impl in ("unifiedKeyPath", "rs")]
    scores = [r["primaryMetric"]["score"] / divisor for r in rows]
    errors = [r["primaryMetric"]["scoreError"] / divisor for r in rows]
    limit = max(s + e for s, e in zip(scores, errors)) * 1.4
    for y, (value, error, color) in enumerate(zip(scores, errors, ("#138A83", "#D47538"))):
        ax.barh(y, value, height=.40, color=color, zorder=3)
        ax.errorbar(value, y, xerr=error, fmt="none", color="#344454", capsize=3, linewidth=1, zorder=4)
        ax.text(value + error + limit * .02, y, f"{value:,.2f}", va="center", fontsize=11)
    ax.set_yticks([0, 1], ["统一盘", "RS 无限盘"]); ax.set_ylim(1.6, -.6); ax.set_xlim(0, limit)
    ax.set_title(title, loc="left", fontsize=12, pad=12, fontweight="bold")
    ax.set_xlabel(unit + " · 越短越快", fontsize=10)
    ax.xaxis.set_major_locator(MaxNLocator(nbins=4)); ax.grid(axis="x", color="#E5EAF0")
    ax.set_axisbelow(True); ax.tick_params(length=0, labelsize=9, colors="#526274")
    for spine in ax.spines.values(): spine.set_visible(False)


def chart(records, stem, title, specs, note="盘数据路径；未包含实际挂载、终端、网络回调和 IO。"):
    nrows = math.ceil(len(specs) / 2); height = nrows * 3.1 + 1.8
    fig, axes = plt.subplots(nrows, 2, figsize=(13, height), squeeze=False)
    fig.set_facecolor("#FAFCFE")
    for ax, spec in zip(axes.flat, specs): panel(ax, records, spec)
    for ax in list(axes.flat)[len(specs):]: ax.set_visible(False)
    fig.suptitle(title, x=.06, y=1-.15/height, ha="left", fontsize=19, fontweight="bold", color="#162B40")
    fig.text(.06, 1-.65/height, "同批实测；每面板独立线性刻度，从 0 开始。误差线为 99.9% 置信区间。", fontsize=10)
    fig.text(.06, .13/height, "单线程 JMH · 2 forks × 5 次测量 · i7-12700H / Java 17 · 2026-10-01—02\n" + note, fontsize=9)
    fig.subplots_adjust(left=.13, right=.97, top=1-1.23/height, bottom=1.05/height, hspace=.8, wspace=.5)
    for ext in ("png", "svg"): fig.savefig(OUTPUT / f"{stem}.{ext}", dpi=150, facecolor=fig.get_facecolor())
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--archive", action="store_true")
    parser.add_argument("--partial", action="store_true"); args = parser.parse_args()
    if args.partial:
        records = {}
        for name in ("plain", "item-variants", "fluid-variants", "mixed", "large-nbt", "new-entry"):
            rows = json.loads((ROOT / f"build/benchmark/comprehensive/{name}.json").read_text())
            assert len(rows) == EXPECTED[name]
            assert all(r["forks"] == 2 and len(r["primaryMetric"]["rawData"]) == 2 for r in rows)
            records[name] = rows
        OUTPUT.mkdir(parents=True, exist_ok=True)
        font = Path("C:/Windows/Fonts/msyh.ttc"); font_manager.fontManager.addfont(str(font))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
        plt.rcParams["axes.unicode_minus"] = False; plt.rcParams["svg.fonttype"] = "none"
        chart(records, "plain-vs-rs", "普通资源：统一盘 vs 原版无限盘", [
            ("plain", method, kind, f"{'物品' if kind == 'item' else '流体'} · {'插入' if method == 'existingInsert' else '提取'}", None, 1)
            for kind in ("item", "fluid") for method in ("existingInsert", "existingExtract")])
        chart(records, "tagged-vs-rs", "NBT 变体与分散类型：统一盘 vs 原版无限盘", [
            (name, method, kind, label + (" · 插入" if method == "existingInsert" else " · 提取"))
            for name, kind, label in (("item-variants", "item", "50,000 同类物品 Key"),
                                     ("fluid-variants", "fluid", "262,144 同类流体 Key"),
                                     ("mixed", "item", "50,000 Key / 512 类型"),
                                     ("large-nbt", "item", "10,000 Key / 4 KiB NBT"))
            for method in ("existingInsert", "existingExtract")])
        chart(records, "new-entry-vs-rs", "新增 NBT 身份：统一盘 vs 原版无限盘", [
            ("new-entry", "newEntryInsert", kind, "已有 50,000 Key · 新" + ("物品" if kind == "item" else "流体"))
            for kind in ("item", "fluid")], "计时外移除上次身份；不代表持续增长均摊成本。")
        print("Rendered 3 completed datasets; comprehensive suite still running")
        return
    if args.archive: archive()
    records, limits = validate(OUTPUT / "raw")
    font = Path("C:/Windows/Fonts/msyh.ttc")
    font_manager.fontManager.addfont(str(font))
    plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams["axes.unicode_minus"] = False; plt.rcParams["svg.fonttype"] = "none"
    chart(records, "plain-vs-rs", "普通资源：统一盘 vs 原版无限盘", [
        ("plain", method, kind, f"{'物品' if kind == 'item' else '流体'} · {'插入' if method == 'existingInsert' else '提取'}", None, 1)
        for kind in ("item", "fluid") for method in ("existingInsert", "existingExtract")])
    chart(records, "tagged-vs-rs", "NBT 变体与分散类型：统一盘 vs 原版无限盘", [
        (name, method, kind, label + (" · 插入" if method == "existingInsert" else " · 提取"))
        for name, kind, label in (("item-variants", "item", "50,000 同类物品 Key"),
                                 ("fluid-variants", "fluid", "262,144 同类流体 Key"),
                                 ("mixed", "item", "50,000 Key / 512 类型"),
                                 ("large-nbt", "item", "10,000 Key / 4 KiB NBT"))
        for method in ("existingInsert", "existingExtract")])
    chart(records, "batch-vs-rs", "普通资源批量搬运：取出一次＋放回一次", [
        ("batch-item", "transferPair", "item", f"物品 · 每次 {amount} 个", amount, 1) for amount in (1, 64, 4096)] + [
        ("batch-fluid", "transferPair", "fluid", f"流体 · 每次 {amount:,} mB", amount, 1) for amount in (1, 1000, 64000)],
        "每个 op 是两次库存操作；不是单次插入时间。批量不重复执行逐个插提。")
    chart(records, "uniform-vs-rs", "全库存分散访问与较低总量", [
        ("uniform-variants", "transferPair", kind, f"50,000 同类{'物品' if kind == 'item' else '流体'} · 64 单位", 64) for kind in ("item", "fluid")] + [
        ("uniform-low-total", "transferPair", "item", f"50,000 Key / 512 类型 · {amount} 个", amount) for amount in (64, 4096)] + [
        ("materials", "transferPair", "item", "512 种无 NBT 材料 · 64 个", 64, 1)],
        "查询集覆盖全库存、按置换顺序循环；极慢 RS 未必完成一轮。每 op 为取出＋放回。")
    chart(records, "new-entry-vs-rs", "首次出现的材料与 NBT 身份", [
        (name, "newEntryInsert", kind, title) for name, kind, title in (
            ("new-plain", "item", "已有石头 · 新钻石"), ("new-plain", "fluid", "已有水 · 新岩浆"),
            ("new-material", "item", "已有 511 种材料 · 新注册类型"),
            ("new-entry", "item", "已有 50,000 同类 Key · 新物品变体"),
            ("new-entry", "fluid", "已有 50,000 同类 Key · 新流体变体"))],
        "计时外移除上次新增身份；不是持续增长均摊成本。持续增长原始批次另存 growth.json。")
    chart(records, "simulation-and-miss-vs-rs", "模拟操作和不存在的资源", [
        ("simulation", method, "item", "50,000 Key / 512 类型 · " + title, 64)
        for method, title in (("simulatedInsert", "模拟插入 64 个"), ("simulatedExtract", "模拟提取 64 个"))] + [
        ("miss", "missingExtract", kind, f"50,000 同类{'物品' if kind == 'item' else '流体'} · 未命中", 64) for kind in ("item", "fluid")])
    chart(records, "cache-vs-rs", "网络库存列表：移除＋增加＋查询", [
        ("cache-item", "cacheUpdatePair", "item", "50,000 物品 Key / 512 类型"),
        ("cache-fluid", "cacheUpdatePair", "fluid", "1,000 同类流体 Key")],
        "IndexedStackList vs 原版 StackList；只测列表，不代表整网或终端同步。")
    fields = ["dataset", "operation", "kind", "entries", "profile", "access", "initial_amount", "batch_amount",
              "implementation", "mode", "mean_ns_op", "error_ns_op", "alloc_bytes_op", "p50_ns", "p95_ns", "p99_ns"]
    with (OUTPUT / "summary.csv").open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields); writer.writeheader()
        for name, rows in records.items():
            for r in rows:
                p, m = r["params"], r["primaryMetric"]; per = m["scorePercentiles"] if r["mode"] == "sample" else {}
                writer.writerow(dict(dataset=name, operation=r["benchmark"].split(".")[-1], kind=p["kind"], entries=p["entries"],
                    profile=p["profile"], access=p["access"], initial_amount=p["initialAmount"], batch_amount=p.get("batchAmount", 1),
                    implementation=p["implementation"], mode=r["mode"], mean_ns_op=m["score"], error_ns_op=m["scoreError"],
                    alloc_bytes_op=r["secondaryMetrics"]["gc.alloc.rate.norm"]["score"], p50_ns=per.get("50.0", ""),
                    p95_ns=per.get("95.0", ""), p99_ns=per.get("99.0", "")))
    readme = "# 统一盘第二轮优化：全面测试\n\n72 组 JMH、24 次持续增长观察、最大盘保存恢复、旧版真实满盘只读恢复。\n"
    readme += "raw 保留全部每轮数据、分配、尾延迟、逐批持续增长时间和 suite 日志；CSV 不舍入。\n"
    readme += "图仅比较当前盘与同批 RS。批量图是取出＋放回两次操作；列表图是移除＋增加＋查询。\n"
    readme += "满 Key 拒绝只测统一盘：原版无限盘没有同样的 Key 上限，不能虚构对应拒绝测试。\n"
    readme += "growth 是单 JVM 三次观察、没有置信区间；初始化和模板生成在计时外，逐项验证在计时外。\n"
    readme += "环境记录继承同机首轮静态信息；本轮 Java/时间以原始 JMH 和 limits 为准。功耗、亲和性及后台负载未固定。\n"
    readme += "sources 保留生产/基准/专项测试/研究参考快照；参考模组未做直接性能比较。详细解释见 ../../UNIFIED_DISK_BENCHMARK_2026-10-01.md。\n"
    readme += "raw/diagnostic-initial 是保留多余身份外层对象的诊断版本：其中内存上升，个别计时波动较大；不用于当前图表。最终版去除重复外层对象后全套复测，不按较快结果挑选数据。\n"
    readme += f"\n最大盘恢复：{limits['status']}，数量变化写入身份段：{limits['amountOnlyKeySegments']}。\n"
    (OUTPUT / "README.md").write_text(readme, encoding="utf-8")
    files = sorted(p for p in OUTPUT.rglob("*") if p.is_file() and p.name != "SHA256SUMS.txt")
    (OUTPUT / "SHA256SUMS.txt").write_text("\n".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(OUTPUT).as_posix()}" for p in files) + "\n", encoding="utf-8")
    with zipfile.ZipFile(OUTPUT.parent / "2026-10-01-comprehensive.zip", "w", zipfile.ZIP_DEFLATED) as bundle:
        for p in sorted(OUTPUT.rglob("*")):
            if p.is_file(): bundle.write(p, p.relative_to(OUTPUT))
    print("Archived 72 JMH cases; 24 growth observations; limits/old-format PASS; 7 PNG/SVG charts")


if __name__ == "__main__": main()
