"""
Datamart Module — MongoDB Inverted Index
=========================================
Contains only the MongoIndex class (search_engine DB, inverted_index collection).
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# pymongo guard: the module loads even if pymongo is not installed.
# ---------------------------------------------------------------------------
try:
    from pymongo import MongoClient, ASCENDING
    from pymongo.operations import UpdateOne
    _PYMONGO_AVAILABLE = True
except ImportError:
    _PYMONGO_AVAILABLE = False


class MongoIndex:
    """
    Inverted index stored in MongoDB.

    - Database:   ``search_engine``
    - Collection: ``inverted_index``
    - Documents:  ``{"term": str, "postings": [int, ...]}``
    - Unique index on ``term`` (guarantees one document per term).

    Posting lists are kept sorted in ascending order using
    the ``$push … $each … $sort`` modifier.

    Requires ``pymongo``. If not installed, raises
    :class:`RuntimeError` upon instantiation.
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 27017,
        db_name: str = "search_engine",
        collection_name: str = "inverted_index",
    ) -> None:
        """
        Connects to MongoDB and ensures the unique index on ``term``.

        Args:
            host:            MongoDB server host.
            port:            MongoDB server port.
            db_name:         Database name.
            collection_name: Collection name.

        Raises:
            RuntimeError: If ``pymongo`` is not available in the environment.
        """
        if not _PYMONGO_AVAILABLE:
            raise RuntimeError(
                "pymongo is not installed. Run: pip install pymongo"
            )

        self._client = MongoClient(host, port)
        self._col = self._client[db_name][collection_name]

        # Unique index on "term" (SPEC Section 6)
        self._col.create_index("term", unique=True)

    # ------------------------------------------------------------------

    def add_postings(self, book_id: int, terms: set[str]) -> None:
        """
        Adds *book_id* to the posting list of each term in bulk
        (``bulk_write``) to maximize performance.

        Each UpdateOne operation uses:
        - ``upsert=True``   → creates the document if the term does not exist.
        - ``$push … $each … $sort: 1`` → inserts the ID maintaining
          ascending order. Deduplication is guaranteed because
          ``add_postings`` is called exactly once per book (the
          ``control_layer`` prevents re-indexing an already processed book).

        Args:
            book_id: Numeric book ID.
            terms:   Set of tokens from the book.
        """
        if not terms:
            return

        operations = [
            UpdateOne(
                {"term": term},
                {
                    "$push": {
                        "postings": {
                            "$each": [book_id],
                            "$sort": 1,         # maintains ascending order
                        }
                    }
                },
                upsert=True,
            )
            for term in terms
        ]

        self._col.bulk_write(operations, ordered=False)

    # ------------------------------------------------------------------

    def close(self) -> None:
        """Closes the MongoDB connection."""
        self._client.close()

    def __enter__(self) -> "MongoIndex":
        return self

    def __exit__(self, *_) -> None:
        self.close()
