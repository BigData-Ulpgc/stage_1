"""Benchmark runner — measures elapsed time with warmup/run methodology.

Follows SPEC section 9: N_WARMUP=2 (discarded), N_RUNS=5 (measured).
The runner ONLY times functions — it contains NO data logic.
"""
from __future__ import annotations
import time
from dataclasses import dataclass
from typing import Callable, List, Optional

N_WARMUP = 2
N_RUNS = 5
LANGUAGE = "python"


@dataclass(frozen=True, slots=True)
class BenchmarkRow:
    """One row of the benchmark CSV output."""
    language: str
    experiment: str
    structure: str
    dataset_size: int
    repetition: int
    metric: str
    value: float
    unit: str

    def to_csv_line(self) -> str:
        return ','.join([
            self.language, self.experiment, self.structure,
            str(self.dataset_size), str(self.repetition),
            self.metric, _format_value(self.value), self.unit
        ])


def _format_value(value: float) -> str:
    """Format a float with max 3 decimal places, no trailing zeros, no scientific notation."""
    rounded = round(value, 3)
    if rounded == int(rounded):
        return str(int(rounded))
    return f'{rounded:.3f}'.rstrip('0').rstrip('.')


CSV_HEADER = 'language,experiment,structure,dataset_size,repetition,metric,value,unit'


def measure(
    experiment: str,
    structure: str,
    dataset_size: int,
    setup: Optional[Callable] = None,
    task: Callable = None,
    warmups: int = N_WARMUP,
    runs: int = N_RUNS,
) -> List[BenchmarkRow]:
    """Run a benchmark with warmup+measured repetitions.
    
    Args:
        experiment: Name of the experiment (e.g. 'datalake_write')
        structure: Name of the structure (e.g. 'book', 'monolithic')
        dataset_size: Number of items in the dataset
        setup: Optional callable run BEFORE each repetition (not timed)
        task: The callable to measure
        warmups: Number of warmup iterations (discarded)
        runs: Number of measured iterations
    
    Returns:
        List of BenchmarkRow with metric='elapsed', unit='ms'
    """
    # Warmup iterations (results discarded)
    for _ in range(warmups):
        if setup:
            setup()
        task()
    
    # Measured iterations
    rows = []
    for rep in range(1, runs + 1):
        if setup:
            setup()
        start = time.perf_counter_ns()
        task()
        elapsed_ns = time.perf_counter_ns() - start
        elapsed_ms = elapsed_ns / 1_000_000.0
        rows.append(BenchmarkRow(
            language=LANGUAGE,
            experiment=experiment,
            structure=structure,
            dataset_size=dataset_size,
            repetition=rep,
            metric='elapsed',
            value=elapsed_ms,
            unit='ms',
        ))
    return rows


def derived_rows(
    elapsed_rows: List[BenchmarkRow],
    metric: str,
    unit: str,
    compute: Callable[[float], float],
) -> List[BenchmarkRow]:
    """Create derived metric rows from elapsed rows.
    
    Args:
        elapsed_rows: The measured elapsed rows
        metric: Name of derived metric (e.g. 'throughput')
        unit: Unit (e.g. 'books_per_s')
        compute: Function from elapsed_ms -> derived_value
    """
    return [
        BenchmarkRow(
            language=r.language,
            experiment=r.experiment,
            structure=r.structure,
            dataset_size=r.dataset_size,
            repetition=r.repetition,
            metric=metric,
            value=compute(max(r.value, 0.001)),
            unit=unit,
        )
        for r in elapsed_rows
    ]


def single_row(
    experiment: str,
    structure: str,
    dataset_size: int,
    metric: str,
    value: float,
    unit: str,
) -> BenchmarkRow:
    """Create a single-measurement row (repetition=1)."""
    return BenchmarkRow(
        language=LANGUAGE,
        experiment=experiment,
        structure=structure,
        dataset_size=dataset_size,
        repetition=1,
        metric=metric,
        value=value,
        unit=unit,
    )


# ------------------------------------------------------------------ #
# java.util.Random compatible PRNG                                    #
# ------------------------------------------------------------------ #

class JavaRandom:
    """Reimplementation of java.util.Random so Python produces the same
    pseudo-random sequence as the Java benchmarks (seed=42)."""

    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def _next(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, bound: int) -> int:
        r = self._next(31)
        m = bound - 1
        if bound & m == 0:
            return (bound * r) >> 31
        u = r
        r = u % bound
        while u - r + m >= 2**31:
            u = self._next(31)
            r = u % bound
        return r


def java_shuffle(items: list, rnd: JavaRandom) -> None:
    """Fisher-Yates shuffle identical to java.util.Collections.shuffle."""
    for i in range(len(items), 1, -1):
        j = rnd.next_int(i)
        items[i - 1], items[j] = items[j], items[i - 1]
