"""Draws the report's benchmark figures from the committed CSVs.

Run from java/stage1/docs/report/:   uv run --with matplotlib python charts.py
Each figure is written to figures/<name>.pdf (vector, for LaTeX).
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

RESULTS = Path(__file__).resolve().parents[2] / "benchmarks" / "results"
OUT = Path(__file__).resolve().parent / "figures"

# First three categorical slots of the validated reference palette (all-pairs safe),
# plus a distinct marker per series so the figures also read in black and white.
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
STYLE = {
    "book": (BLUE, "o"), "range": (ORANGE, "s"), "time": (AQUA, "^"),
    "monolithic": (BLUE, "o"), "hierarchical": (ORANGE, "s"), "mongo": (AQUA, "^"),
    "sqlite": (BLUE, "o"), "sqlite_no_index": (ORANGE, "s"),
}

plt.rcParams.update({
    "font.family": "serif", "font.size": 9, "axes.titlesize": 9.5, "axes.labelsize": 9,
    "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.6, "axes.axisbelow": True,
    "legend.frameon": False, "legend.fontsize": 8.5, "lines.linewidth": 2, "lines.markersize": 6,
})


def medians(folder, experiment):
    """(structure, N, metric) -> median of the measured repetitions."""
    values = defaultdict(list)
    with open(RESULTS / folder / f"java_{experiment}.csv") as f:
        for row in csv.DictReader(f):
            values[(row["structure"], int(row["dataset_size"]), row["metric"])].append(float(row["value"]))
    return {k: statistics.median(v) for k, v in values.items()}


def bars(ax, data, structures, title, ylabel, fmt):
    xs = range(len(structures))
    vals = [data[s] for s in structures]
    ax.bar(xs, vals, width=0.62, color=[STYLE[s][0] for s in structures], edgecolor="white", linewidth=2)
    ax.set_xticks(list(xs), structures)
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    ax.grid(axis="x", visible=False)
    for x, v in zip(xs, vals):
        ax.annotate(fmt(v), (x, v), xytext=(0, 3), textcoords="offset points", ha="center", color=INK, fontsize=8.5)
    ax.set_ylim(0, max(vals) * 1.18)


def lines(ax, data, structures, sizes, metric, title, ylabel, log=False):
    for s in structures:
        color, marker = STYLE[s]
        ax.plot(sizes, [data[(s, n, metric)] for n in sizes], color=color, marker=marker, label=s)
    if log:
        ax.set_yscale("log")
    ax.set_xticks(sizes)
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    ax.set_xlabel("books (N)")


def save(fig, name):
    fig.tight_layout()
    fig.savefig(OUT / f"{name}.pdf")
    plt.close(fig)


def datalake():
    d = medians("real", "datalake_write") | medians("real", "datalake_lookup") | medians("real", "datalake_storage")
    st = ["book", "range", "time"]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.4))
    bars(axes[0], {s: d[(s, 200, "throughput")] for s in st}, st, "Write (higher is better)",
         "books / s", lambda v: f"{v:,.0f}")
    bars(axes[1], {s: d[(s, 200, "per_lookup")] for s in st}, st, "Lookup (lower is better)",
         "µs per book", lambda v: f"{v:.1f}")
    bars(axes[2], {s: d[(s, 200, "max_entries_per_dir")] for s in st}, st, "Fullest folder",
         "entries", lambda v: f"{v:.0f}")
    save(fig, "datalake")


def metadata():
    d = medians("synthetic", "metadata_insert") | medians("synthetic", "metadata_query")
    st, sizes = ["sqlite", "sqlite_no_index"], [1000, 10000, 100000]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.5))
    for s in st:
        color, marker = STYLE[s]
        axes[0].plot(sizes, [d[(s, n, "throughput")] / 1000 for n in sizes], color=color, marker=marker, label=s)
    axes[0].set_title("Insert (higher is better)", loc="left")
    axes[0].set_ylabel("thousand rows / s")
    axes[0].set_ylim(0, 65)
    for ax, metric, title in ((axes[1], "find_by_author_avg", "find_by_author"),
                              (axes[2], "find_by_title_avg", "find_by_title")):
        lines(ax, d, st, sizes, metric, f"{title} (log scale)", "µs per query", log=True)
    for ax in axes:
        ax.set_xscale("log")
        ax.set_xticks(sizes, ["1k", "10k", "100k"])
        ax.set_xlabel("rows (N)")
    axes[0].legend(loc="lower right")
    save(fig, "metadata")


INDEX = ["monolithic", "hierarchical", "mongo"]
SIZES = [50, 100, 200]


def index_build_query():
    build, query = medians("real", "index_build"), medians("real", "index_query")
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.6))
    for s in INDEX:
        color, marker = STYLE[s]
        axes[0].plot(SIZES, [build[(s, n, "elapsed")] / 1000 for n in SIZES], color=color, marker=marker, label=s)
    axes[0].set_xticks(SIZES)
    axes[0].set_title("Build (lower is better)", loc="left")
    axes[0].set_ylabel("seconds")
    axes[0].set_xlabel("books (N)")
    lines(axes[1], query, INDEX, SIZES, "per_query", "AND query (log scale)", "µs per query", log=True)
    axes[0].legend(loc="upper left")
    save(fig, "index_build_query")


def index_update():
    d = medians("real", "index_update")
    fig, ax = plt.subplots(figsize=(4.2, 2.6))
    for s in INDEX:
        color, marker = STYLE[s]
        ax.plot(SIZES, [d[(s, n, "per_book")] for n in SIZES], color=color, marker=marker, label=s)
    ax.set_yscale("log")
    ax.set_xticks(SIZES)
    ax.set_title("Adding one book (log scale)", loc="left")
    ax.set_ylabel("ms per book")
    ax.set_xlabel("books already indexed (N)")
    ax.legend(loc="center right")
    save(fig, "index_update")


def index_disk_memory():
    disk = medians("real", "index_disk")
    mem = medians("real", "index_memory")
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.5))
    mb = 1_000_000                                   # decimal MB, as in the report
    bars(axes[0], {s: disk[(s, 200, "bytes")] / mb for s in INDEX}, INDEX, "Data on disk, N = 200",
         "MB", lambda v: f"{v:.1f}")
    alloc = disk[("hierarchical", 200, "allocated_bytes")] / mb
    axes[0].annotate(f"{alloc:.0f} MB of\nallocated blocks", (1, disk[("hierarchical", 200, "bytes")] / mb),
                     xytext=(0, 18), textcoords="offset points", ha="center", color=MUTED, fontsize=8)
    axes[0].set_ylim(0, 19)
    bars(axes[1], {s: mem[(s, 200, "heap_after_open")] / mb for s in INDEX}, INDEX, "Java heap after reopening, N = 200",
         "MB", lambda v: f"{v:.2f}" if v < 1 else f"{v:.1f}")
    save(fig, "index_disk_memory")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    datalake()
    metadata()
    index_build_query()
    index_update()
    index_disk_memory()
    print("figures written to", OUT)
