from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from datasets import Dataset
from ragas import evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall, answer_correctness
from langchain_openai import ChatOpenAI
from langchain_openai import OpenAIEmbeddings

import math
import inspect
import os
import time
import traceback
import ragas
import asyncio
import nest_asyncio
from typing import Optional

nest_asyncio.apply()

app = FastAPI(title="Ragas Evaluation Service")


class LLMConfig(BaseModel):
    model: str = "deepseek-chat"
    base_url: str = "https://api.deepseek.com/v1"
    api_key: str = ""

class EmbeddingConfig(BaseModel):
    model: str = "embedding-3"
    base_url: str = "https://open.bigmodel.cn/api/paas/v4"
    api_key: str = ""

class EvalCase(BaseModel):
    query: str
    answer: str
    contexts: list[str]
    ground_truth: str


class EvalRequest(BaseModel):
    cases: list[EvalCase]
    metrics: list[str] = ["faithfulness", "answer_relevancy", "context_precision", "context_recall"]
    llm_config: LLMConfig | None = None
    embedding_config: EmbeddingConfig | None = None
    language: str = "chinese"


@app.get("/health")
def health() -> dict:
    embedding_available = bool(os.environ.get("EMBEDDING_API_KEY", "") or os.environ.get("LLM_API_KEY", ""))
    return {
        "status": "ok",
        "ragas_version": ragas.__version__,
        "embedding_available": embedding_available,
        "default_language": "chinese"
    }


def _build_llm(req: EvalRequest) -> ChatOpenAI:
    llm_config = req.llm_config or LLMConfig()
    api_key = llm_config.api_key or os.environ.get("LLM_API_KEY", "") or os.environ.get("OPENAI_API_KEY", "")
    model = llm_config.model or os.environ.get("LLM_MODEL", "deepseek-chat")
    base_url = llm_config.base_url or os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1")

    if not api_key:
        raise HTTPException(status_code=400, detail="LLM API key is required (via request body or LLM_API_KEY env)")

    return ChatOpenAI(model=model, api_key=api_key, base_url=base_url)

def _build_embeddings(req: EvalRequest) -> Optional[LangchainEmbeddingsWrapper]:
    emb_config = req.embedding_config or EmbeddingConfig()
    api_key = emb_config.api_key or os.environ.get("EMBEDDING_API_KEY", "") or os.environ.get("LLM_API_KEY", "")
    model = emb_config.model or os.environ.get("EMBEDDING_MODEL", "embedding-3")
    base_url = emb_config.base_url or os.environ.get("EMBEDDING_BASE_URL", "https://open.bigmodel.cn/api/paas/v4")

    if not api_key:
        return None

    print(f"[Ragas] Built embeddings: model={model}, base_url={base_url}, api_key={'***' + api_key[-4:] if api_key else 'NONE'}")
    langchain_embeddings = OpenAIEmbeddings(model=model, api_key=api_key, base_url=base_url)
    return LangchainEmbeddingsWrapper(langchain_embeddings)


def _resolve_metrics(metric_names: list[str]) -> list:
    metric_map = {
        "faithfulness": faithfulness,
        "answer_relevancy": answer_relevancy,
        "context_precision": context_precision,
        "context_recall": context_recall,
        "answer_correctness": answer_correctness,
    }
    selected = [metric_map[name] for name in metric_names if name in metric_map]
    if not selected:
        selected = [faithfulness, answer_relevancy, context_precision, context_recall]
    return selected

async def _adapt_metrics_language(metrics: list, language: str, llm) -> list:
    """使用 Ragas adapt API 将指标的内部 prompt 适配为目标语言。"""
    if not language or language == "english":
        return metrics

    adapted = []
    for metric in metrics:
        try:
            # 尝试使用 Ragas 的 adapt 方法
            # Ragas 0.2.x 的每个 metric 有多个 prompt 属性
            prompt_attrs = [attr for attr in dir(metric) if 'prompt' in attr.lower() and not attr.startswith('_')]
            for attr_name in prompt_attrs:
                prompt_obj = getattr(metric, attr_name, None)
                if prompt_obj is not None and hasattr(prompt_obj, 'adapt'):
                    try:
                        adapted_prompt = await prompt_obj.adapt(target_language=language, llm=llm)
                        setattr(metric, attr_name, adapted_prompt)
                    except Exception:
                        pass  # 部分 prompt 可能不支持 adapt，静默跳过
            adapted.append(metric)
        except Exception:
            adapted.append(metric)  # 适配失败时保留原始 metric

    return adapted

def _adapt_metrics_sync(metrics: list, language: str, llm) -> list:
    """同步包装 adapt 调用。nest_asyncio 已在模块初始化时 apply，可直接 asyncio.run。"""
    try:
        return asyncio.run(_adapt_metrics_language(metrics, language, llm))
    except Exception as e:
        print(f"Metric language adaptation failed, using original metrics: {e}")
        return metrics


@app.post("/evaluate")
def run_evaluate(req: EvalRequest) -> dict:
    start_time = time.time()
    try:
        llm = _build_llm(req)
        embeddings = _build_embeddings(req)

        dataset = Dataset.from_dict(
            {
                "question": [c.query for c in req.cases],
                "answer": [c.answer for c in req.cases],
                "contexts": [c.contexts for c in req.cases],
                "ground_truth": [c.ground_truth for c in req.cases],
            }
        )

        selected_metrics = _resolve_metrics(req.metrics)

        # DeepSeek 不支持 n>1，将 answer_relevancy 的 strictness 设为 1
        for metric in selected_metrics:
            if hasattr(metric, "strictness"):
                metric.strictness = 1

        # 中文语言适配
        if req.language and req.language != "english":
            selected_metrics = _adapt_metrics_sync(selected_metrics, req.language, llm)

        # 将 embeddings 直接注入到 metric 对象，确保 Ragas 内部能使用正确的 embedding
        if embeddings:
            for metric in selected_metrics:
                if hasattr(metric, "embeddings"):
                    metric.embeddings = embeddings

        # ragas 版本差异兼容
        try:
            sig = inspect.signature(evaluate)
            kwargs = {"metrics": selected_metrics}
            if "llm" in sig.parameters:
                kwargs["llm"] = llm
            if embeddings and "embeddings" in sig.parameters:
                kwargs["embeddings"] = embeddings
            result = evaluate(dataset, **kwargs)
        except TypeError:
            result = evaluate(dataset, metrics=selected_metrics)

        df = result.to_pandas()
        results = []
        for _, row in df.iterrows():
            case_result = {}
            for metric_name in req.metrics:
                value = row.get(metric_name, 0.0)
                try:
                    fval = float(value)
                    case_result[metric_name] = 0.0 if math.isnan(fval) else fval
                except Exception:
                    case_result[metric_name] = 0.0
            results.append(case_result)

        avg = {}
        for metric_name in req.metrics:
            values = [r.get(metric_name, 0.0) for r in results]
            avg[metric_name] = sum(values) / len(values) if values else 0.0

        elapsed_ms = int((time.time() - start_time) * 1000)
        return {
            "results": results,
            "avg": avg,
            "ragas_version": ragas.__version__,
            "eval_duration_ms": elapsed_ms,
            "language": req.language,
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ragas evaluation failed: {str(e)}\n{traceback.format_exc()}")
