"""保留 JMH 原始数据，生成横向柱状图与汇总；不重新运行基准。"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "gradlew.bat").is_file())
DEPS = ROOT / "build/benchmark/plot-deps"
if DEPS.is_dir():
    sys.path.insert(0, str(DEPS))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.ticker import MaxNLocator

EXPECTED = {"item-variants": 16, "plain": 8, "fluid-variants": 8,
            "mixed": 4, "large-nbt": 8, "new-entry": 4,
            "core": 2, "insert-percentiles": 2}
COLORS = {"unifiedKeyPath": "#138A83", "rs": "#D47538"}
LABELS = {"unifiedKeyPath": "统一盘", "rs": "RS 无限盘", "unifiedCore": "统一盘预生成 Key"}
METHODS = {"existingInsert": "插入已有种类", "existingExtract": "提取已有种类", "newEntryInsert": "插入新种类"}


def load(raw):
    records = {}
    for name, count in EXPECTED.items():
        entries = json.loads((raw / f"{name}.json").read_text(encoding="utf-8"))
        assert len(entries) == count, (name, len(entries), count)
        identities = set()
        for row in entries:
            metric = row["primaryMetric"]
            assert metric["scoreUnit"] == "ns/op" and math.isfinite(metric["score"])
            assert row["forks"] == 2 and row["measurementIterations"] == 5
            assert row["mode"] == ("sample" if name == "insert-percentiles" else "avgt")
            identity = (row["benchmark"], tuple(sorted(row["params"].items())))
            assert identity not in identities
            identities.add(identity)
            if row["mode"] == "avgt":
                assert len(metric["rawData"]) == 2 and all(len(fork) == 5 for fork in metric["rawData"])
        records[name] = entries
    limits = json.loads((raw / "limit-result.json").read_text(encoding="utf-8"))
    assert limits["status"] == "PASS" and limits["allRestoredAmountsVerified"]
    assert limits["itemEntries"] == limits["fluidEntries"] == 262144
    return records


def select(records, name, method, entries, kind, implementation):
    rows = [row for row in records[name] if row["benchmark"].endswith("." + method)
            and row["params"]["entries"] == str(entries) and row["params"]["kind"] == kind
            and row["params"]["implementation"] == implementation]
    assert len(rows) == 1, (name, method, entries, kind, implementation)
    return rows[0]


def panel(ax, records, name, method, entries, kind, title, divisor=1000, unit="µs/op", percentile=None):
    implementations = ["unifiedKeyPath", "rs"]
    rows = [select(records, name, method, entries, kind, impl) for impl in implementations]
    values = [(r["primaryMetric"]["scorePercentiles"][percentile] if percentile else r["primaryMetric"]["score"]) / divisor for r in rows]
    errors = [0 if percentile else r["primaryMetric"]["scoreError"] / divisor for r in rows]
    limit = max(v + e for v, e in zip(values, errors)) * 1.38
    for y, (impl, value, error) in enumerate(zip(implementations, values, errors)):
        ax.barh(y, value, height=.42, color=COLORS[impl], zorder=3)
        if error:
            ax.errorbar(value, y, xerr=error, fmt="none", color="#344454", capsize=3, linewidth=1, zorder=4)
        ax.text(value + error + limit * .022, y, f"{value:,.2f}", va="center", fontsize=11, color="#233346")
    ax.set_yticks([0, 1], [LABELS[i] for i in implementations])
    ax.set_ylim(1.6, -.6)
    ax.set_xlim(0, limit)
    ax.xaxis.set_major_locator(MaxNLocator(nbins=4))
    ax.set_xlabel(unit + "  ·  越短越快", color="#526274", fontsize=10)
    ax.set_title(title, loc="left", fontsize=12, pad=12, fontweight="bold")
    ax.grid(axis="x", color="#E5EAF0", zorder=0)
    ax.set_axisbelow(True)
    ax.tick_params(axis="both", length=0, labelsize=10, colors="#526274")
    for spine in ax.spines.values():
        spine.set_visible(False)


def chart(records, output, stem, title, specs, columns=2, subtitle=None):
    rows = math.ceil(len(specs) / columns)
    height = rows * 2.85 + 1.7
    fig, axes = plt.subplots(rows, columns, figsize=(12, height), squeeze=False)
    fig.set_facecolor("#FAFCFE")
    for ax, spec in zip(axes.flat, specs):
        panel(ax, records, **spec)
    for ax in list(axes.flat)[len(specs):]:
        ax.set_visible(False)
    fig.suptitle(title, x=.08, y=1 - .15 / height, ha="left", fontsize=19, fontweight="bold", color="#162B40")
    fig.text(.08, 1 - .63 / height, subtitle or "JMH 平均耗时；误差线为 99.9% 置信区间。各面板采用独立线性刻度，均从 0 开始。", fontsize=10, color="#526274")
    fig.text(.08, .12 / height, "RS 1.12.4 真实无限盘 vs 统一盘 Key 编码 + 核心操作  |  2 forks × 5 次测量\n盘数据路径，未包含挂载、网络缓存、终端与文件保存；i7-12700H / Java 17 / 2026-10-01", fontsize=9, color="#526274")
    fig.subplots_adjust(left=.13, right=.975, top=1 - 1.22 / height, bottom=1.05 / height, hspace=.85, wspace=.50)
    for suffix in ("png", "svg"):
        fig.savefig(output / f"{stem}.{suffix}", dpi=160, facecolor=fig.get_facecolor())
    plt.close(fig)


def spec(name, method, entries, kind, title, **kwargs):
    return dict(name=name, method=method, entries=entries, kind=kind, title=title, **kwargs)


def tables(records, output):
    fields = ["dataset", "mode", "operation", "kind", "entries", "profile", "payload_bytes",
              "implementation", "mean_ns_op", "error_99_9_ns_op", "alloc_bytes_op", "p50_ns", "p95_ns", "p99_ns"]
    lines = ["# 完整数据汇总", "", "52 个场景的完整原始结果见 raw/。平均模式不输出单次调用分位数；SampleTime 单独列出。", "",
             "误差为 JMH 的 99.9% 置信区间半宽。新种类分配量含计时外的删除准备，不能与已有种类直接比较。", "",
             "| 数据集 | 操作 | 资源 | Key 数 | 实现 | 平均 ns/op ± 误差 | B/op |", "| --- | --- | --- | ---: | --- | ---: | ---: |"]
    with (output / "summary.csv").open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for name, rows in records.items():
            for r in rows:
                p, m = r["params"], r["primaryMetric"]
                operation = r["benchmark"].split(".")[-1]
                alloc = r["secondaryMetrics"].get("gc.alloc.rate.norm", {}).get("score", "")
                row = dict(dataset=name, mode=r["mode"], operation=operation, kind=p["kind"], entries=p["entries"],
                           profile=p["profile"], payload_bytes=p["payloadBytes"], implementation=p["implementation"],
                           mean_ns_op=m["score"], error_99_9_ns_op=m["scoreError"], alloc_bytes_op=alloc)
                if r["mode"] == "sample":
                    row.update(p50_ns=m["scorePercentiles"]["50.0"], p95_ns=m["scorePercentiles"]["95.0"], p99_ns=m["scorePercentiles"]["99.0"])
                writer.writerow(row)
                if r["mode"] == "avgt":
                    lines.append(f"| {name} | {METHODS[operation]} | {p['kind']} | {int(p['entries']):,} | {LABELS[p['implementation']]} | {m['score']:,.2f} ± {m['scoreError']:,.2f} | {alloc:,.2f} |")
    lines += ["", "## 单次插入采样：50,000 个同类物品变体", "", "| 实现 | p50 ns | p95 ns | p99 ns |", "| --- | ---: | ---: | ---: |"]
    for r in records["insert-percentiles"]:
        p = r["primaryMetric"]["scorePercentiles"]
        lines.append(f"| {LABELS[r['params']['implementation']]} | {p['50.0']:,.0f} | {p['95.0']:,.0f} | {p['99.0']:,.0f} |")
    (output / "RESULTS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", action="store_true", help="从 build 复制正式原始结果，默认只从文档归档重绘")
    args = parser.parse_args()
    output = ROOT / "docs/benchmarks/2026-10-01"
    raw, sources = output / "raw", output / "sources"
    raw.mkdir(parents=True, exist_ok=True)
    sources.mkdir(exist_ok=True)
    if args.archive:
        for name in EXPECTED:
            shutil.copyfile(ROOT / f"build/benchmark/{name}.json", raw / f"{name}.json")
        shutil.copyfile(ROOT / "build/benchmark/limits/latest-result.json", raw / "limit-result.json")
        for source in (ROOT / "src/benchmark/java").rglob("*.java"):
            shutil.copyfile(source, sources / source.name)
        shutil.copyfile(ROOT / "build.gradle", sources / "build.gradle")
        production = sources / "production"
        for package in ("disk/core", "disk/persistence"):
            shutil.copytree(ROOT / f"src/main/java/com/huanghuang/rsintegration/{package}", production / package, dirs_exist_ok=True)
        for name in ("StorageIdentityBytes", "StorageItemKey"):
            shutil.copyfile(ROOT / f"src/main/java/com/huanghuang/rsintegration/storage/{name}.java", production / f"{name}.java")
        environment = dict(date="2026-10-01", timezone="Asia/Shanghai", cpu="Intel Core i7-12700H",
                           physical_memory_bytes=16781971456, os="Windows 11", java="17.0.4.1", jmh="1.37",
                           threads=1, forks=2, warmup="3 x 500 ms", measurement="5 x 500 ms", heap="1 GiB / 3 GiB",
                           affinity_controlled=False, power_plan_controlled=False, scope="disk data path; no world leases/network/listeners/UI/IO",
                           queries="256 fixed dispersed keys, cyclic", formal_records=52, smoke_excluded=True)
        (raw / "environment.json").write_text(json.dumps(environment, indent=2) + "\n", encoding="utf-8")
    records = load(raw)
    font = Path("C:/Windows/Fonts/msyh.ttc")
    if font.is_file():
        font_manager.fontManager.addfont(str(font))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["svg.fonttype"] = "none"
    chart(records, output, "overview", "统一盘 vs 原版 RS 无限盘：实测对照", [
        spec("item-variants", method, 50000, "item", f"50,000 同类 NBT Key · {'插入' if method == 'existingInsert' else '提取'}")
        for method in ("existingInsert", "existingExtract")] + [
        spec("plain", method, 1, "item", f"普通无 NBT 物品 · {'插入' if method == 'existingInsert' else '提取'}", divisor=1, unit="ns/op")
        for method in ("existingInsert", "existingExtract")],
        subtitle="上排单位 µs/op，下排单位 ns/op；1 µs = 1,000 ns。面板刻度独立且从 0 开始，误差线为 99.9% 置信区间。")
    chart(records, output, "item-variants", "同类物品 NBT 变体：精确插入与提取", [
        spec("item-variants", method, n, "item", f"{n:,} Key · {METHODS[method]}")
        for n in (1000, 10000, 50000, 262144) for method in ("existingInsert", "existingExtract")])
    chart(records, output, "plain", "普通无 NBT 材料：原版无限盘更快", [
        spec("plain", method, 1, kind, f"{'物品' if kind == 'item' else '流体'} · {METHODS[method]}", divisor=1, unit="ns/op")
        for kind in ("item", "fluid") for method in ("existingInsert", "existingExtract")])
    chart(records, output, "fluid-variants", "同类流体 NBT 变体：精确插入与提取", [
        spec("fluid-variants", method, n, "fluid", f"{n:,} Key · {METHODS[method]}")
        for n in (1000, 262144) for method in ("existingInsert", "existingExtract")])
    chart(records, output, "mixed-and-large-nbt", "类型分散与大 NBT：优势取决于库存分布", [
        spec(name, method, n, "item", f"{label} · {'插入' if method == 'existingInsert' else '提取'}")
        for name, n, label in (("mixed", 50000, "50,000 Key / 512 类型"), ("large-nbt", 1000, "1,000 Key / 4 KiB NBT"), ("large-nbt", 10000, "10,000 Key / 4 KiB NBT"))
        for method in ("existingInsert", "existingExtract")])
    chart(records, output, "new-entry", "已有 50,000 种变体：插入新的身份", [
        spec("new-entry", "newEntryInsert", 50000, kind, "物品" if kind == "item" else "流体") for kind in ("item", "fluid")],
        subtitle="插入前删除上一次新增条目，删除不计入耗时；这个准备步骤会影响缓存热度和 GC 分配指标。")
    chart(records, output, "insert-percentiles", "50,000 个同类物品变体：单次插入延迟", [
        spec("insert-percentiles", "existingInsert", 50000, "item", label, percentile=p)
        for label, p in (("中位数 p50", "50.0"), ("p95", "95.0"), ("p99", "99.0"))],
        subtitle="JMH SampleTime 的单次调用采样分位数；没有用平均模式的迭代分位数替代。各面板刻度独立。")
    tables(records, output)
    shutil.copyfile(Path(__file__), sources / "plot_disk_benchmarks.py")
    (output / "README.md").write_text(
        "# 统一盘与 RS 1.12.4 原版无限盘实测归档\n\n"
        "日期：2026-10-01。52 个正式 JMH 参数组合；smoke 预检数据不参与结论。\n\n"
        "- raw/：8 份完整 JMH JSON（含原始迭代/采样分布）、上限检查及环境。\n"
        "- summary.csv / RESULTS.md：精确数值与完整表格；采样模式单独输出单次分位数。\n"
        "- overview.png：总览；其余 PNG/SVG 按场景展示。面板刻度独立且从 0 开始，越短越快。\n"
        "- sources/：实测时的基准、构建、核心/保存/身份源码及绘图脚本快照。需要完整项目和相同 RS/Forge 依赖才能重跑。\n"
        "- SHA256SUMS.txt：各文件校验和。\n\n"
        "这里只测盘数据路径：统一盘 FrozenKey + core，对照真实 RS 无限盘；未包含挂载、整网、真实监听器、终端或文件保存。\n"
        "同类 NBT 变体多时统一盘明显更快；普通无 NBT 材料原版更快；512 类型分散场景未证明明显优势。\n"
        "每种初始数量 500,000,000；固定 256 个分散 Key 循环查询，不是全表均匀随机访问。\n"
        "上限检查同时装入物品/流体各 262,144 种，每种到 int 上限，保存恢复后逐项校验 PASS。保存/内存数字为单次观察。\n\n"
        "复现参数与范围说明见项目 docs/UNIFIED_DISK_BENCHMARK_2026-10-01.md。\n"
        "基准任务：gradlew diskBenchmark（参数见报告）和 diskBenchmarkSuite；不要同时运行。\n"
        "重绘：python src/benchmark/python/plot_disk_benchmarks.py，Matplotlib 3.10.6。\n",
        encoding="utf-8")
    files = sorted(p for p in output.rglob("*") if p.is_file() and p.name != "SHA256SUMS.txt")
    hashes = "\n".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(output).as_posix()}" for p in files) + "\n"
    (output / "SHA256SUMS.txt").write_text(hashes, encoding="utf-8")
    with zipfile.ZipFile(output.parent / "2026-10-01-disk-benchmarks.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(output))
    print(f"验证并归档 {sum(EXPECTED.values())} 个正式场景；生成 7 组 PNG/SVG。输出：{output}")


if __name__ == "__main__":
    main()
