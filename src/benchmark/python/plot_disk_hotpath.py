"""从已归档数据绘制当前盘与 RS 的横向柱状图；不显示优化前或硬件配置。"""
import hashlib
import json
import math
import shutil
from pathlib import Path

import plot_disk_benchmarks as base
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.ticker import MaxNLocator

OUTPUT = base.ROOT / "docs/benchmarks/2026-10-02-hotpath"


def load():
    records = {}
    for name, count in (("plain", 8), ("tagged", 4), ("fluid", 4), ("large", 8)):
        rows = json.loads((OUTPUT / f"raw/2026-10-02-after-{name}.json").read_text(encoding="utf-8"))
        assert len(rows) == count, (name, len(rows))
        for row in rows:
            metric = row["primaryMetric"]
            assert metric["scoreUnit"] == "ns/op" and math.isfinite(metric["score"])
            assert math.isfinite(metric["scoreError"]) and metric["scoreError"] >= 0
            assert row["mode"] == "avgt" and row["forks"] == 2 and row["measurementIterations"] == 5
            assert len(metric["rawData"]) == 2 and all(len(fork) == 5 for fork in metric["rawData"])
            key = (name, row["benchmark"].rsplit(".", 1)[1], row["params"]["kind"], row["params"]["implementation"])
            assert key not in records
            records[key] = row
    return records


def panel(ax, records, dataset, operation, kind, title, divisor):
    rows = [records[dataset, operation, kind, impl] for impl in ("unifiedKeyPath", "rs")]
    scores = [row["primaryMetric"]["score"] / divisor for row in rows]
    errors = [row["primaryMetric"]["scoreError"] / divisor for row in rows]
    limit = max(value + error for value, error in zip(scores, errors)) * 1.4
    for y, (value, error, color) in enumerate(zip(scores, errors, ("#138A83", "#D47538"))):
        ax.barh(y, value, height=.40, color=color, zorder=3)
        ax.errorbar(value, y, xerr=error, fmt="none", color="#344454", capsize=3, linewidth=1, zorder=4)
        ax.text(value + error + limit * .02, y, f"{value:,.2f}", va="center", fontsize=11, color="#233346")
    ax.set_yticks([0, 1], ["万界归墟盘", "RS 无限盘"])
    ax.set_ylim(1.6, -.6)
    ax.set_xlim(0, limit)
    ax.set_title(title, loc="left", fontsize=12, pad=12, fontweight="bold")
    unit = "ns/op（纳秒）" if divisor == 1 else "µs/op（微秒）"
    ax.set_xlabel(unit + " · 越短越快", fontsize=10, color="#526274")
    ax.xaxis.set_major_locator(MaxNLocator(nbins=4))
    ax.grid(axis="x", color="#E5EAF0")
    ax.set_axisbelow(True)
    ax.tick_params(length=0, labelsize=9, colors="#526274")
    for spine in ax.spines.values():
        spine.set_visible(False)


def chart(records, stem, title, specs, divisor, note=None):
    fig, axes = plt.subplots(2, 2, figsize=(13.4, 8), squeeze=False)
    fig.set_facecolor("#FAFCFE")
    for ax, spec in zip(axes.flat, specs):
        panel(ax, records, *spec, divisor)
    fig.suptitle(title, x=.05, y=.98, ha="left", fontsize=19, fontweight="bold", color="#162B40")
    fig.text(.05, .918, "同批实测；各面板独立线性刻度，从 0 开始。误差线为 JMH 99.9% 置信区间。", fontsize=10, color="#526274")
    fig.text(.05, .028, "已有种类的单次插入 / 提取；提取包含返回副本。单线程 JMH · 2 forks × 5 次测量 · 2026-10-02\n"
             + (note or "盘数据路径，未包含实际挂载、网络回调、终端及文件读写。"), fontsize=9, color="#526274")
    fig.subplots_adjust(left=.14, right=.97, top=.835, bottom=.155, hspace=.88, wspace=.54)
    for ext in ("png", "svg"):
        fig.savefig(OUTPUT / f"{stem}.{ext}", dpi=160, facecolor=fig.get_facecolor())
    plt.close(fig)


def main():
    records = load()
    font = Path("C:/Windows/Fonts/msyh.ttc")
    font_manager.fontManager.addfont(str(font))
    plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["svg.fonttype"] = "none"
    methods = (("existingInsert", "插入"), ("existingExtract", "提取"))
    chart(records, "plain-vs-rs", "普通资源：万界归墟盘 vs RS 原版无限盘", [
        ("plain", method, kind, f"普通{'物品' if kind == 'item' else '流体'} · {label}（1 个 Key）")
        for kind in ("item", "fluid") for method, label in methods], 1)
    chart(records, "tagged-vs-rs", "NBT 资源：万界归墟盘 vs RS 原版无限盘", [
        (dataset, method, kind, f"{title} · {label}")
        for dataset, kind, title in (("tagged", "item", "50,000 物品 Key / 512 类型"),
                                     ("fluid", "fluid", "1,000 同类流体 Key"))
        for method, label in methods], 1000)
    chart(records, "large-vs-rs", "大容量 NBT：万界归墟盘 vs RS 原版无限盘", [
        ("large", method, kind, f"262,144 同类{'物品' if kind == 'item' else '流体'} NBT Key · {label}")
        for kind in ("item", "fluid") for method, label in methods], 1000,
        "盘数据路径；256 个分散查询循环。同一注册类型的大量 NBT 变体，不代表日常混合材料库存。")
    shutil.copyfile(Path(__file__), OUTPUT / "sources" / Path(__file__).name)
    # 图和报告更新后重建清单，不改变已归档的原始基准。
    paths = sorted(path for path in OUTPUT.rglob("*") if path.is_file() and path.name != "SHA256SUMS.txt")
    lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(OUTPUT).as_posix()}" for path in paths]
    (OUTPUT / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("已生成普通资源、NBT 资源、大容量 NBT 三张对照图（PNG/SVG），仅使用本轮 after 原始数据。")


if __name__ == "__main__":
    main()
