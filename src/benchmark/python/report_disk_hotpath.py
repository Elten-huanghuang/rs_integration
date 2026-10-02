"""归档本轮热路径基准；保留前后原始数据，生成逐场景对照表。"""
import csv
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs/benchmarks/2026-10-02-hotpath"
FILES = [f"2026-10-02-{phase}-{name}" for phase in ("before", "after")
         for name in ("plain", "tagged", "fluid")]
CORE = [f"src/main/java/com/huanghuang/rsintegration/disk/core/{name}.java"
        for name in ("ResourceTable", "FrozenKey", "NbtIdentity")]


def load(phase):
    result = {}
    for name in ("plain", "tagged", "fluid"):
        for row in json.loads((OUT / f"raw/2026-10-02-{phase}-{name}.json").read_text()):
            params = row["params"]
            key = (name, row["benchmark"].rsplit(".", 1)[1], params["kind"], params["implementation"])
            assert key not in result
            result[key] = row
    assert len(result) == 16, "正式结果应包含 16 组，不能漏掉失败项"
    return result


def main():
    raw = OUT / "raw"
    if raw.exists():
        raise SystemExit("归档已存在，拒绝覆盖原始数据")
    raw.mkdir(parents=True)
    for name in FILES:
        for suffix in ("json", "log"):
            shutil.copyfile(ROOT / f"build/benchmark/{name}.{suffix}", raw / f"{name}.{suffix}")
    shutil.copyfile(ROOT / "build/benchmark/2026-10-02-tests.log", raw / "tests.log")
    sources = OUT / "sources"
    for path in CORE:
        old = sources / "before" / path
        new = sources / "after" / path
        old.parent.mkdir(parents=True, exist_ok=True)
        new.parent.mkdir(parents=True, exist_ok=True)
        old.write_bytes(subprocess.check_output(["git", "show", f"203278d:{path}"], cwd=ROOT))
        shutil.copyfile(ROOT / path, new)
    shutil.copyfile(Path(__file__), sources / Path(__file__).name)
    for folder in ("src/benchmark/java", "src/test/java/com/huanghuang/rsintegration/disk"):
        shutil.copytree(ROOT / folder, sources / folder)
    before, after = load("before"), load("after")
    rows = []
    for name, operation, kind, implementation in before:
        if implementation != "unifiedKeyPath":
            continue
        old = before[name, operation, kind, implementation]
        new = after[name, operation, kind, implementation]
        rs = after[name, operation, kind, "rs"]
        def score(row):
            assert row["primaryMetric"]["scoreUnit"] == "ns/op"
            return row["primaryMetric"]["score"]
        rows.append(dict(dataset=name, operation=operation, kind=kind,
                         before_ns=score(old), before_error=old["primaryMetric"]["scoreError"],
                         after_ns=score(new), after_error=new["primaryMetric"]["scoreError"],
                         rs_ns=score(rs), rs_error=rs["primaryMetric"]["scoreError"],
                         reduction_percent=100 * (1 - score(new) / score(old)),
                         before_bytes=old["secondaryMetrics"]["gc.alloc.rate.norm"]["score"],
                         after_bytes=new["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]))
    with (OUT / "summary.csv").open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    lines = ["# 磁盘热路径优化对照（2026-10-02）", "",
             "单项库存仍为 int。盘汇总复用已有 long 展示统计，并在 RS 接口截断；移除两张总量树，最大盘减少约 4 MiB int 数组。",
             "NBT 查询直接冻结 tag，按与完整保存载荷相同的规则计算身份，不创建外层 Compound。能力物品保留完整复制归一化路径。",
             "", "每组 2 forks、3 次预热、5 次测量，每次 500 ms，单线程、GC profiler；JVM 信息在原始日志。",
             "普通场景为单 Key；物品 NBT 场景为 50,000 Key/512 注册类型；流体为 1,000 同类 Key。",
             "优化前 tagged 日志包含错误的 fluid/mixed 尝试，该组合无结果，不参与比较；有效流体结果来自独立 fluid 批次。",
             "以下是盘核心接收 Stack 的耗时，提取包含返回副本；不包含实际网络、租约验证、终端与游戏 MSPT。",
             "", "| 场景 | 操作 | 优化前 ns | 优化后 ns | 同批 RS ns | 耗时减少 | 分配 B/op 前→后 |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for row in rows:
        name = {"plain": "普通", "tagged": "5 万物品 NBT", "fluid": "千种流体 NBT"}[row["dataset"]]
        kind = "物品" if row["kind"] == "item" else "流体"
        operation = "插入" if row["operation"] == "existingInsert" else "提取"
        lines.append(f"| {name}/{kind} | {operation} | {row['before_ns']:.2f} | {row['after_ns']:.2f} | {row['rs_ns']:.2f} | {row['reduction_percent']:.1f}% | {row['before_bytes']:.1f}→{row['after_bytes']:.1f} |")
    lines += ["", "误差与未舍入数值见 summary.csv；所有 fork/迭代原始数据、日志和源码保留。跨批次波动不能解释为普遍性能保证。",
              "本轮相关回归测试 99 项通过，涵盖饱和减量、保存恢复、能力变化、NBT 顺序、空/无 tag、数组修改与哈希碰撞。",
              "", "源码归档的优化前基于本地提交 203278d；用户无关配置和绘图文件删除不包含在性能修改中。", ""]
    (OUT / "README.md").write_text("\n".join(lines), encoding="utf-8")
    entries = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(OUT).as_posix()}"
               for path in sorted(OUT.rglob("*")) if path.is_file()]
    (OUT / "SHA256SUMS.txt").write_text("\n".join(entries) + "\n", encoding="utf-8")
    print(json.dumps(rows, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
