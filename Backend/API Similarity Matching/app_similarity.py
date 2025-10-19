import json
import os
import boto3
import base64
import logging

from bedrock_agent import build_sentence_from_profile, build_icebreakers_between_profiles

s3 = boto3.client("s3")
BUCKET = os.getenv("PROFILE_BUCKET", "bond-audio-uploads")

logger = logging.getLogger()
logger.setLevel(logging.INFO)


def _json(obj, code=200):
    return {
        "statusCode": code,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
        },
        "body": json.dumps(obj),
    }


def _head_exists(key: str) -> bool:
    try:
        s3.head_object(Bucket=BUCKET, Key=key)
        return True
    except s3.exceptions.NoSuchKey:
        return False
    except Exception:
        # Some S3 SDKs throw ClientError rather than NoSuchKey
        return False


def _load_s3_json(key: str):
    if not _head_exists(key):
        raise FileNotFoundError(key)
    body = s3.get_object(Bucket=BUCKET, Key=key)["Body"].read().decode("utf-8")
    return json.loads(body)


def _load_vector(profile_name: str):
    key = f"profiles/{profile_name}/vector.json"
    logger.info(f"Loading vector: s3://{BUCKET}/{key}")
    return _load_s3_json(key)


def _load_profile(profile_name: str) -> dict:
    """
    Loads the structured profile JSON used to create the sentence summary.
    Expected key: profiles/<name>/profile.json
    """
    key = f"profiles/{profile_name}/profile.json"
    logger.info(f"Loading profile: s3://{BUCKET}/{key}")
    return _load_s3_json(key)


def _sanitize(name: str) -> str:
    # Trim whitespace and normalize to lower-case so S3 keys match consistently
    return (name or "").strip().lower()


def handler(event, context):
    try:
        # 1) Extract and decode body
        body = event.get("body", "")
        if event.get("isBase64Encoded"):
            body = base64.b64decode(body).decode("utf-8")

        # 2) Parse JSON
        try:
            payload = json.loads(body)
        except Exception as e:
            logger.error(f"Bad JSON body: {e}; body={repr(body)[:200]}")
            return _json({"error": "Invalid JSON body"}, 400)

        # 3) Inputs
        a = _sanitize(payload.get("profileA"))
        b = _sanitize(payload.get("profileB"))
        if not a or not b:
            return _json({"error": "profileA and profileB are required"}, 400)

        # 4) Load vectors
        try:
            v1 = _load_vector(a)
            v2 = _load_vector(b)
        except FileNotFoundError as miss:
            return _json({"error": f"Vector file not found: {miss}"}, 404)

        # 5) Cosine similarity
        def _cosine(x, y):
            import math
            dot = sum(px * py for px, py in zip(x, y))
            nx = math.sqrt(sum(px * px for px in x))
            ny = math.sqrt(sum(py * py for py in y))
            return 0.0 if (nx == 0 or ny == 0) else dot / (nx * ny)

        score = _cosine(v1, v2)

        # 6) Load profiles and ask Bedrock for a short sentence per profile
        try:
            prof_a = _load_profile(a)
            sentence_a = build_sentence_from_profile(prof_a)
        except FileNotFoundError:
            prof_a = None
            sentence_a = None

        try:
            prof_b = _load_profile(b)
            sentence_b = build_sentence_from_profile(prof_b)
        except FileNotFoundError:
            prof_b = None
            sentence_b = None

        # 7) Icebreakers (only if we have both profiles available)
        icebreakers = None
        if prof_a and prof_b:
            try:
                icebreakers = build_icebreakers_between_profiles(prof_a, prof_b)
            except Exception as e:
                logger.error(f"Icebreaker generation failed: {e}")
                icebreakers = None

        return _json({
            "ok": True,
            "similarity": score,
            "summaries": {a: sentence_a, b: sentence_b},
            "icebreakers": icebreakers
        })

    except Exception as e:
        logger.exception("Unhandled error")
        return _json({"error": str(e)}, 500)
