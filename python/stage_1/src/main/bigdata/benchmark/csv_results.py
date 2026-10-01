"""
Benchmark Utility — CSV Results Writer
=======================================
Responsible exclusively for formatting and writing the benchmark
CSV files to the output directory ``python/stage_1/benchmarks/results/``.

This module exposes:

* ``RESULTS_DIR`` — resolved :class:`~pathlib.Path` to the output folder.
* ``save_csv()``  — writes a single CSV file with standard headers and rows.
"""

import csv
from pathlib import Path

# ---------------------------------------------------------------------------
# Output directory — python/stage_1/benchmarks/results/
# ---------------------------------------------------------------------------
_BENCHMARK_PKG_DIR: Path = Path(__file__).resolve().parent
_PYTHON_PROJECT_ROOT: Path = _BENCHMARK_PKG_DIR.parents[3]  # python/stage_1/

RESULTS_DIR: Path = _PYTHON_PROJECT_ROOT / "benchmarks" / "results"
RESULTS_DIR.mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

CSV_HEADERS = [
    "language", "experiment", "structure", "dataset_size",
    "repetition", "metric", "value", "unit"
]

def save_csv(filename: str, rows: list[list]) -> Path:
    """
    Write a CSV file with the standard headers and data rows.

    The file is created (or overwritten) inside :data:`RESULTS_DIR`.

    Args:
        filename: Target CSV file name (e.g. ``"python_datalake_write.csv"``).
        rows:     List of row-lists, one per data row.

    Returns:
        The absolute :class:`~pathlib.Path` of the written CSV file.
    """
    filepath = RESULTS_DIR / filename
    with open(filepath, mode="w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(CSV_HEADERS)
        writer.writerows(rows)
    print(f"[BENCHMARK] Generated: {filename}")
    return filepath
