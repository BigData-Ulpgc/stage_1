"""
Main orchestrator for the indexing pipeline — Stage 1
=====================================================
Connects all project modules to process books from
Project Gutenberg end-to-end:

    book_ids.txt → download → datalake → metadata →
    tokenization → inverted indexes → control mark
"""

import os
import sys

# ---------------------------------------------------------------------------
# Internal module imports
# ---------------------------------------------------------------------------
from src.main.bigdata.control.state_manager import ControlLayer
from src.main.bigdata.crawler.splitter import fetch_book
from src.main.bigdata.datalake import save_to_all_structures
from src.main.bigdata.datamart.metadata.repository import MetadataManager
from src.main.bigdata.datamart.index.tokenizer import tokenize
from src.main.bigdata.datamart.index.monolithic import MonolithicIndex
from src.main.bigdata.datamart.index.hierarchical import HierarchicalIndex
from src.main.bigdata.datamart.index.mongo import MongoIndex

# ---------------------------------------------------------------------------
# Paths (relative to this script's directory → src/)
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_BOOK_IDS_PATH = os.path.join(_SCRIPT_DIR, "..", "..", "..", "..", "..", "shared", "book_ids.txt")


# ---------------------------------------------------------------------------
# Reading IDs
# ---------------------------------------------------------------------------

def load_book_ids(filepath: str) -> list[int]:
    """
    Reads *filepath* and returns the list of numeric IDs.

    - Ignores empty lines or those starting with ``#``.
    - Each valid line must contain an integer.
    """
    ids: list[int] = []
    with open(filepath, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                ids.append(int(line))
            except ValueError:
                print(f"[WARN] Line ignored in {filepath}: '{line}'")
    return ids


# ---------------------------------------------------------------------------
# Main pipeline
# ---------------------------------------------------------------------------

def main() -> None:
    # ── 1. Reading IDs ─────────────────────────────────────────────────
    print("=" * 65)
    print("[INFO] Starting indexing pipeline")
    print("=" * 65)

    if not os.path.isfile(_BOOK_IDS_PATH):
        print(f"[ERROR] Book IDs file not found: {_BOOK_IDS_PATH}")
        sys.exit(1)

    book_ids = load_book_ids(_BOOK_IDS_PATH)
    print(f"[INFO] Books to process: {len(book_ids)} -> {book_ids}\n")

    # ── 2. Initialization ──────────────────────────────────────────────
    control = ControlLayer()                      # uses default path
    metadata = MetadataManager()                  # creates schema in __init__
    mono_index = MonolithicIndex()
    hier_index = HierarchicalIndex()

    # MongoDB: wrap in try-except to avoid blocking the rest
    mongo_index = None
    try:
        mongo_index = MongoIndex()
        print("[INFO] MongoDB connection established successfully.")
    except Exception as e:
        print(f"[WARN] Could not connect to MongoDB: {e}")
        print("[WARN] MongoDB index will be skipped during this run.\n")

    # ── 3. Processing loop ─────────────────────────────────────────────
    processed = 0
    skipped = 0
    errors = 0

    for book_id in book_ids:
        print("-" * 55)
        print(f"[INFO] Processing book {book_id}...")

        # 3a. Filter — already indexed
        if control.is_indexed(book_id):
            print(f"[SKIP] Book {book_id} already indexed. Skipping.")
            skipped += 1
            continue

        # 3b. Download
        print(f"[INFO] [{book_id}] Downloading from Project Gutenberg...")
        result = fetch_book(book_id)
        if result is None:
            print(f"[ERROR] [{book_id}] Download failed. Skipping.")
            errors += 1
            continue
        header, body = result
        print(f"[INFO] [{book_id}] Download completed "
              f"(header: {len(header)} chars, body: {len(body)} chars).")

        # 3c. Datalake
        print(f"[INFO] [{book_id}] Saving to datalake (3 structures)...")
        paths = save_to_all_structures(book_id, header, body)
        book_header_path, book_body_path = paths["book"]
        print(f"[INFO] [{book_id}] Datalake -> OK.")

        # 3d. Metadata
        print(f"[INFO] [{book_id}] Inserting metadata into SQLite...")
        metadata.insert_or_update_book(
            book_id=book_id,
            header_text=header,
            body_path=book_body_path,
            header_path=book_header_path,
        )
        print(f"[INFO] [{book_id}] Metadata -> OK.")

        # 3e. Tokenization
        print(f"[INFO] [{book_id}] Tokenizing body...")
        tokens = tokenize(body)
        print(f"[INFO] [{book_id}] Unique tokens: {len(tokens)}.")

        # 3f. Inverted indexes
        print(f"[INFO] [{book_id}] Updating monolithic index...")
        mono_index.add_postings(book_id, tokens)

        print(f"[INFO] [{book_id}] Updating hierarchical index...")
        hier_index.add_postings(book_id, tokens)

        if mongo_index is not None:
            print(f"[INFO] [{book_id}] Updating MongoDB index...")
            mongo_index.add_postings(book_id, tokens)
        else:
            print(f"[WARN] [{book_id}] MongoDB index skipped (no connection).")

        # 3g. Confirmation in control layer
        control.mark_as_downloaded(book_id)
        control.mark_as_indexed(book_id)
        print(f"[INFO] [{book_id}] [OK] Book processed successfully.")

        processed += 1

    # ── 4. Shutdown ────────────────────────────────────────────────────
    print("\n" + "=" * 65)
    print("[INFO] Saving monolithic index to disk...")
    mono_index.save()
    print("[INFO] Monolithic index saved.")

    metadata.close()
    print("[INFO] SQLite connection closed.")

    if mongo_index is not None:
        mongo_index.close()
        print("[INFO] MongoDB connection closed.")

    # ── 5. Final summary ───────────────────────────────────────────────
    print("=" * 65)
    print(f"[INFO] Pipeline finished.")
    print(f"       Processed : {processed}")
    print(f"       Skipped   : {skipped}")
    print(f"       Errors    : {errors}")
    print(f"       Total     : {len(book_ids)}")
    print("=" * 65)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    main()
