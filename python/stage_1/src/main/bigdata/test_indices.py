"""
Quick verification script for the three inverted indexes.
Run from stage_1/python/:  python src/test_indices.py
"""

import json
import os
import sys

# Force UTF-8 output to avoid cp1252 errors on Windows
sys.stdout.reconfigure(encoding="utf-8")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATAMARTS = os.path.join(SCRIPT_DIR, "..", "..", "..", "..", "..", "data", "datamarts")

# =====================================================================
# 1. MONOLITHIC INDEX (inverted_index.json)
# =====================================================================
print("=" * 60)
print("  1. MONOLITHIC INDEX  (inverted_index.json)")
print("=" * 60)

mono_path = os.path.join(DATAMARTS, "inverted_index.json")
with open(mono_path, encoding="utf-8") as f:
    idx = json.load(f)

print(f"  Total terms: {len(idx)}")
print()

# Example search
search_words = ["whale", "darcy", "alice", "monster", "love", "sea", "war"]
print("  Example searches:")
for word in search_words:
    if word in idx:
        print(f"    \"{word}\" → books: {idx[word]}")
    else:
        print(f"    \"{word}\" → not found")

print()

# Top 10 most common terms
top = sorted(idx.items(), key=lambda x: len(x[1]), reverse=True)[:10]
print("  Top 10 terms (present in the most books):")
for term, ids in top:
    print(f"    \"{term}\" → {len(ids)} books: {ids}")


# =====================================================================
# 2. HIERARCHICAL INDEX  (inverted_index/<LETTER>/<term>.txt)
# =====================================================================
print()
print("=" * 60)
print("  2. HIERARCHICAL INDEX  (inverted_index/<LETTER>/<term>.txt)")
print("=" * 60)

hier_base = os.path.join(DATAMARTS, "inverted_index")
subdirs = sorted(os.listdir(hier_base))
print(f"  Subdirectories: {subdirs}")

total_files = 0
for sd in subdirs:
    sd_path = os.path.join(hier_base, sd)
    if os.path.isdir(sd_path):
        count = len(os.listdir(sd_path))
        total_files += count
print(f"  Total files (= terms): {total_files}")
print()

# Read some example files
example_terms = ["whale", "darcy", "alice", "love"]
print("  Verifying example files:")
for term in example_terms:
    first = term[0].upper()
    fpath = os.path.join(hier_base, first, f"{term}.txt")
    if os.path.isfile(fpath):
        with open(fpath, encoding="utf-8") as f:
            content = f.read().strip()
        ids = content.split("\n")
        print(f"    {first}/{term}.txt → IDs: {ids}")
    else:
        print(f"    {first}/{term}.txt → does not exist")


# =====================================================================
# 3. COMPARISON — do both indexes match?
# =====================================================================
print()
print("=" * 60)
print("  3. COMPARISON MONOLITHIC vs HIERARCHICAL")
print("=" * 60)

mismatches = 0
for term in example_terms:
    first = term[0].upper()
    fpath = os.path.join(hier_base, first, f"{term}.txt")

    mono_ids = sorted(idx.get(term, []))

    if os.path.isfile(fpath):
        with open(fpath, encoding="utf-8") as f:
            hier_ids = sorted(int(x) for x in f.read().strip().split("\n") if x)
    else:
        hier_ids = []

    match = "✓ OK" if mono_ids == hier_ids else "✗ MISMATCH"
    if mono_ids != hier_ids:
        mismatches += 1
    print(f"    \"{term}\": mono={mono_ids}  hier={hier_ids}  → {match}")

print()
if mismatches == 0:
    print("  ✓ The monolithic and hierarchical indexes match perfectly.")
else:
    print(f"  ✗ Found {mismatches} mismatches.")

print()
print("=" * 60)
print("  Verification completed.")
print("=" * 60)
