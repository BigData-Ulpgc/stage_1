"""Draws the report's benchmark figures and tables from the committed Python CSVs.

Run from python/stage_1/docs/report/:   python charts.py
Figures go to figures/<name>.pdf (vector, for LaTeX) and tables to tables/<name>.tex, both with the
median of the 5 measured runs. A CSV or a value that is missing is skipped with a warning, so the
report still compiles (it shows a \\pending box in its place).
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
RESULTS = HERE.parents[1] / "benchmarks" / "results"
FIGURES = HERE / "figures"
TABLES = HERE / "tables"

# Same palette and markers as the Java report, so the figures of the three reports look alike.
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

DATALAKE = ["book", "range", "time"]
INDEX = ["monolithic", "hierarchical", "mongo"]
SIZES = [50, 100, 200]
META = ["sqlite", "sqlite_no_index"]
META_SIZES = [1000, 10000, 100000]


def warn(what):
    print(f"  [skip] {what}")


def medians(path):
    """(structure, N, metric) -> median of the repetitions in one CSV; {} if the file is missing."""
    if not path.is_file():
        warn(f"missing {path}")
        return {}
    values = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            values[(row["structure"], int(row["dataset_size"]), row["metric"])].append(float(row["value"]))
    return {k: statistics.median(v) for k, v in values.items()}


def python_medians(mode, *experiments):
    merged = {}
    for e in experiments:
        merged |= medians(RESULTS / mode / f"python_{e}.csv")
    return merged


# ------------------------------------------------------------------ figure helpers
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


def lines(ax, data, structures, sizes, metric, title, ylabel, log=False, scale=1.0):
    for s in structures:
        color, marker = STYLE[s]
        ax.plot(sizes, [data[(s, n, metric)] * scale for n in sizes], color=color, marker=marker, label=s)
    if log:
        ax.set_yscale("log")
    ax.set_xticks(sizes)
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    ax.set_xlabel("books (N)")


def save(fig, name):
    fig.tight_layout()
    fig.savefig(FIGURES / f"{name}.pdf")
    plt.close(fig)
    print(f"  figures/{name}.pdf")


def guarded(name, draw):
    """Runs one figure or table; a missing value skips it instead of stopping the script."""
    try:
        draw()
    except (KeyError, ValueError, ZeroDivisionError) as e:
        plt.close("all")
        warn(f"{name}: missing data ({e})")


# ------------------------------------------------------------------ table helpers
def num(v, digits=1):
    if v is None:
        return "---"
    if abs(v) >= 1000 and digits <= 1:
        return f"{v:,.0f}"
    return f"{v:,.{digits}f}"


def write_table(name, header, rows, align):
    """A bare tabular, \\input by report.tex inside its own table environment and caption."""
    lines_ = [f"\\begin{{tabular}}{{@{{}}{align}@{{}}}}", "\\toprule", " & ".join(header) + " \\\\", "\\midrule"]
    for r in rows:
        lines_.append(" & ".join(r) + " \\\\")
    lines_ += ["\\bottomrule", "\\end{tabular}", ""]
    (TABLES / f"{name}.tex").write_text("\n".join(lines_), encoding="utf-8")
    print(f"  tables/{name}.tex")


def code(s):
    return "\\code{" + s.replace("_", "\\_") + "}"


# ------------------------------------------------------------------ datalake
def datalake_figure():
    d = python_medians("real", "datalake_write", "datalake_lookup", "datalake_storage")
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.4))
    bars(axes[0], {s: d[(s, 200, "throughput")] for s in DATALAKE}, DATALAKE, "Write (higher is better)",
         "books / s", lambda v: f"{v:,.0f}")
    bars(axes[1], {s: d[(s, 200, "per_lookup")] for s in DATALAKE}, DATALAKE, "Lookup (lower is better)",
         "µs per book", lambda v: f"{v:.1f}")
    bars(axes[2], {s: d[(s, 200, "max_entries_per_dir")] for s in DATALAKE}, DATALAKE, "Fullest folder",
         "entries", lambda v: f"{v:.0f}")
    save(fig, "datalake")


def datalake_table():
    # "elapsed" appears in several experiments, so each one is read on its own
    storage = python_medians("real", "datalake_storage")
    g = lambda s, m: storage[(s, 200, m)]
    mb = 1_000_000
    write = python_medians("real", "datalake_write")
    lookup = python_medians("real", "datalake_lookup")
    incr = python_medians("real", "datalake_incremental")
    rec = python_medians("real", "datalake_recovery")
    rows = [
        ["Total write (ms)"] + [num(write[(s, 200, "elapsed")]) for s in DATALAKE],
        ["Write (books/s)"] + [num(write[(s, 200, "throughput")], 0) for s in DATALAKE],
        ["Lookup per book (\\us)"] + [num(lookup[(s, 200, "per_lookup")]) for s in DATALAKE],
        ["Detect new books (ms)"] + [num(incr[(s, 200, "elapsed")], 2) for s in DATALAKE],
        ["Recovery (ms)"] + [num(rec[(s, 200, "elapsed")]) for s in DATALAKE],
        ["Recovered / lost / duplicated"] + [" / ".join(f"{rec[(s, 200, m)]:.0f}" for m in ("recovered", "lost", "duplicates")) for s in DATALAKE],
        ["Files"] + [num(g(s, "files"), 0) for s in DATALAKE],
        ["Folders"] + [num(g(s, "directories"), 0) for s in DATALAKE],
        ["Max entries per folder"] + [num(g(s, "max_entries_per_dir"), 0) for s in DATALAKE],
        ["Data (MB)"] + [num(g(s, "bytes") / mb, 1) for s in DATALAKE],
        ["Allocated (MB)"] + [num(g(s, "allocated_bytes") / mb, 1) for s in DATALAKE],
    ]
    write_table("datalake", ["Metric"] + [code(s) for s in DATALAKE], rows, "lrrr")


# ------------------------------------------------------------------ metadata
def metadata_figure():
    d = python_medians("synthetic", "metadata_insert", "metadata_query")
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.5))
    for s in META:
        color, marker = STYLE[s]
        axes[0].plot(META_SIZES, [d[(s, n, "throughput")] / 1000 for n in META_SIZES], color=color, marker=marker, label=s)
    axes[0].set_title("Insert (higher is better)", loc="left")
    axes[0].set_ylabel("thousand rows / s")
    axes[0].set_ylim(bottom=0)
    for ax, metric, title in ((axes[1], "find_by_author_avg", "find_by_author"),
                              (axes[2], "find_by_title_avg", "find_by_title")):
        lines(ax, d, META, META_SIZES, metric, f"{title} (log scale)", "µs per query", log=True)
    for ax in axes:
        ax.set_xscale("log")
        ax.set_xticks(META_SIZES, ["1k", "10k", "100k"])
        ax.set_xlabel("rows (N)")
    axes[0].legend(loc="lower right")
    save(fig, "metadata")


def metadata_table():
    ins = python_medians("synthetic", "metadata_insert")
    q = python_medians("synthetic", "metadata_query")
    rows = []
    for s in META:
        for n in META_SIZES:
            rows.append([code(s), f"{n:,}", num(ins[(s, n, "throughput")], 0),
                         num(q[(s, n, "find_by_id_avg")], 1), num(q[(s, n, "find_by_author_avg")], 1),
                         num(q[(s, n, "find_by_title_avg")], 1)])
    write_table("metadata", ["Structure", "N", "Insert (rows/s)", "by id (\\us)", "by author (\\us)",
                             "by title (\\us)"], rows, "lrrrrr")


# ------------------------------------------------------------------ index
def index_build_query():
    build = python_medians("real", "index_build")
    query = python_medians("real", "index_query")
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.6))
    lines(axes[0], build, INDEX, SIZES, "elapsed", "Build (lower is better)", "seconds", scale=1 / 1000)
    lines(axes[1], query, INDEX, SIZES, "per_query", "AND query (log scale)", "µs per query", log=True)
    axes[0].legend(loc="upper left")
    save(fig, "index_build_query")


def index_update():
    d = python_medians("real", "index_update")
    fig, ax = plt.subplots(figsize=(4.2, 2.6))
    lines(ax, d, INDEX, SIZES, "per_book", "Adding one book (log scale)", "ms per book", log=True)
    ax.set_xlabel("books already indexed (N)")
    ax.legend(loc="best")
    save(fig, "index_update")


def index_disk_memory():
    disk = python_medians("real", "index_disk")
    mem = python_medians("real", "index_memory")
    mb = 1_000_000
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.5))
    bars(axes[0], {s: disk[(s, 200, "bytes")] / mb for s in INDEX}, INDEX, "Data on disk, N = 200",
         "MB", lambda v: f"{v:.1f}")
    bars(axes[1], {s: max(mem[(s, 200, "heap_after_open")], 0) / mb for s in INDEX}, INDEX,
         "Python heap after reopening, N = 200", "MB", lambda v: f"{v:.2f}" if v < 1 else f"{v:.1f}")
    save(fig, "index_disk_memory")


def index_table():
    build = python_medians("real", "index_build")
    query = python_medians("real", "index_query")
    update = python_medians("real", "index_update")
    disk = python_medians("real", "index_disk")
    mem = python_medians("real", "index_memory")
    mb = 1_000_000
    rows = []
    for s in INDEX:
        for n in SIZES:
            rows.append([code(s), str(n), num(build[(s, n, "elapsed")]), num(query[(s, n, "per_query")], 1),
                         num(update[(s, n, "per_book")], 1), num(disk[(s, n, "bytes")] / mb, 2),
                         num(mem[(s, n, "heap_after_build")] / mb, 1), num(mem[(s, n, "heap_after_open")] / mb, 1)])
    write_table("index", ["Structure", "N", "Build (ms)", "Query (\\us)", "Update (ms/book)", "Disk (MB)",
                          "Heap build (MB)", "Heap open (MB)"], rows, "lrrrrrrr")


if __name__ == "__main__":
    FIGURES.mkdir(exist_ok=True)
    TABLES.mkdir(exist_ok=True)
    print(f"Reading {RESULTS}")
    for name, draw in [("datalake figure", datalake_figure), ("datalake table", datalake_table),
                       ("metadata figure", metadata_figure), ("metadata table", metadata_table),
                       ("index_build_query", index_build_query), ("index_update", index_update),
                       ("index_disk_memory", index_disk_memory), ("index table", index_table)]:
        guarded(name, draw)
