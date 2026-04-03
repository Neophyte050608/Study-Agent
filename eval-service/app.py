from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall
from langchain_openai import ChatOpenAI

import inspect
import os
import time
import traceback
import ragas

app = FastAPI(title="Ragas Evaluation Service")


class LLMConfig(BaseModel):
    model: str = "deepseek-chat"
    base_url: str = "https://api.deepseek.com/v1"
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


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "ragas_version": ragas.__version__}


def _build_llm(req: EvalRequest) -> ChatOpenAI:
    llm_config = req.llm_config or LLMConfig()
    api_key = llm_config.api_key or os.environ.get("LLM_API_KEY", "") or os.environ.get("OPENAI_API_KEY", "")
    model = llm_config.model or os.environ.get("LLM_MODEL", "deepseek-chat")
    base_url = llm_config.base_url or os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1")

    if not api_key:
        raise HTTPException(status_code=400, detail="LLM API key is required (via request body or LLM_API_KEY env)")

    # 同步设置 OPENAI 环境变量，兼容 ragas 不同版本下的 LLM 读取路径。
    os.environ["OPENAI_API_KEY"] = api_key
    os.environ["OPENAI_BASE_URL"] = base_url

    return ChatOpenAI(model=model, api_key=api_key, base_url=base_url)


def _resolve_metrics(metric_names: list[str]) -> list:
    metric_map = {
        "faithfulness": faithfulness,
        "answer_relevancy": answer_relevancy,
        "context_precision": context_precision,
        "context_recall": context_recall,
    }
    selected = [metric_map[name] for name in metric_names if name in metric_map]
    if not selected:
        selected = [faithfulness, answer_relevancy, context_precision, context_recall]
    return selected


@app.post("/evaluate")
def run_evaluate(req: EvalRequest) -> dict:
    start_time = time.time()
    try:
        llm = _build_llm(req)
        dataset = Dataset.from_dict(
            {
                "question": [c.query for c in req.cases],
                "answer": [c.answer for c in req.cases],
                "contexts": [c.contexts for c in req.cases],
                "ground_truth": [c.ground_truth for c in req.cases],
            }
        )

        selected_metrics = _resolve_metrics(req.metrics)

        # ragas 版本差异兼容：优先尝试 llm 参数；若不支持则回退到环境变量路径。
        try:
            sig = inspect.signature(evaluate)
            if "llm" in sig.parameters:
                result = evaluate(dataset, metrics=selected_metrics, llm=llm)
            else:
                result = evaluate(dataset, metrics=selected_metrics)
        except TypeError:
            result = evaluate(dataset, metrics=selected_metrics)

        df = result.to_pandas()
        results = []
        for _, row in df.iterrows():
            case_result = {}
            for metric_name in req.metrics:
                value = row.get(metric_name, 0.0)
                try:
                    case_result[metric_name] = float(value)
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
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ragas evaluation failed: {str(e)}\n{traceback.format_exc()}")
