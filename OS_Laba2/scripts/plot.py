"""Строит графики из CSV, которые пишет Benchmark.java.

    python scripts/plot.py --results results --physical 8 --logical 16

--physical / --logical — число физических ядер и логических процессоров
(Диспетчер задач → Производительность → ЦП). Для них на графиках ускорения
рисуются вертикальные линии. PNG сохраняются в тот же каталог results/.
"""
import argparse
import csv
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import ScalarFormatter  # noqa: E402


def read(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def thread_axis(ax, threads):
    """Ось X — число потоков; логарифмическая по основанию 2, чтобы 4L и 8L не сжимали начало."""
    ax.set_xscale("log", base=2)
    ax.set_xticks(threads)
    ax.get_xaxis().set_major_formatter(ScalarFormatter())
    ax.tick_params(axis="x", labelsize=7, rotation=90)
    ax.set_xlabel("Число потоков")
    ax.grid(True, which="major", alpha=0.3)


def core_lines(ax, args):
    if args.physical:
        ax.axvline(args.physical, color="gray", ls="--", lw=1, label=f"Физических ядер ({args.physical})")
    if args.logical:
        ax.axvline(args.logical, color="black", ls=":", lw=1, label=f"Логических процессоров ({args.logical})")


def save(fig, args, name):
    fig.tight_layout()
    path = os.path.join(args.results, name)
    fig.savefig(path, dpi=150)
    plt.close(fig)
    print("сохранено:", path)


def plot_mul(args):
    rows = read(os.path.join(args.results, "mul.csv"))
    if not rows:
        return
    by_size = defaultdict(list)
    for r in rows:
        by_size[int(r["size"])].append((int(r["threads"]), float(r["mean_s"]),
                                        float(r["speedup"]), float(r["efficiency"])))
    threads = sorted({t for v in by_size.values() for t, *_ in v})

    fig, ax = plt.subplots(figsize=(8, 5))
    for n, v in sorted(by_size.items()):
        v.sort()
        ax.plot([t for t, *_ in v], [x[1] for x in v], marker="o", ms=3, label=f"{n}×{n}")
    core_lines(ax, args)
    thread_axis(ax, threads)
    ax.set_yscale("log")
    ax.set_ylabel("Время умножения, с (лог. шкала)")
    ax.set_title("Время умножения от числа потоков")
    ax.legend()
    save(fig, args, "mul_time.png")

    fig, ax = plt.subplots(figsize=(8, 5))
    for n, v in sorted(by_size.items()):
        ax.plot([t for t, *_ in v], [x[2] for x in v], marker="o", ms=3, label=f"{n}×{n}")
    lim = max(args.logical or 0, max(threads))
    ideal = [t for t in threads if t <= lim]
    ax.plot(ideal, ideal, "k-", lw=0.8, alpha=0.5, label="Идеальное S = T")
    core_lines(ax, args)
    thread_axis(ax, threads)
    top = max(x[2] for v in by_size.values() for x in v)
    ax.set_ylim(0, top * 1.3)
    ax.set_ylabel("Ускорение S(T) = t(1) / t(T)")
    ax.set_title("Ускорение умножения от числа потоков")
    ax.legend()
    save(fig, args, "mul_speedup.png")

    fig, ax = plt.subplots(figsize=(8, 5))
    for n, v in sorted(by_size.items()):
        ax.plot([t for t, *_ in v], [x[3] for x in v], marker="o", ms=3, label=f"{n}×{n}")
    core_lines(ax, args)
    thread_axis(ax, threads)
    ax.set_ylim(0, 1.15)
    ax.set_ylabel("Эффективность E(T) = S(T) / T")
    ax.set_title("Эффективность от числа потоков")
    ax.legend()
    save(fig, args, "mul_efficiency.png")


def plot_order(args):
    rows = read(os.path.join(args.results, "order.csv"))
    if not rows:
        return
    fig, ax = plt.subplots(figsize=(5, 4))
    names = [("i → j → t" if r["order"] == "i-j-t" else "i → t → j") for r in rows]
    vals = [float(r["mean_s"]) for r in rows]
    bars = ax.bar(names, vals, color=["#c44e52", "#4c72b0"][: len(vals)])
    ax.bar_label(bars, fmt="%.2f с")
    ax.set_ylabel("Время, с")
    ax.set_xlabel("Порядок циклов")
    ax.set_title(f"Однопоточное умножение {rows[0]['size']}×{rows[0]['size']}")
    save(fig, args, "order.png")


def plot_tri(args):
    rows = read(os.path.join(args.results, "tri.csv"))
    if not rows:
        return
    data = defaultdict(lambda: defaultdict(list))
    for r in rows:
        data[int(r["size"])][r["mode"]].append((int(r["threads"]), float(r["mean_s"])))
    labels = {"static": "Статическое", "dynamic": "Динамическое (порция 1)",
              "dynamic8": "Динамическое (порция 8)", "dynamic64": "Динамическое (порция 64)"}
    for n, modes in sorted(data.items()):
        fig, ax = plt.subplots(figsize=(8, 5))
        threads = sorted({t for v in modes.values() for t, _ in v})
        for m, v in modes.items():
            v.sort()
            ax.plot([t for t, _ in v], [x for _, x in v], marker="o", ms=3, label=labels.get(m, m))
        core_lines(ax, args)
        thread_axis(ax, threads)
        ax.set_yscale("log")
        ax.set_ylabel("Время умножения, с (лог. шкала)")
        ax.set_title(f"Треугольная A {n}×{n}: статическое и динамическое распределение")
        ax.legend()
        save(fig, args, f"tri_{n}.png")


def plot_ipc(args):
    rows = read(os.path.join(args.results, "ipc.csv"))
    if not rows:
        return
    sizes = [r["size"] for r in rows]
    x = range(len(rows))
    w = 0.38
    fig, ax = plt.subplots(figsize=(7, 4.5))
    b1 = ax.bar([i - w / 2 for i in x], [float(r["transfer_s"]) for r in rows], w, label="Передача через канал")
    b2 = ax.bar([i + w / 2 for i in x], [float(r["multiply_s"]) for r in rows], w,
                label=f"Умножение ({rows[0]['threads']} потоков)")
    ax.bar_label(b1, fmt="%.3f", fontsize=7)
    ax.bar_label(b2, fmt="%.3f", fontsize=7)
    ax.set_xticks(list(x))
    ax.set_xticklabels([f"{s}×{s}\n{int(r['bytes']) / 1e6:.0f} МБ" for s, r in zip(sizes, rows)])
    ax.set_xlabel("Размер матриц (объём передачи)")
    ax.set_ylabel("Время, с")
    ax.set_yscale("log")
    ax.set_title("Время передачи данных и время умножения")
    ax.legend()
    save(fig, args, "ipc.png")


def plot_pow(args):
    rows = read(os.path.join(args.results, "pow.csv"))
    if not rows:
        return
    data = defaultdict(list)
    for r in rows:
        data[r["mode"]].append((int(r["threads"]), float(r["mean_s"]), float(r["speedup"])))
    threads = sorted({t for v in data.values() for t, *_ in v})
    labels = {"barrier": "Потоки один раз + барьер", "respawn": "Новые потоки на каждом шаге"}
    n, p = rows[0]["size"], rows[0]["p"]
    for idx, (ylabel, fname, title) in enumerate([
        ("Время, с", "pow_time.png", "Время возведения в степень"),
        ("Ускорение S(T) = t(1) / t(T)", "pow_speedup.png", "Ускорение возведения в степень"),
    ]):
        fig, ax = plt.subplots(figsize=(8, 5))
        for m, v in data.items():
            v.sort()
            ax.plot([t for t, *_ in v], [x[1 + idx] for x in v], marker="o", ms=3, label=labels.get(m, m))
        if idx == 1:
            lim = max(args.logical or 0, max(threads))
            ideal = [t for t in threads if t <= lim]
            ax.plot(ideal, ideal, "k-", lw=0.8, alpha=0.5, label="Идеальное S = T")
            ax.set_ylim(0, max(x[2] for v in data.values() for x in v) * 1.3)
        core_lines(ax, args)
        thread_axis(ax, threads)
        ax.set_ylabel(ylabel)
        ax.set_title(f"{title}: A^{p}, n = {n}")
        ax.legend()
        save(fig, args, fname)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default="results")
    ap.add_argument("--physical", type=int, default=0, help="число физических ядер")
    ap.add_argument("--logical", type=int, default=0, help="число логических процессоров")
    args = ap.parse_args()
    for f in (plot_mul, plot_order, plot_tri, plot_ipc, plot_pow):
        f(args)


if __name__ == "__main__":
    main()
