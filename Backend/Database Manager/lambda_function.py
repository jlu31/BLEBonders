# handler.py
import os
import json
import time
import boto3
from urllib.parse import unquote_plus
from botocore.exceptions import ClientError

from embeddings import embed_text, embed_profile
from bedrock_profile import build_profile_from_transcript
from vpm import VPM

s3 = boto3.client("s3")
engine = VPM()  # one-time init per container


# ---------- helpers ----------

def wait_for_s3_key(bucket, key, timeout=120, interval=3):
    """Poll S3 for a key to appear."""
    start = time.time()
    while time.time() - start < timeout:
        try:
            s3.head_object(Bucket=bucket, Key=key)
            return True
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code")
            if code in ("404", "NotFound", "NoSuchKey"):
                time.sleep(interval)
                continue
            raise
    return False


def _base_name(key: str) -> str:
    """Extracts base file name (no extension) from an S3 key."""
    base = os.path.basename(key)
    return os.path.splitext(base)[0]


def _load_transcript_text(bucket: str, orig_key: str, vpm_result: dict) -> str:
    """
    Return the plain transcript text, trying (in order):
      1) inline text from VPM (vpm_result['transcript'])
      2) transcript S3 key from VPM (vpm_result['transcriptKey'])
      3) transcripts/<base>.txt
      4) transcripts/<base>.json (supports simple + AWS Transcribe schema)
    """
    base = _base_name(orig_key)

    # 1) inline text from VPM
    if isinstance(vpm_result, dict):
        t = vpm_result.get("transcript")
        if isinstance(t, str) and t.strip():
            return t

    # 2) transcript key returned by VPM
    if isinstance(vpm_result, dict):
        tkey = vpm_result.get("transcriptKey")
        if tkey:
            wait_for_s3_key(bucket, tkey, timeout=60, interval=2)
            obj = s3.get_object(Bucket=bucket, Key=tkey)
            body = obj["Body"].read().decode("utf-8")
            stripped = body.strip()
            if stripped and stripped[0] not in "{[":
                return stripped
            try:
                payload = json.loads(body)
                if isinstance(payload, dict) and "transcript" in payload:
                    return payload["transcript"]
                return payload["results"]["transcripts"][0]["transcript"]
            except Exception:
                pass

    # 3) fallback to local transcripts folder
    txt_key = f"transcripts/{base}.txt"
    json_key = f"transcripts/{base}.json"

    found = wait_for_s3_key(bucket, txt_key, timeout=5, interval=1) or \
            wait_for_s3_key(bucket, json_key, timeout=120, interval=3)

    if not found:
        raise RuntimeError(f"Transcript not found in time: s3://{bucket}/{txt_key} or {json_key}")

    try:
        obj = s3.get_object(Bucket=bucket, Key=txt_key)
        return obj["Body"].read().decode("utf-8")
    except Exception:
        obj = s3.get_object(Bucket=bucket, Key=json_key)
        payload = json.loads(obj["Body"].read().decode("utf-8"))
        if isinstance(payload, dict) and "transcript" in payload:
            return payload["transcript"]
        return payload["results"]["transcripts"][0]["transcript"]


def _save_profile_and_vector(bucket: str, profile_name: str, profile: dict, vector: list[float]) -> dict:
    """
    Saves both profile.json and vector.json to S3 under profiles/{profile_name}/.
    """
    base_folder = f"profiles/{profile_name}/"

    # Save profile.json
    profile_key = f"{base_folder}profile.json"
    s3.put_object(
        Bucket=bucket,
        Key=profile_key,
        Body=json.dumps(profile, ensure_ascii=False, indent=2).encode("utf-8"),
        ContentType="application/json"
    )

    # Save vector.json
    vector_key = f"{base_folder}vector.json"
    s3.put_object(
        Bucket=bucket,
        Key=vector_key,
        Body=json.dumps(vector, ensure_ascii=False).encode("utf-8"),
        ContentType="application/json"
    )

    return {"profile_key": profile_key, "vector_key": vector_key}


# ---------- lambda entry ----------
def lambda_handler(event, context):
    rec = event["Records"][0]
    bucket = rec["s3"]["bucket"]["name"]
    key = unquote_plus(rec["s3"]["object"]["key"])
    print(f"[S3 Trigger] bucket={bucket} key={key}")

    profile_name = _base_name(key)
    print(f"[Profile name] {profile_name}")

    # 1) Transcribe via VPM
    vpm_result = engine.run_vpm(bucket, key)
    print("[VPM result]", vpm_result)

    # 2) Load transcript text
    transcript_text = _load_transcript_text(bucket, key, vpm_result)
    print(f"[Transcript length] {len(transcript_text)} chars")

    # 3) Build profile via Bedrock
    profile = build_profile_from_transcript(transcript_text)
    print("[Profile]", profile)

    # 4) Generate vector embedding
    vector = embed_profile(profile)
    print(f"[Vector length] {len(vector)}")

    # 5) Save both profile and vector under profiles/<name>/
    saved = _save_profile_and_vector(bucket, profile_name, profile, vector)
    print(f"[Saved] s3://{bucket}/{saved['profile_key']} and s3://{bucket}/{saved['vector_key']}")

    return {
        "statusCode": 200,
        "body": json.dumps({
            "ok": True,
            "bucket": bucket,
            "profile_name": profile_name,
            "saved": saved
        })
    }
