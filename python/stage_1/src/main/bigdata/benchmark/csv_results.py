"""CSV results writer — atomic write of benchmark results."""
from __future__ import annotations
import os
from pathlib import Path
from typing import List

from .runner import BenchmarkRow, CSV_HEADER


def file_for(results_dir: Path, language: str, experiment: str) -> Path:
    """Return the CSV file path for a given experiment."""
    return results_dir / f'{language}_{experiment}.csv'


def write_csv(file: Path, rows: List[BenchmarkRow]) -> None:
    """Write benchmark rows to a CSV file atomically.
    
    Writes to a .tmp file first, then moves it into place.
    """
    file = Path(file)
    file.parent.mkdir(parents=True, exist_ok=True)
    tmp = file.with_suffix('.csv.tmp')
    
    lines = [CSV_HEADER]
    for row in rows:
        lines.append(row.to_csv_line())
    content = '\n'.join(lines) + '\n'
    
    try:
        tmp.write_text(content, encoding='utf-8', newline='\n')
        os.replace(str(tmp), str(file))
    except Exception:
        tmp.unlink(missing_ok=True)
        raise


def write_experiment(results_dir: Path, experiment: str, rows: List[BenchmarkRow]) -> None:
    """Convenience: write rows to the standard file for an experiment."""
    write_csv(file_for(results_dir, 'python', experiment), rows)
