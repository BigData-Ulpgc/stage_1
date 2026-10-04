"""Draws the figures of the group's main report from the committed CSVs of the three languages.

Run from docs/report/:   uv run --with matplotlib python charts.py
Reads the results of each implementation (SPEC section 10.4):
    java/stage1/benchmarks/results/{real,synthetic}/java_*.csv
    python/stage_1/benchmarks/results/{real,synthetic}/python_*.csv
    cpp/benchmarks/results/real/{datalake,index}/cpp_*.csv, cpp/benchmarks/results/synthetic/metadata/cpp_*.csv
and writes figures/<name>.pdf with the median of the 5 measured runs.
"""
import csv
import statistics
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
FIGURES = HERE / "figures"

LANGS = ["java", "python", "cpp"]
LABEL = {"java": "Java", "python": "Python", "cpp": "C++"}
# Same palette as the three module reports.
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
COLOR = {"java": BLUE, "python": ORANGE, "cpp": AQUA}
MARKER = {"java": "o", "python": "s", "cpp": "^"}

DATALAKE = ["book", "range", "time"]
INDEX = ["monolithic", "hierarchical", "mongo"]
SIZES = [50, 100, 200]
METADATA_EXPERIMENTS = ["metadata_insert", "metadata_query"]
EXPERIMENTS = ["datalake_write", "datalake_lookup", "datalake_incremental", "datalake_recovery",
               "index_build", "index_query", "index_update", "index_memory", "index_disk"] + METADATA_EXPERIMENTS

plt.rcParams.update({
    "font.family": "serif", "font.size": 9, "axes.titlesize": 9.5, "axes.labelsize": 9,
    "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.6, "axes.axisbelow": True,
    "legend.frameon": False, "legend.fontsize": 8, "lines.linewidth": 2, "lines.markersize": 5.5,
})


def csv_path(lang, experiment):
    mode = "synthetic" if experiment in METADATA_EXPERIMENTS else "real"
    if lang == "java":
        return REPO / "java" / "stage1" / "benchmarks" / "results" / mode / f"java_{experiment}.csv"
    if lang == "python":
        return REPO / "python" / "stage_1" / "benchmarks" / "results" / mode / f"python_{experiment}.csv"
    group = "metadata" if mode == "synthetic" else experiment.split("_")[0]
    return REPO / "cpp" / "benchmarks" / "results" / mode / group / f"cpp_{experiment}.csv"


def load():
    """(lang, experiment, structure, N, metric) -> median of the measured runs."""
    values = defaultdict(list)
    for lang in LANGS:
        for e in EXPERIMENTS:
            with open(csv_path(lang, e), encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    key = (lang, e, row["structure"], int(row["dataset_size"]), row["metric"])
                    values[key].append(float(row["value"]))
    return {k: statistics.median(v) for k, v in values.items()}


def legend_on_top(fig, ax):
    """One legend for the whole figure, above the panels, so it never covers a bar."""
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=len(labels), bbox_to_anchor=(0.5, 1.07), fontsize=8)


def save(fig, name):
    fig.tight_layout()
    fig.savefig(FIGURES / f"{name}.pdf", bbox_inches="tight")
    plt.close(fig)
    print(f"  figures/{name}.pdf")


def grouped(ax, data, experiment, structures, n, metric, title, ylabel, log=False, scale=1.0, hatch_cpp=False):
    """Bars grouped by structure, one colour per language. hatch_cpp marks C++ bars measured on macOS."""
    width = 0.26
    for i, lang in enumerate(LANGS):
        xs = [j + (i - 1) * width for j in range(len(structures))]
        ys = [data[(lang, experiment, s, n, metric)] * scale for s in structures]
        hatch = "////" if hatch_cpp and lang == "cpp" else None
        ax.bar(xs, ys, width=width, color=COLOR[lang], edgecolor="white", linewidth=0.8, hatch=hatch,
               label=LABEL[lang] + (" (macOS)" if hatch else ""))
    ax.set_xticks(range(len(structures)), structures)
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    ax.grid(axis="x", visible=False)
    if log:
        ax.set_yscale("log")


def lines(ax, data, experiment, structure, metric, title, ylabel, log=False, scale=1.0):
    """One line per language as N grows."""
    for lang in LANGS:
        ys = [data[(lang, experiment, structure, n, metric)] * scale for n in SIZES]
        ax.plot(SIZES, ys, color=COLOR[lang], marker=MARKER[lang], label=LABEL[lang])
    ax.set_xticks(SIZES)
    ax.set_xlabel("books (N)")
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    if log:
        ax.set_yscale("log")


def lang_datalake(data):
    fig, axes = plt.subplots(1, 3, figsize=(7.4, 2.7))
    grouped(axes[0], data, "datalake_write", DATALAKE, 200, "throughput", "Write (higher is better)", "books / s",
            hatch_cpp=True)
    grouped(axes[1], data, "datalake_lookup", DATALAKE, 200, "per_lookup", "Lookup (log scale)", "µs per book",
            log=True, hatch_cpp=True)
    grouped(axes[2], data, "datalake_incremental", DATALAKE, 200, "elapsed", "Detect 20 new books", "ms",
            hatch_cpp=True)
    legend_on_top(fig, axes[0])
    save(fig, "lang_datalake")


def lang_index(data):
    fig, axes = plt.subplots(1, 3, figsize=(7.4, 2.7))
    grouped(axes[0], data, "index_build", INDEX, 200, "elapsed", "Build, N = 200", "seconds", scale=1 / 1000)
    grouped(axes[1], data, "index_query", INDEX, 200, "per_query", "AND query (log scale)", "µs per query", log=True)
    grouped(axes[2], data, "index_update", INDEX, 200, "per_book", "Add one book (log scale)", "ms per book",
            log=True)
    for ax in axes:
        ax.tick_params(axis="x", labelsize=7.5, labelrotation=20)
    legend_on_top(fig, axes[0])
    save(fig, "lang_index")


def lang_scaling(data):
    """How the monolithic index, the structure the three languages chose, scales with N."""
    fig, axes = plt.subplots(1, 3, figsize=(7.4, 2.6))
    lines(axes[0], data, "index_query", "monolithic", "per_query", "AND query", "µs per query")
    lines(axes[1], data, "index_update", "monolithic", "per_book", "Add one book", "ms per book")
    lines(axes[2], data, "index_memory", "monolithic", "heap_after_open", "Memory after reopening", "MB",
          scale=1 / 1e6)
    legend_on_top(fig, axes[0])
    save(fig, "lang_scaling")


def lang_metadata(data):
    fig, axes = plt.subplots(1, 3, figsize=(7.4, 2.7))
    grouped(axes[0], data, "metadata_insert", ["sqlite", "sqlite_no_index"], 100000, "throughput",
            "Insert (log scale)", "rows / s", log=True, hatch_cpp=True)
    grouped(axes[1], data, "metadata_query", ["sqlite", "sqlite_no_index"], 100000, "find_by_id_avg",
            "find_by_id", "µs per query", hatch_cpp=True)
    grouped(axes[2], data, "metadata_query", ["sqlite", "sqlite_no_index"], 100000, "find_by_author_avg",
            "find_by_author (log scale)", "µs per query", log=True, hatch_cpp=True)
    for ax in axes:
        ax.tick_params(axis="x", labelsize=7.5)
    legend_on_top(fig, axes[0])
    save(fig, "lang_metadata")


# (label, experiment, structure, N, metric, higher_is_better)
RATIOS = [
    ("AND query, monolithic", "index_query", "monolithic", 200, "per_query", False),
    ("AND query, hierarchical", "index_query", "hierarchical", 200, "per_query", False),
    ("AND query, mongo", "index_query", "mongo", 200, "per_query", False),
    ("Build, monolithic", "index_build", "monolithic", 200, "elapsed", False),
    ("Build, hierarchical", "index_build", "hierarchical", 200, "elapsed", False),
    ("Build, mongo", "index_build", "mongo", 200, "elapsed", False),
    ("Add a book, monolithic", "index_update", "monolithic", 200, "per_book", False),
    ("Add a book, hierarchical", "index_update", "hierarchical", 200, "per_book", False),
    ("Add a book, mongo", "index_update", "mongo", 200, "per_book", False),
    ("Memory, monolithic open", "index_memory", "monolithic", 200, "heap_after_open", False),
]


def lang_ratios(data):
    """Each language's cost relative to the fastest one, for the experiments run on the same Linux machine."""
    fig, ax = plt.subplots(figsize=(7.4, 3.3))
    labels = [r[0] for r in RATIOS]
    height = 0.26
    for i, lang in enumerate(LANGS):
        ys, xs = [], []
        for j, (_, e, s, n, m, _) in enumerate(RATIOS):
            best = min(data[(l, e, s, n, m)] for l in LANGS)
            xs.append(data[(lang, e, s, n, m)] / best)
            ys.append(j + (i - 1) * height)
        ax.barh(ys, xs, height=height, color=COLOR[lang], edgecolor="white", linewidth=0.6, label=LABEL[lang])
    ax.set_yticks(range(len(labels)), labels)
    ax.invert_yaxis()
    ax.set_xscale("log")
    ax.set_xlim(left=0.8)   # so a ratio of exactly 1 (the fastest language) still shows a short bar
    ax.axvline(1, color=MUTED, linewidth=0.8)
    ax.set_xlabel("times the fastest language (1 = fastest, log scale)")
    ax.grid(axis="y", visible=False)
    ax.legend(loc="lower right", fontsize=7.5)
    save(fig, "lang_ratios")


if __name__ == "__main__":
    FIGURES.mkdir(exist_ok=True)
    medians = load()
    lang_datalake(medians)
    lang_index(medians)
    lang_scaling(medians)
    lang_metadata(medians)
    lang_ratios(medians)
