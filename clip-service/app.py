"""
CLIP ViT-B/32 Embedding Service
"""
import base64
import io
import logging
from contextlib import asynccontextmanager

import open_clip
import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel

logger = logging.getLogger("clip-service")
logging.basicConfig(level=logging.INFO)

model = None
preprocess = None
tokenizer = None
device = "cpu"


@asynccontextmanager
async def lifespan(app: FastAPI):
    global model, preprocess, tokenizer
    logger.info("Loading CLIP ViT-B-32 model...")
    model, _, preprocess = open_clip.create_model_and_transforms(
        "ViT-B-32", pretrained="laion2b_s34b_b79k", device=device
    )
    tokenizer = open_clip.get_tokenizer("ViT-B-32")
    model.eval()
    logger.info("CLIP model loaded")
    yield


app = FastAPI(title="CLIP Embedding Service", lifespan=lifespan)


class ImageEmbedRequest(BaseModel):
    image_base64: str
    mime_type: str = "image/png"


class TextEmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    embedding: list[float]
    dimension: int


@app.post("/embed", response_model=EmbedResponse)
async def embed_image(request: ImageEmbedRequest):
    try:
        image_bytes = base64.b64decode(request.image_base64)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        image_tensor = preprocess(image).unsqueeze(0).to(device)

        with torch.no_grad():
            embedding = model.encode_image(image_tensor)
            embedding = embedding / embedding.norm(dim=-1, keepdim=True)

        vector = embedding.squeeze().cpu().tolist()
        return EmbedResponse(embedding=vector, dimension=len(vector))
    except Exception as exc:
        logger.exception("Image embedding failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/embed-text", response_model=EmbedResponse)
async def embed_text(request: TextEmbedRequest):
    try:
        text_tokens = tokenizer([request.text]).to(device)

        with torch.no_grad():
            embedding = model.encode_text(text_tokens)
            embedding = embedding / embedding.norm(dim=-1, keepdim=True)

        vector = embedding.squeeze().cpu().tolist()
        return EmbedResponse(embedding=vector, dimension=len(vector))
    except Exception as exc:
        logger.exception("Text embedding failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/health")
async def health():
    return {
        "status": "ok" if model is not None else "loading",
        "model": "ViT-B-32",
        "pretrained": "laion2b_s34b_b79k",
        "dimension": 512,
    }
