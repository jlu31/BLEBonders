import os, json, boto3
from typing import List, Dict

# ---- Config ----
_REGION = os.getenv("BEDROCK_REGION", "us-east-1")
_MODEL_ID = os.getenv("BEDROCK_EMBED_MODEL_ID", "amazon.titan-embed-text-v2:0")

# Optional tuning
_DIM_RAW = os.getenv("TITAN_EMBED_DIM", "").strip()
_NORMALIZE = os.getenv("TITAN_EMBED_NORMALIZE", "true").lower() == "true"

# Validate optional dimension (Titan V2 supports 256, 512, 1024)
_DIM = None
if _DIM_RAW:
    try:
        dim_val = int(_DIM_RAW)
        if dim_val in (256, 512, 1024):
            _DIM = dim_val
    except ValueError:
        pass  # ignore invalid; use model default

_bedrock = boto3.client("bedrock-runtime", region_name=_REGION)


# ---- Helpers ----
def _titan_body_for_text(text: str) -> dict:
    body = {"inputText": text}
    if _DIM is not None:
        body["dimensions"] = _DIM
    # Titan V2 supports unit vector normalization (bool)
    body["normalize"] = bool(_NORMALIZE)
    return body


def _parse_titan_embedding(payload: dict) -> List[float]:
    """
    Titan V2 responses:
      - Single input: {"embedding": [ ... ] }
      - Batch input:  {"embeddings": [[...], [...], ...]}
    """
    if "embedding" in payload:
        return [float(x) for x in payload["embedding"]]
    if "embeddings" in payload and payload["embeddings"]:
        return [float(x) for x in payload["embeddings"][0]]
    raise RuntimeError(f"Unexpected Titan payload: {payload}")


def _parse_titan_embeddings(payload: dict) -> List[List[float]]:
    if "embeddings" in payload and isinstance(payload["embeddings"], list):
        return [[float(x) for x in vec] for vec in payload["embeddings"]]
    # If someone accidentally passed single input here:
    if "embedding" in payload:
        return [[float(x) for x in payload["embedding"]]]
    raise RuntimeError(f"Unexpected Titan payload (batch): {payload}")


def _flatten_profile(profile: Dict) -> str:
    """
    Flattens your profile dict into a concise string for embedding.
    Expected keys:
      - "hobbies": List[str]
      - "personality traits": List[str]
      - "traits looking for ": List[str]   # note trailing space in your schema
    """
    hobbies = [s.strip() for s in profile.get("hobbies", []) if s and str(s).strip()]
    traits = [s.strip() for s in profile.get("personality traits", []) if s and str(s).strip()]
    looking = [s.strip() for s in profile.get("traits looking for ", []) if s and str(s).strip()]

    parts = []
    if hobbies:
        parts.append("hobbies: " + ", ".join(hobbies))
    if traits:
        parts.append("personality: " + ", ".join(traits))
    if looking:
        parts.append("looking_for: " + ", ".join(looking))

    # Minimal, order-stable, compact string for an embedding
    return " | ".join(parts) if parts else ""


# ---- Public API ----
def embed_text(text: str) -> List[float]:
    """Embed a single string with Titan Text Embeddings V2."""
    body = _titan_body_for_text(text)
    resp = _bedrock.invoke_model(modelId=_MODEL_ID, body=json.dumps(body))
    payload = json.loads(resp["body"].read())
    return _parse_titan_embedding(payload)


def embed_texts(texts: List[str]) -> List[List[float]]:
    """Embed a list of strings with Titan Text Embeddings V2 (batch)."""
    body = {
        "inputText": texts,
        "normalize": bool(_NORMALIZE),
    }
    if _DIM is not None:
        body["dimensions"] = _DIM

    resp = _bedrock.invoke_model(modelId=_MODEL_ID, body=json.dumps(body))
    payload = json.loads(resp["body"].read())
    return _parse_titan_embeddings(payload)


def embed_profile(profile: Dict) -> List[float]:
    """
    Convenience: takes your profile dict, flattens it, then embeds with Titan.
    """
    text = _flatten_profile(profile)
    if not text:
        # Fall back to a stable empty representation (prevents model errors)
        text = "hobbies: | personality: | looking_for: "
    return embed_text(text)
