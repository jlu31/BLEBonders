# bedrock_profile.py
import os
import json
import boto3

# Bedrock setup from env
_REGION   = os.getenv("BEDROCK_REGION", "us-east-1")
_MODEL_ID = os.getenv("BEDROCK_MODEL_ID", "amazon.nova-micro-v1:0")

bedrock = boto3.client("bedrock-runtime", region_name=_REGION)

# Stable schema example to show the LLM what the profile looks like
PROFILE_SCHEMA = {
    "hobbies": ["chess", "music", "guitar"],
    "personality traits": ["funny", "extroverted"],
    "traits looking for": ["talkative", "kind", "healthy", "gymer"]
}

SYSTEM_PROMPT = (
    "You are an AI assistant that summarizes user personality profiles.\n"
    "\n"
    "You will be given a JSON object describing a single user's profile, containing fields such as:\n"
    "- hobbies\n"
    "- personality traits\n"
    "- traits they are looking for\n"
    "\n"
    "Your task:\n"
    "- Create a short descriptive summary of the user in **under 30 characters**.\n"
    "- The summary should reflect the user's general vibe or personality — not a full sentence.\n"
    "- Only use the provided information. Do NOT invent new facts or assumptions.\n"
    "- The summary must be concise, natural, and human-like (like a quick label, not a phrase).\n"
    "- Output ONLY the summary text — no quotes, punctuation, or extra explanation.\n"
    "- Do NOT include JSON, full sentences, or additional formatting.\n"
    "\n"
    "Example profile schema:\n"
    + json.dumps(PROFILE_SCHEMA, indent=2)
    + "\n\n"
    "Example input:\n"
    "{\n"
    "  \"hobbies\": [\"basketball\", \"traveling\"],\n"
    "  \"personality traits\": [\"outgoing\", \"curious\"],\n"
    "  \"traits looking for\": [\"funny\", \"kind\"]\n"
    "}\n"
    "\n"
    "Example output:\n"
    "Adventurous & outgoing\n"
)

def _clean_summary(s: str, max_len: int = 30) -> str:
    """Normalize the model's output into a single <=30 char string."""
    s = (s or "").strip()
    # Remove any surrounding quotes or code fences the model might add
    if (s.startswith(("```", "“", '"', "'")) and s.endswith(("```", "”", '"', "'"))):
        s = s.strip("`“”'\"")
    # Collapse newlines/spaces
    s = " ".join(s.split())
    # Hard cap to 30 chars per rule
    if len(s) > max_len:
        s = s[:max_len].rstrip()
    return s

def build_sentence_from_profile(profile: dict) -> str:
    """
    Given a profile dict matching the schema (or similar),
    return a max 30-character, non-sentence summary string.
    Uses Bedrock 'converse' (correct system/messages placement).
    """
    profile_text = json.dumps(profile, ensure_ascii=False, indent=2)

    resp = bedrock.converse(
        modelId=_MODEL_ID,
        system=[{"text": SYSTEM_PROMPT}],
        messages=[{
            "role": "user",
            "content": [{"text": f"Profile JSON:\n{profile_text}\n\nReturn ≤30 characters."}]
        }],
        inferenceConfig={
            "maxTokens": 60,     # tiny; we expect <=30 chars
            "temperature": 0.2
        }
    )

    # Nova (Converse) response format:
    # resp["output"]["message"]["content"] -> list of blocks like {"text": "..."}
    blocks = resp.get("output", {}).get("message", {}).get("content", []) or []
    text = ""
    for b in blocks:
        if isinstance(b, dict) and "text" in b:
            text += b["text"]
    return _clean_summary(text)


def build_icebreakers_between_profiles(profileA: dict, profileB: dict) -> list[str]:
    """
    Takes two user profiles (dicts) and generates 2 friendly, natural
    ice breaker questions relevant to both users.
    Uses Amazon Nova (or any Bedrock model) via 'converse'.
    Returns a list of 2 strings.
    """
    ICEBREAKER_SYSTEM_PROMPT = (
        "You are a friendly and emotionally intelligent AI designed to help people connect.\n"
        "\n"
        "You will receive two user profiles, each containing structured information such as:\n"
        "- hobbies\n"
        "- personality traits\n"
        "- traits they are looking for\n"
        "\n"
        "Your task:\n"
        "- Create 2 short, natural ice breaker questions that would help these two users start a conversation.\n"
        "- MAXIUMUM CHARACTERS is 70 character. DO NOT exceed that"
        "- Each question should be relevant to BOTH users' interests, hobbies, or compatible personality traits.\n"
        "- The tone should be friendly, warm, and curious — something you’d naturally ask on a first meeting.\n"
        "- The questions should NOT feel robotic or generic.\n"
        "- Do not mention that you know their profiles.\n"
        "- Do NOT output any explanation or JSON — only return the 2 questions as plain text, numbered 1 and 2.\n"
        "\n"
        "Example Input:\n"
        "Profile A:\n"
        "{\n"
        "  \"hobbies\": [\"basketball\", \"traveling\"],\n"
        "  \"personality traits\": [\"outgoing\", \"adventurous\"],\n"
        "  \"traits looking for\": [\"curious\", \"funny\"]\n"
        "}\n"
        "\n"
        "Profile B:\n"
        "{\n"
        "  \"hobbies\": [\"soccer\", \"music\"],\n"
        "  \"personality traits\": [\"creative\", \"social\"],\n"
        "  \"traits looking for\": [\"confident\", \"active\"]\n"
        "}\n"
        "\n"
        "Example Output:\n"
        "1. What’s your favorite way to stay active — pickup games or exploring new places?\n"
        "2. If you could plan a weekend trip together, would it be somewhere sporty or somewhere musical?\n"
    )

    # Convert both profiles into compact JSON strings for clarity
    profile_text_a = json.dumps(profileA, ensure_ascii=False, indent=2)
    profile_text_b = json.dumps(profileB, ensure_ascii=False, indent=2)

    # Prepare the user message content
    user_text = f"Profile A:\n{profile_text_a}\n\nProfile B:\n{profile_text_b}"

    # Call Bedrock
    resp = bedrock.converse(
        modelId=_MODEL_ID,
        system=[{"text": ICEBREAKER_SYSTEM_PROMPT}],
        messages=[{
            "role": "user",
            "content": [{"text": user_text}]
        }],
        inferenceConfig={
            "maxTokens": 150,   # Enough for two short questions
            "temperature": 0.7  # a bit more creative than summaries
        }
    )

    # Extract output text
    blocks = resp.get("output", {}).get("message", {}).get("content", []) or []
    text = ""
    for b in blocks:
        if isinstance(b, dict) and "text" in b:
            text += b["text"]

    # Normalize and split into 2 questions
    lines = [line.strip() for line in text.split("\n") if line.strip()]
    questions = [l for l in lines if l[0].isdigit() or l.startswith("-") or "?" in l]
    if len(questions) < 2:
        # fallback: just return first two non-empty lines
        questions = lines[:2]

    return questions[:2]
