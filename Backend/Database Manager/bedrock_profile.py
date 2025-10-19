# bedrock_profile.py
import os, json, boto3

# Uses the env vars you set earlier
_REGION = os.getenv("BEDROCK_REGION", "us-east-1")
_MODEL_ID = os.getenv("BEDROCK_MODEL_ID", "amazon.nova-micro-v1:0")

bedrock = boto3.client("bedrock-runtime", region_name=_REGION)

# Keep your profile schema simple & stable for the app to parse
PROFILE_SCHEMA = {
    "hobbies": ["", "", ""],
    "personality traits": ["", "", ""],
    "traits looking for ": [" ", " ", " "],
}

example_schema = {
    "hobbies": ["chess", "music", "guitar"],
    "personality traits": ["funny", "extroverted"],
    "traits looking for ": ["talkative", "kind", "healthy", "gymer"],
}

SYSTEM_PROMPT = (
    "You are an intelligent and detail-oriented AI assistant. Your job is to extract structured information "
    "from a user's transcript to build their personality profile.\n"
    "\n"
    "You will receive a transcript of a user responding to three questions:\n"
    "- What are your hobbies?\n"
    "- What are your personality traits?\n"
    "- What are the traits you are looking for?\n"
    "\n"
    "Your task:\n"
    "- Analyze the transcript carefully and extract only clear, factual information that directly answers the three questions.\n"
    "- Return STRICT JSON (no extra text, no comments, no explanations) matching the schema shown below.\n"
    "- Each list should contain simple, lowercase, single-word or short-phrase descriptors.\n"
    "- Do NOT fabricate or assume traits not explicitly supported by the transcript.\n"
    "- If a category is not mentioned, leave its list empty ([]).\n"
    "- The output must be valid JSON and must strictly match the field names in the schema.\n"
    "\n"
    "Profile schema:\n"
    + json.dumps(PROFILE_SCHEMA, indent=2) +
    "\n\n"
    "Example output:\n"
    + json.dumps(example_schema, indent=2) +
    "\n\n"
    "Formatting rules:\n"
    "- No explanations or natural language outside JSON.\n"
    "- No trailing commas or invalid JSON syntax.\n"
    "- Do not include any quotes around keys other than standard JSON format.\n"
    "- Ensure the output parses successfully as valid JSON."
)


def build_profile_from_transcript(transcript_text: str) -> dict:
    """
    Calls Amazon Nova Micro via Bedrock Converse and returns a dict matching PROFILE_SCHEMA.
    """
    resp = bedrock.converse(
        modelId=_MODEL_ID,
        system=[{"text": SYSTEM_PROMPT}],                 # system prompt goes here for Converse
        messages=[{
            "role": "user",
            "content": [{"text": f"Transcript:\n{transcript_text}"}]
        }],
        inferenceConfig={
            "maxTokens": 800,
            "temperature": 0.2
        }
    )

    # Nova (Converse) response format:
    # resp["output"]["message"]["content"] -> list of blocks like {"text": "..."}
    content_blocks = resp.get("output", {}).get("message", {}).get("content", []) or []
    text_blocks = [b.get("text", "") for b in content_blocks if isinstance(b, dict) and "text" in b]
    if not text_blocks:
        raise RuntimeError("Bedrock (Nova) response missing text content.")
    text = text_blocks[0].strip()

    # Expect pure JSON; parse directly, else fall back to the largest {...} slice.
    try:
        return json.loads(text)
    except Exception:
        start, end = text.find("{"), text.rfind("}")
        if start >= 0 and end >= 0:
            return json.loads(text[start:end + 1])
        raise