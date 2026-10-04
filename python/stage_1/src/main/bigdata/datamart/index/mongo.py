from typing import List, Set
from .base import InvertedIndex

try:
    from pymongo import MongoClient, UpdateOne
    from pymongo.errors import PyMongoError
    HAS_PYMONGO = True
except ImportError:
    HAS_PYMONGO = False

class MongoInvertedIndex(InvertedIndex):
    def __init__(self, uri='mongodb://localhost:27017', db_name='search_engine', collection='inverted_index'):
        if not HAS_PYMONGO:
            raise ImportError("pymongo is required for MongoInvertedIndex")
        
        self._client = MongoClient(uri)
        self._db = self._client[db_name]
        self._collection = self._db[collection]
        
        self._collection.create_index("term", unique=True)
        self._pending: dict[str, set[int]] = {}

    def name(self) -> str:
        return "mongo"

    def add_document(self, book_id: int, terms: Set[str]) -> None:
        for term in terms:
            self._pending.setdefault(term, set()).add(book_id)

    def postings(self, term: str) -> List[int]:
        ids = set(self._pending.get(term, set()))
        
        doc = self._collection.find_one({"term": term})
        if doc and "postings" in doc:
            ids.update(doc["postings"])
            
        return sorted(ids)

    def flush(self) -> None:
        if not self._pending:
            return
            
        operations = []
        for term, ids in self._pending.items():
            operations.append(
                UpdateOne(
                    {"term": term},
                    {"$addToSet": {"postings": {"$each": sorted(ids)}}},
                    upsert=True
                )
            )
            
        if operations:
            self._collection.bulk_write(operations, ordered=False)
            
        self._pending.clear()

    def clear(self) -> None:
        self._pending.clear()
        self._collection.drop()
        self._collection.create_index("term", unique=True)

    def disk_usage_bytes(self) -> int:
        try:
            # Flush WiredTiger buffers to disk so collStats reflects the real size
            self._client.admin.command('fsync')
            stats = self._db.command("collStats", self._collection.name)
            return stats.get("storageSize", 0) + stats.get("totalIndexSize", 0)
        except Exception:
            return 0

    def close(self) -> None:
        if hasattr(self, '_client'):
            self._client.close()
