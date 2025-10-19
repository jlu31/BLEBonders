# vpm.py
import os, json, time, uuid, boto3

class VPM:
    """
    Minimal wrapper that:
      1) Starts Amazon Transcribe on s3://bucket/key (MP3).
      2) Polls briefly until it completes (simple demo).
      3) Reads the transcript JSON Transcribe wrote to S3.
      4) Saves a .txt transcript and returns a small result dict.
    """

    def __init__(self, region: str | None = None, output_prefix: str = "transcripts/"):
        self.region = region or os.getenv("AWS_REGION", "us-east-1")
        self.transcribe = boto3.client("transcribe", region_name=self.region)
        self.s3 = boto3.client("s3")
        self.output_prefix = output_prefix
        
        # override via env if you like: VPM_LANG=en-US
        self.language_code = os.getenv("VPM_LANG", "en-US")

    def run_vpm(self, bucket: str, key: str) -> dict:
        job_name = f"mp3-{uuid.uuid4().hex[:12]}"
        media_uri = f"s3://{bucket}/{key}"

        # 1) Kick off Transcribe; tell it to write output JSON back to our bucket
        self.transcribe.start_transcription_job(
            TranscriptionJobName=job_name,
            Media={"MediaFileUri": media_uri},
            MediaFormat="mp3",
            LanguageCode=self.language_code,
            OutputBucketName=bucket,                 # transcript will be placed here…
            OutputKey=f"{self.output_prefix}{job_name}/"  # …under this prefix
        )

        # 2) Simple short poll (~60s). For long audio, switch to EventBridge callback later.
        for _ in range(30):  # 30 * 2s = ~60s
            resp = self.transcribe.get_transcription_job(TranscriptionJobName=job_name)
            status = resp["TranscriptionJob"]["TranscriptionJobStatus"]
            if status in ("COMPLETED", "FAILED"):
                break
            time.sleep(2)

        if status != "COMPLETED":
            return {"ok": False, "job": job_name, "status": status, "msg": "Transcribe not completed in time"}

        # 3) Find the JSON Transcribe wrote to S3 and read it
        prefix = f"{self.output_prefix}{job_name}/"
        listed = self.s3.list_objects_v2(Bucket=bucket, Prefix=prefix).get("Contents", [])
        json_keys = [o["Key"] for o in listed if o["Key"].endswith(".json")]
        if not json_keys:
            return {"ok": False, "job": job_name, "error": "Transcript JSON not found"}

        json_key = sorted(json_keys)[-1]
        obj = self.s3.get_object(Bucket=bucket, Key=json_key)
        transcript_json = obj["Body"].read().decode("utf-8")
        text = json.loads(transcript_json)["results"]["transcripts"][0]["transcript"]

        # 4) Save a plain .txt next to it for easy fetching
        base = os.path.splitext(os.path.basename(key))[0]
        out_txt_key = f"{self.output_prefix}{base}.txt"
        self.s3.put_object(
            Bucket=bucket,
            Key=out_txt_key,
            Body=text.encode("utf-8"),
            ContentType="text/plain"
        )

        return {
            "ok": True,
            "job": job_name,
            "input": f"s3://{bucket}/{key}",
            "transcriptKey": out_txt_key,
            "textSample": text[:200]
        }
