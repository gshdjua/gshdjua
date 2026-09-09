import json
from typing import List, Optional, Tuple
from urllib.request import Request, urlopen

from .config import vector_rag_base_url


class VectorEmbeddingClient:
    def embed(self, text: str, input_type: str) -> Tuple[Optional[List[float]], str]:
        normalized = (text or "").strip()
        if not normalized:
            return None, ""
        request = Request(
            vector_rag_base_url() + "/embed",
            data=json.dumps({"texts": [normalized], "input_type": input_type}).encode("utf-8"),
            headers={"Content-Type": "application/json; charset=UTF-8"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=8) as response:
                payload = json.loads(response.read().decode("utf-8"))
            vectors = payload.get("vectors") or []
            return (vectors[0] if vectors else None), str(payload.get("model") or "")
        except Exception:
            return None, ""


embedding_client = VectorEmbeddingClient()
