from pathlib import Path

def _load_stopwords(path: Path) -> frozenset[str]:
    stopwords = set()
    if path.exists():
        with open(path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#'):
                    stopwords.add(line.lower())
    return frozenset(stopwords)

_STOPWORDS_PATH = Path(__file__).resolve().parents[7] / 'shared' / 'stopwords.txt'
_STOPWORDS = _load_stopwords(_STOPWORDS_PATH)

def tokenize(text: str) -> set[str]:
    result = set()
    current_token = []
    
    for char in text:
        if 'a' <= char <= 'z' or '0' <= char <= '9':
            current_token.append(char)
        elif 'A' <= char <= 'Z':
            current_token.append(char.lower())
        else:
            if current_token:
                token = ''.join(current_token)
                if len(token) >= 2 and token not in _STOPWORDS:
                    result.add(token)
                current_token.clear()
                
    if current_token:
        token = ''.join(current_token)
        if len(token) >= 2 and token not in _STOPWORDS:
            result.add(token)
            
    return result
