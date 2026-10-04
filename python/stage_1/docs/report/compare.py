"""Compares the Java, Python and C++ benchmark results (median of the 5 measured runs).

Run from python/stage_1/docs/report/:   python compare.py
Reads the committed CSVs of the three implementations:
    java/stage1/benchmarks/results/{real,synthetic}/java_*.csv
    cpp/benchmarks/results/real/{datalake,index}/cpp_*.csv, cpp/benchmarks/results/synthetic/metadata/cpp_*.csv
    python/stage_1/benchmarks/results/{real,synthetic}/python_*.csv
and writes figures/lang_datalake.pdf, figures/lang_index.pdf, figures/lang_metadata.pdf and
tables/lang_summary.tex. A missing language or value is skipped with a warning.
"""
import matplotlib.pyplot as plt

from charts import (AQUA, BLUE, FIGURES, INK, ORANGE, TABLES, code, guarded, medians, num, save, warn,
                    write_table)
from charts import HERE

REPO = HERE.parents[3]
LANGS = ["java", "python", "cpp"]
LABEL = {"java": "Java", "python": "Python", "cpp": "C++"}
COLOR = {"java": BLUE, "python": ORANGE, "cpp": AQUA}

DATALAKE_EXPERIMENTS = ["datalake_write", "datalake_lookup", "datalake_incremental", "datalake_recovery",
                        "datalake_storage"]
INDEX_EXPERIMENTS = ["index_build", "index_query", "index_update", "index_memory", "index_disk"]
METADATA_EXPERIMENTS = ["metadata_insert", "metadata_query"]


def csv_path(lang, experiment):
    """Where each implementation keeps the CSV of one experiment (SPEC section 10.4)."""
    synthetic = experiment in METADATA_EXPERIMENTS
    mode = "synthetic" if synthetic else "real"
    if lang == "java":
        return REPO / "java" / "stage1" / "benchmarks" / "results" / mode / f"java_{experiment}.csv"
    if lang == "python":
        return REPO / "python" / "stage_1" / "benchmarks" / "results" / mode / f"python_{experiment}.csv"
    group = "metadata" if synthetic else ("datalake" if experiment.startswith("datalake") else "index")
    return REPO / "cpp" / "benchmarks" / "results" / mode / group / f"cpp_{experiment}.csv"


def load():
    """lang -> experiment -> {(structure, N, metric): median}. Values such as 249.210 parse as floats."""
    return {lang: {e: medians(csv_path(lang, e))
                   for e in DATALAKE_EXPERIMENTS + INDEX_EXPERIMENTS + METADATA_EXPERIMENTS}
            for lang in LANGS}


def value(data, lang, experiment, structure, n, metric):
    return data[lang][experiment].get((structure, n, metric))


def grouped(ax, data, experiment, structures, n, metric, title, ylabel, log=False, scale=1.0):
    """Bars grouped by structure, one colour per language; a missing language leaves a gap."""
    width = 0.26
    drawn = False
    for i, lang in enumerate(LANGS):
        xs, ys = [], []
        for j, s in enumerate(structures):
            v = value(data, lang, experiment, s, n, metric)
            if v is not None:
                xs.append(j + (i - 1) * width)
                ys.append(v * scale)
        if xs:
            ax.bar(xs, ys, width=width, color=COLOR[lang], edgecolor="white", linewidth=1, label=LABEL[lang])
            drawn = True
    if not drawn:
        raise KeyError(f"{experiment} {metric}")
    ax.set_xticks(range(len(structures)), structures, rotation=20, ha="right")
    ax.set_title(title, loc="left", color=INK)
    ax.set_ylabel(ylabel)
    ax.grid(axis="x", visible=False)
    if log:
        ax.set_yscale("log")


def lang_datalake(data):
    st = ["book", "range", "time"]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.6))
    grouped(axes[0], data, "datalake_write", st, 200, "throughput", "Write (higher is better)", "books / s")
    grouped(axes[1], data, "datalake_lookup", st, 200, "per_lookup", "Lookup (log scale)", "µs per book", log=True)
    grouped(axes[2], data, "datalake_recovery", st, 200, "elapsed", "Recovery of 20 books", "ms")
    axes[0].legend(loc="lower left", fontsize=7.5)
    save(fig, "lang_datalake")


def lang_index(data):
    st = ["monolithic", "hierarchical", "mongo"]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.6))
    grouped(axes[0], data, "index_build", st, 200, "elapsed", "Build (log scale)", "seconds", log=True, scale=1 / 1000)
    grouped(axes[1], data, "index_query", st, 200, "per_query", "AND query (log scale)", "µs per query", log=True)
    grouped(axes[2], data, "index_update", st, 200, "per_book", "Add one book (log scale)", "ms per book", log=True)
    for ax in axes:
        ax.tick_params(axis="x", labelsize=7.5)
    axes[0].legend(loc="upper left", fontsize=7.5)
    save(fig, "lang_index")


def lang_metadata(data):
    st = ["sqlite", "sqlite_no_index"]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 2.6))
    grouped(axes[0], data, "metadata_insert", st, 100000, "throughput", "Insert (higher is better)", "thousand rows / s",
            scale=1 / 1000)
    grouped(axes[1], data, "metadata_query", st, 100000, "find_by_id_avg", "find_by_id", "µs per query")
    grouped(axes[2], data, "metadata_query", st, 100000, "find_by_author_avg", "find_by_author (log scale)",
            "µs per query", log=True)
    axes[0].legend(loc="upper left", fontsize=7.5)
    save(fig, "lang_metadata")


SUMMARY = [
    # (label, experiment, structure, N, metric, scale, digits)
    ("Datalake write, \\code{book} (books/s)", "datalake_write", "book", 200, "throughput", 1, 0),
    ("Datalake lookup, \\code{book} (\\us/book)", "datalake_lookup", "book", 200, "per_lookup", 1, 1),
    ("Datalake lookup, \\code{time} (\\us/book)", "datalake_lookup", "time", 200, "per_lookup", 1, 1),
    ("Detect new books, \\code{book} (ms)", "datalake_incremental", "book", 200, "elapsed", 1, 2),
    ("Recovery, \\code{book} (ms)", "datalake_recovery", "book", 200, "elapsed", 1, 1),
    ("Index build, \\code{monolithic}, N = 200 (ms)", "index_build", "monolithic", 200, "elapsed", 1, 0),
    ("Index build, \\code{hierarchical}, N = 200 (ms)", "index_build", "hierarchical", 200, "elapsed", 1, 0),
    ("Index build, \\code{mongo}, N = 200 (ms)", "index_build", "mongo", 200, "elapsed", 1, 0),
    ("AND query, \\code{monolithic}, N = 200 (\\us)", "index_query", "monolithic", 200, "per_query", 1, 1),
    ("AND query, \\code{hierarchical}, N = 200 (\\us)", "index_query", "hierarchical", 200, "per_query", 1, 1),
    ("AND query, \\code{mongo}, N = 200 (\\us)", "index_query", "mongo", 200, "per_query", 1, 1),
    ("Update, \\code{monolithic}, N = 200 (ms/book)", "index_update", "monolithic", 200, "per_book", 1, 1),
    ("Update, \\code{hierarchical}, N = 200 (ms/book)", "index_update", "hierarchical", 200, "per_book", 1, 1),
    ("Update, \\code{mongo}, N = 200 (ms/book)", "index_update", "mongo", 200, "per_book", 1, 1),
    ("Monolithic JSON, N = 200 (bytes)", "index_disk", "monolithic", 200, "bytes", 1, 0),
    ("Index terms, N = 200", "index_disk", "monolithic", 200, "terms", 1, 0),
    ("Metadata insert, \\code{sqlite}, N = 100k (rows/s)", "metadata_insert", "sqlite", 100000, "throughput", 1, 0),
    ("find\\_by\\_author, \\code{sqlite}, N = 100k (\\us)", "metadata_query", "sqlite", 100000, "find_by_author_avg", 1, 1),
    ("find\\_by\\_author, \\code{sqlite\\_no\\_index}, N = 100k (\\us)", "metadata_query", "sqlite_no_index", 100000,
     "find_by_author_avg", 1, 1),
]


def lang_summary(data):
    rows = []
    for label, e, s, n, m, scale, digits in SUMMARY:
        cells = []
        for lang in LANGS:
            v = value(data, lang, e, s, n, m)
            cells.append(num(v * scale, digits) if v is not None else "---")
        rows.append([label] + cells)
    write_table("lang_summary", ["Median of 5 runs"] + [LABEL[l] for l in LANGS], rows, "lrrr")


if __name__ == "__main__":
    FIGURES.mkdir(exist_ok=True)
    TABLES.mkdir(exist_ok=True)
    data = load()
    for lang in LANGS:
        found = sum(1 for rows in data[lang].values() if rows)
        print(f"  {LABEL[lang]}: {found} of 12 experiments")
    for name, draw in [("lang_datalake", lang_datalake), ("lang_index", lang_index),
                       ("lang_metadata", lang_metadata), ("lang_summary", lang_summary)]:
        guarded(name, lambda d=draw: d(data))
