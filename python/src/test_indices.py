"""
Script de verificación rápida de los tres índices invertidos.
Ejecutar desde stage_1/python/:  python src/test_indices.py
"""

import json
import os
import sys

# Forzar UTF-8 en la salida para evitar errores con cp1252 en Windows
sys.stdout.reconfigure(encoding="utf-8")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATAMARTS = os.path.join(SCRIPT_DIR, "..", "..", "data", "datamarts")

# =====================================================================
# 1. ÍNDICE MONOLÍTICO (inverted_index.json)
# =====================================================================
print("=" * 60)
print("  1. ÍNDICE MONOLÍTICO  (inverted_index.json)")
print("=" * 60)

mono_path = os.path.join(DATAMARTS, "inverted_index.json")
with open(mono_path, encoding="utf-8") as f:
    idx = json.load(f)

print(f"  Total de términos: {len(idx)}")
print()

# Búsqueda de ejemplo
search_words = ["whale", "darcy", "alice", "monster", "love", "sea", "war"]
print("  Búsquedas de ejemplo:")
for word in search_words:
    if word in idx:
        print(f"    \"{word}\" → libros: {idx[word]}")
    else:
        print(f"    \"{word}\" → no encontrado")

print()

# Top 10 términos más comunes
top = sorted(idx.items(), key=lambda x: len(x[1]), reverse=True)[:10]
print("  Top 10 términos (presentes en más libros):")
for term, ids in top:
    print(f"    \"{term}\" → {len(ids)} libros: {ids}")


# =====================================================================
# 2. ÍNDICE JERÁRQUICO  (inverted_index/<LETRA>/<term>.txt)
# =====================================================================
print()
print("=" * 60)
print("  2. ÍNDICE JERÁRQUICO  (inverted_index/<LETRA>/<term>.txt)")
print("=" * 60)

hier_base = os.path.join(DATAMARTS, "inverted_index")
subdirs = sorted(os.listdir(hier_base))
print(f"  Subcarpetas: {subdirs}")

total_files = 0
for sd in subdirs:
    sd_path = os.path.join(hier_base, sd)
    if os.path.isdir(sd_path):
        count = len(os.listdir(sd_path))
        total_files += count
print(f"  Total de archivos (= términos): {total_files}")
print()

# Leer algunos archivos de ejemplo
example_terms = ["whale", "darcy", "alice", "love"]
print("  Verificando archivos de ejemplo:")
for term in example_terms:
    first = term[0].upper()
    fpath = os.path.join(hier_base, first, f"{term}.txt")
    if os.path.isfile(fpath):
        with open(fpath, encoding="utf-8") as f:
            content = f.read().strip()
        ids = content.split("\n")
        print(f"    {first}/{term}.txt → IDs: {ids}")
    else:
        print(f"    {first}/{term}.txt → no existe")


# =====================================================================
# 3. COMPARACIÓN — ¿coinciden ambos índices?
# =====================================================================
print()
print("=" * 60)
print("  3. COMPARACIÓN MONOLÍTICO vs JERÁRQUICO")
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
    print("  ✓ Los índices monolítico y jerárquico coinciden perfectamente.")
else:
    print(f"  ✗ Se encontraron {mismatches} discrepancias.")

print()
print("=" * 60)
print("  Verificación completada.")
print("=" * 60)
