"""Draws the report's benchmark figures from the committed C++ result CSVs.

Run from cpp/docs/report/:   uv run --with matplotlib python charts.py
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

# The first three categorical slots of a validated palette (adjacent CVD separation 9.2, normal
# vision 27.6), each series with its own marker so the figures also read in black and white.
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
STYLE = {
    "book": (BLUE, "o"), "range": (ORANGE, "s"), "time": (AQUA, "^"),
    "monolithic": (BLUE, "o"), "hierarchical": (ORANGE, "s"), "mongo": (AQUA, "^"),
    "sqlite": (BLUE, "o"), "sqlite_no_index": (ORANGE, "s"),
}
LAYOUTS = ["book", "range", "time"]
INDEXES = ["monolithic", "hierarchical", "mongo"]
SIZES = [50, 100, 200]
ROWS = [1000, 10000, 100000]

plt.rcParams.update({
    "font.family": "serif", "font.size": 9, "axes.titlesize": 9.5, "axes.labelsize": 9,
    "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.6, "axes.axisbelow": True,
    "legend.frameon": False, "legend.fontsize": 8.5, "lines.linewidth": 2, "lines.markersize": 6,
})


def medians(category, experiment):
    """(structure, N, metric) -> median of the measured repetitions."""
    kind = "synthetic" if category == "metadata" else "real"
    values = defaultdict(list)
    with open(RESULTS / kind / category / f"cpp_{experiment}.csv") as f:
        for row in csv.DictReader(f):
            values[(row["structure"], int(row["dataset_size"]), row["metric"])].append(float(row["value"]))
    return {key: statistics.median(v) for key, v in values.items()}


def bars(ax, values, names, title, ylabel, fmt):
    xs = range(len(names))
    heights = [values[n] for n in names]
    ax.bar(xs, heights, width=0.62, color=[STYLE[n][0] for n in names], edgecolor="white", linewidth=2)
    ax.set_xticks(list(xs), names)
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    ax.grid(axis="x", visible=False)
    for x, v in zip(xs, heights):
        ax.annotate(fmt(v), (x, v), xytext=(0, 3), textcoords="offset points", ha="center", color=INK, fontsize=8.5)
    ax.set_ylim(0, max(heights) * 1.2)


def lines(ax, data, names, sizes, metric, title, ylabel, scale=1.0, log=False):
    for n in names:
        color, marker = STYLE[n]
        ax.plot(sizes, [data[(n, s, metric)] / scale for s in sizes], color=color, marker=marker, label=n)
    if log:
        ax.set_yscale("log")
    ax.set_xticks(sizes)
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)


def save(fig, name):
    fig.tight_layout()
    fig.savefig(OUT / f"{name}.pdf")
    plt.close(fig)


def datalake():
    # Each experiment read on its own: they all name their time metric "elapsed".
    write, lookup = medians("datalake", "datalake_write"), medians("datalake", "datalake_lookup")
    storage = medians("datalake", "datalake_storage")
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.5))
    bars(axes[0], {s: write[(s, 200, "elapsed")] for s in LAYOUTS}, LAYOUTS, "Write 200 books (lower is better)",
         "ms", lambda v: f"{v:.0f}")
    bars(axes[1], {s: lookup[(s, 200, "per_lookup")] for s in LAYOUTS}, LAYOUTS, "Lookup (lower is better)",
         "µs per book", lambda v: f"{v:.2f}")
    axes[1].annotate("in-memory\nmap", (2, lookup[("time", 200, "per_lookup")]), xytext=(0, 16),
                     textcoords="offset points", ha="center", color=MUTED, fontsize=8)
    bars(axes[2], {s: storage[(s, 200, "max_entries_per_dir")] for s in LAYOUTS}, LAYOUTS, "Fullest folder",
         "entries", lambda v: f"{v:.0f}")
    save(fig, "datalake")


def metadata():
    d = medians("metadata", "metadata_insert") | medians("metadata", "metadata_query")
    variants = ["sqlite", "sqlite_no_index"]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.6))
    lines(axes[0], d, variants, ROWS, "throughput", "Insert (higher is better)", "thousand rows / s", scale=1000)
    axes[0].set_ylim(0, 1100)
    lines(axes[1], d, variants, ROWS, "find_by_id_avg", "find_by_id", "µs per query")
    axes[1].set_ylim(0, 14)
    lines(axes[2], d, variants, ROWS, "find_by_author_avg", "find_by_author (log scale)", "µs per query", log=True)
    for ax in axes:
        ax.set_xscale("log")
        ax.set_xticks(ROWS, ["1k", "10k", "100k"])
        ax.set_xlabel("rows (N)")
    axes[0].legend(loc="lower right")
    save(fig, "metadata")


def index_build_query():
    build, query = medians("index", "index_build"), medians("index", "index_query")
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.6))
    lines(axes[0], build, INDEXES, SIZES, "elapsed", "Build (lower is better)", "seconds", scale=1000)
    lines(axes[1], query, INDEXES, SIZES, "per_query", "AND query (log scale)", "µs per query", log=True)
    for ax in axes:
        ax.set_xlabel("books (N)")
    axes[0].legend(loc="upper left")
    save(fig, "index_build_query")


def index_update_memory():
    update, memory = medians("index", "index_update"), medians("index", "index_memory")
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.6))
    lines(axes[0], update, INDEXES, SIZES, "per_book", "Adding one book (lower is better)", "ms per book")
    axes[0].set_ylim(0, 1000)
    axes[0].set_xlabel("books already indexed (N)")
    axes[0].legend(loc="upper left")
    lines(axes[1], memory, INDEXES, SIZES, "heap_after_open", "Memory after opening the index", "MB", scale=1e6)
    axes[1].annotate("hierarchical and mongo: under 0.02 MB", (125, 0), xytext=(0, 10),
                     textcoords="offset points", ha="center", color=MUTED, fontsize=8)
    axes[1].set_xlabel("books (N)")
    save(fig, "index_update_memory")


def index_disk():
    disk = medians("index", "index_disk")
    fig, ax = plt.subplots(figsize=(4.2, 2.5))
    bars(ax, {s: disk[(s, 200, "bytes")] / 1e6 for s in INDEXES}, INDEXES, "Index on disk, N = 200", "MB",
         lambda v: f"{v:.2f}")
    save(fig, "index_disk")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    datalake()
    metadata()
    index_build_query()
    index_update_memory()
    index_disk()
    print("figures written to", OUT)
