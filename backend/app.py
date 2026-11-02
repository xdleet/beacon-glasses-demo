"""Beacon glasses-demo backend.

Glasses frame + question -> Muse Wonder (+ SAM 3.1 visual context)
-> short, glanceable answer, stored for the watch relay to poll.

Contract (fixed; other tracks build against this):
  GET  /health  -> {"ok": true}
  POST /ask     {"image_b64": str, "question": str}
                -> {"answer": str, "sam": str | null}
  GET  /latest  -> {"question": str | null, "answer": str | null,
                    "at": str | null}

The Meta Model API key is NEVER read from this repo, logged, printed, or
embedded. It is supplied only as the MODEL_API_KEY environment variable by
the start script, which sources the host machine's own environment file
(~/.config/muse-dev/env) into the service process. If MODEL_API_KEY is
absent, /ask returns 503 and /health still returns {"ok": true}.

SAM 3.1 is best-effort by design: if the SAM call fails, times out, or
returns a shape this code cannot summarise, /ask still answers from
Wonder alone (sam=null). SAM must never block the flow.

Sample data only in tests and logs. No private data, credentials, or
client data is stored: /latest keeps only the most recent question and
answer text, and request images are held in memory for the single call
and then dropped.
"""

import asyncio
import base64
import binascii
import json
import logging
import os
import re
import time
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("glasses-backend")

BASE_URL = os.environ.get("META_API_BASE_URL", "https://api.meta.ai/v1").rstrip("/")
WONDER_MODEL = "muse-wonder-1.0"
SPARK_MODEL = "muse-spark-1.3"  # fallback ONLY if Wonder fails
SAM_MODEL = "sam-3.1"

# Generous output cap: Wonder reasoning tokens count against the output
# budget and are billed at output rates, so a tiny cap gets eaten by
# reasoning and returns an empty reply (seen in the Oct 27 smoke test).
MAX_TOKENS = 1024
REASONING_EFFORT = "minimal"

SYSTEM_PROMPT = (
    "You are a hands-free repair copilot answering what Ray-Ban Meta "
    "glasses see. Reply concisely for a watch display: a few sentences "
    "at most, plain language, no preamble. If the question needs steps, "
    "give at most three short steps. Say plainly if you cannot tell from "
    "the image."
)

# One short noun phrase per SAM request (SAM takes concepts, not questions).
CONCEPT_KEYWORDS = [
    ("laptop", "laptop"),
    ("notebook", "laptop"),
    ("computer", "computer"),
    ("pc", "computer"),
    ("phone", "phone"),
    ("keyboard", "keyboard"),
    ("screen", "screen"),
    ("monitor", "monitor"),
    ("cable", "cable"),
    ("charger", "charger"),
    ("router", "router"),
    ("printer", "printer"),
    ("watch", "watch"),
    ("glasses", "glasses"),
    ("headphone", "headphones"),
    ("earbud", "earbuds"),
    ("mouse", "mouse"),
    ("tablet", "tablet"),
    ("logo", "logo"),
    ("camera", "camera"),
    ("speaker", "speaker"),
    ("battery", "battery"),
    ("book", "book"),
    ("cup", "cup"),
    ("bottle", "bottle"),
    ("car", "car"),
    ("dog", "dog"),
    ("cat", "cat"),
    ("person", "person"),
]


def derive_concept(question: str) -> str:
    q = question.lower()
    for keyword, concept in CONCEPT_KEYWORDS:
        if re.search(r"\b" + re.escape(keyword) + r"\b", q):
            return concept
    return "object"


class AskRequest(BaseModel):
    image_b64: str = Field(..., min_length=1)
    question: str = Field(..., min_length=1, max_length=2000)


app = FastAPI(title="beacon-glasses-demo-backend")
_latest: dict = {"question": None, "answer": None, "at": None}
_latest_lock = asyncio.Lock()


def _api_key() -> str | None:
    return os.environ.get("MODEL_API_KEY") or None


def _headers() -> dict:
    # The key value is used here only as an Authorization header; it is
    # never logged, printed, or returned.
    return {"Authorization": "Bearer " + (_api_key() or ""), "Content-Type": "application/json"}


def _extract_chat_text(payload: dict) -> str:
    """Extract assistant text from a Chat Completions payload."""
    try:
        content = payload["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        return ""
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, dict) and part.get("type") in ("text", "output_text"):
                parts.append(str(part.get("text", "")))
            elif isinstance(part, dict) and "text" in part:
                parts.append(str(part["text"]))
        return "".join(parts).strip()
    return ""


def _extract_responses_text(payload: dict) -> str:
    """Extract text from a Responses API payload (best effort)."""
    texts: list[str] = []
    output = payload.get("output")
    if isinstance(output, list):
        for item in output:
            content = item.get("content") if isinstance(item, dict) else None
            if isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and part.get("type") in (
                        "output_text", "text", "refusal",
                    ):
                        texts.append(str(part.get("text", "")))
            elif isinstance(item, dict) and item.get("type") == "message":
                pass
    if not texts and isinstance(payload.get("output_text"), str):
        texts.append(payload["output_text"])
    return "".join(texts).strip()


async def call_sam(client: httpx.AsyncClient, image_b64: str, concept: str) -> str | None:
    """One SAM 3.1 concept call. Returns a short summary or None.

    SAM is served through the Responses API: the image goes in as a data
    URL and the concept as a short noun phrase. The official output is a
    streamed segmentation grammar decoded by the meta-sam parser; this
    service does not depend on that parser. It summarises what it can
    (mask/box counts visible in the response) and otherwise reports that
    SAM responded, so callers still get the visual-context signal. Any
    failure -> None (Wonder-only fallback).
    """
    data_url = "data:image/jpeg;base64," + image_b64
    payload = {
        "model": SAM_MODEL,
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_image", "image_url": data_url},
                    {"type": "input_text", "text": concept},
                ],
            }
        ],
    }
    try:
        resp = await client.post(
            f"{BASE_URL}/responses", json=payload, headers=_headers(), timeout=30.0
        )
    except Exception as exc:  # timeout / network: never block /ask
        log.warning("SAM request failed: %s", type(exc).__name__)
        return None
    if resp.status_code != 200:
        log.warning("SAM HTTP %s (body %d bytes, not logged)", resp.status_code, len(resp.content))
        return None
    try:
        body = resp.json()
    except ValueError:
        log.warning("SAM response was not JSON")
        return None
    text = _extract_responses_text(body)
    # SAM's output_text is a segmentation grammar, e.g.
    #   <0f>0<|box;x1=807;y1=560;x2=866;y2=596;w=1024;h=1024|>
    #   <|mask;x=0;y=0;data=...|>
    # Verified live Oct 30, 2026 on a real photograph: the grammar IS in
    # output_text. A synthetic flat drawing returned empty output_text,
    # which the cookbook documents as a valid empty match, not an error.
    # Summarise boxes only (mask payloads are large encoded strings and
    # are never forwarded); the watch UI only ever sees this short string.
    boxes = re.findall(
        r"<\|box;x1=(\d+);y1=(\d+);x2=(\d+);y2=(\d+);w=(\d+);h=(\d+)\|>", text
    )
    mask_count = len(re.findall(r"<\|mask;", text))
    if boxes:
        parts = []
        for x1, y1, x2, y2, w, h in boxes[:3]:
            w, h = int(w), int(h)
            parts.append(
                f"left {int(x1) * 100 // w}%, top {int(y1) * 100 // h}% "
                f"to right {int(x2) * 100 // w}%, bottom {int(y2) * 100 // h}%"
            )
        return (
            f"SAM 3.1 segmented {len(boxes)} instance(s) of '{concept}' "
            f"({mask_count} mask(s)): " + "; ".join(parts)
            + (f" (and {len(boxes) - 3} more)" if len(boxes) > 3 else "")
        )
    if text:
        return f"SAM 3.1 responded for '{concept}' (grammar present, no boxes parsed)."
    # HTTP 200, completed, empty output: no instances matched this
    # concept in this frame. Do NOT assert absence to Wonder (the
    # concept is only a keyword guess from the question); fall back to
    # Wonder-only by returning None.
    log.info("SAM completed with empty output for concept %r (no instances)", concept)
    return None


async def call_wonder(client: httpx.AsyncClient, image_b64: str, question: str, sam: str | None) -> tuple[str, str]:
    """Call Wonder (fallback Spark) with image + question. Returns (answer, model_used)."""
    user_text = question
    if sam:
        user_text = f"Visual context from SAM 3.1 segmentation: {sam}\n\nQuestion: {question}"
    data_url = "data:image/jpeg;base64," + image_b64
    for model in (WONDER_MODEL, SPARK_MODEL):
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_text},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                },
            ],
            "reasoning_effort": REASONING_EFFORT,
            "max_tokens": MAX_TOKENS,
        }
        try:
            resp = await client.post(
                f"{BASE_URL}/chat/completions", json=payload, headers=_headers(), timeout=90.0
            )
        except Exception as exc:
            log.warning("%s request failed: %s", model, type(exc).__name__)
            continue
        if resp.status_code != 200:
            log.warning("%s HTTP %s (body %d bytes, not logged)", model, resp.status_code, len(resp.content))
            # A 400 may mean an unsupported parameter (e.g. reasoning_effort
            # on this surface); retry once without it before falling back.
            if resp.status_code == 400 and model == WONDER_MODEL:
                payload.pop("reasoning_effort", None)
                try:
                    retry = await client.post(
                        f"{BASE_URL}/chat/completions", json=payload, headers=_headers(), timeout=90.0
                    )
                except Exception:
                    continue
                if retry.status_code == 200:
                    try:
                        answer = _extract_chat_text(retry.json())
                    except ValueError:
                        answer = ""
                    if answer:
                        return answer, model
            continue
        try:
            answer = _extract_chat_text(resp.json())
        except ValueError:
            answer = ""
        if answer:
            return answer, model
        log.warning("%s returned HTTP 200 but empty text; trying next model", model)
    raise HTTPException(status_code=502, detail="Upstream model call failed for all models")


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/latest")
async def latest():
    async with _latest_lock:
        return dict(_latest)


@app.post("/ask")
async def ask(req: AskRequest):
    if not _api_key():
        raise HTTPException(status_code=503, detail="MODEL_API_KEY not configured on host")
    try:
        raw = base64.b64decode(req.image_b64, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="image_b64 is not valid base64")
    if len(raw) == 0 or len(raw) > 15 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="image size out of range (empty or >15MB)")
    question = req.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is empty")
    t0 = time.time()
    async with httpx.AsyncClient() as client:
        sam = await call_sam(client, req.image_b64, derive_concept(question))
        answer, model_used = await call_wonder(client, req.image_b64, question, sam)
    at = datetime.now(timezone.utc).isoformat()
    async with _latest_lock:
        _latest.update({"question": question, "answer": answer, "at": at})
    log.info(
        "ask ok model=%s sam=%s answer_chars=%d question_chars=%d elapsed=%.1fs",
        model_used, "yes" if sam else "no", len(answer), len(question), time.time() - t0,
    )
    return {"answer": answer, "sam": sam}
