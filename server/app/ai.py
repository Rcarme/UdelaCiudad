import json
import logging
import math
import os
import re
import threading
import time
import urllib.error
import urllib.request
import psutil

from db import get_history
from models import (
    AIPredictionResponse,
    AIRecommendationsResponse,
    ContainerRecommendation,
    ForecastPoint,
    ForecastResponse,
    MetricForecast,
)

logger = logging.getLogger("tgmon.ai")

# --- Umbrales y Constantes de Configuración ---
CRITICAL_USAGE_THRESHOLD = 85.0
MODERATE_USAGE_THRESHOLD = 70.0
SPIKE_DELTA_THRESHOLD = 20.0
SPIKE_WINDOW_MINUTES = 15
IDLE_CPU_THRESHOLD = 0.1

FORECAST_CRITICAL_THRESHOLD = CRITICAL_USAGE_THRESHOLD
FORECAST_MIN_SAMPLES = 3
FORECAST_ETA_MAX_MINUTES = 24 * 60
FORECAST_MIN_R_SQUARED_FOR_ETA = 0.25

ESSENTIAL_CONTAINER_TOKENS = frozenset({"system", "proxy", "core", "agent", "tgmon"})

GEMINI_MODEL = os.getenv("TGMON_GEMINI_MODEL", "gemini-3.8-flash")
GEMINI_FALLBACK_MODELS = (
    GEMINI_MODEL,
    "gemini-3.5-flash-lite",
    "gemini-flash-lite-latest",
)
GEMINI_REST_TIMEOUT_SECONDS = 6.0
LLM_CACHE_TTL_SECONDS = 120.0

_cache_lock = threading.Lock()

_llm_prediction_cache: dict = {
    "timestamp": 0.0,
    "cpu_at_cache": 0.0,
    "ram_at_cache": 0.0,
    "response": None,
}

_llm_recommendations_cache: dict = {
    "timestamp": 0.0,
    "container_count": -1,
    "response": None,
}

_SENSITIVE_PATTERN = re.compile(
    r'(?i)(password|passwd|secret|token|api[_-]?key|credential|auth)\s*[:=]\s*[^\s\'"`,;]+'
)

# Valores críticos exactos de t de Student (dos colas, 95% -> alpha/2 = 0.025) para df = 1..30
_STUDENT_T_975: dict[int, float] = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
    11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131,
    16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093, 20: 2.086,
    21: 2.080, 22: 2.074, 23: 2.069, 24: 2.064, 25: 2.060,
    26: 2.056, 27: 2.052, 28: 2.048, 29: 2.045, 30: 2.042,
}


def _student_t_975(df: int) -> float:
    """Devuelve el cuantil t_{0.975, df} de Student (o 1.96 para df > 30)."""
    if df <= 0:
        return 1.96
    return _STUDENT_T_975.get(df, 1.96)


def _sanitize_prompt(text: str) -> str:
    """Enmascara cualquier posible secreto o token antes de enviarlo a la IA."""
    return _SENSITIVE_PATTERN.sub(r"\1=***REDACTED***", text)


def _is_essential_container(name: str) -> bool:
    """Determina si un contenedor es esencial separando su nombre por delimitadores."""
    tokens = set(re.split(r"[^a-z0-9]+", name.lower()))
    return bool(tokens & ESSENTIAL_CONTAINER_TOKENS)


def clear_ai_cache() -> None:
    """Limpia de forma thread-safe todos los campos del caché inteligente de IA."""
    with _cache_lock:
        _llm_prediction_cache.update({
            "timestamp": 0.0,
            "cpu_at_cache": 0.0,
            "ram_at_cache": 0.0,
            "response": None,
        })
        _llm_recommendations_cache.update({
            "timestamp": 0.0,
            "container_count": -1,
            "response": None,
        })


def _get_api_key() -> str:
    """Obtiene GEMINI_API_KEY del entorno o de archivos .env locales."""
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if api_key:
        return api_key

    base_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = (
        os.path.join(base_dir, ".env.api"),
        os.path.join(base_dir, ".env"),
        os.path.join(base_dir, "..", ".env"),
    )
    placeholders = {"tu_clave_aqui", "your_gemini_api_key_here"}

    for candidate in candidates:
        try:
            if os.path.exists(candidate):
                with open(candidate, "r", encoding="utf-8") as f:
                    for line in f:
                        s = line.strip()
                        if s.startswith("GEMINI_API_KEY="):
                            val = s.split("=", 1)[1].strip().strip('"').strip("'")
                            if val and val not in placeholders:
                                return val
        except OSError as exc:
            logger.debug("No se pudo leer %s: %s", candidate, exc)

    return ""


def _call_gemini_sdk(api_key: str, safe_prompt: str, schema_dict: dict, schema_model):
    """Intenta invocar Gemini mediante el SDK oficial google-genai si está instalado."""
    try:
        from google import genai

        client = genai.Client(api_key=api_key)
        interaction = client.interactions.create(
            model=GEMINI_MODEL,
            input=safe_prompt,
            response_format={
                "type": "text",
                "mime_type": "application/json",
                "schema": schema_dict,
            },
            store=False,
        )
        if interaction and getattr(interaction, "output_text", None):
            return schema_model.model_validate_json(interaction.output_text)
    except ImportError:
        pass
    except Exception as sdk_err:
        logger.debug("SDK google-genai falló (%s), usando cliente REST...", sdk_err)
    return None


def _call_gemini_rest(api_key: str, safe_prompt: str, schema_dict: dict, schema_model):
    """Invoca el endpoint REST de Gemini con timeout acotado por modelo."""
    headers = {
        "Content-Type": "application/json",
        "x-goog-api-key": api_key,
    }
    schema_prompt = (
        f"{safe_prompt}\n\n"
        f"Responde ÚNICAMENTE con un objeto JSON válido que cumpla este esquema exacto:\n"
        f"{json.dumps(schema_dict, ensure_ascii=False)}"
    )
    payload = json.dumps({
        "contents": [{"parts": [{"text": schema_prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "temperature": 0.2,
        },
    }).encode("utf-8")

    last_err = None
    seen_models: set[str] = set()
    for model_name in GEMINI_FALLBACK_MODELS:
        if model_name in seen_models:
            continue
        seen_models.add(model_name)
        try:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent"
            req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=GEMINI_REST_TIMEOUT_SECONDS) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                candidates = data.get("candidates", [])
                if candidates:
                    parts = candidates[0].get("content", {}).get("parts", [])
                    for part in parts:
                        if part.get("text"):
                            return schema_model.model_validate_json(part["text"])
        except urllib.error.HTTPError as http_err:
            err_body = ""
            try:
                err_body = http_err.read().decode("utf-8", errors="replace")[:160]
            except OSError:
                pass
            last_err = f"HTTP {http_err.code} en {model_name} ({err_body})"
            if http_err.code in (400, 404, 429, 503):
                continue
            break
        except Exception as model_err:
            last_err = f"{model_err} en {model_name}"
            continue

    if last_err:
        logger.warning("Fallback local activado tras error REST en Gemini: %s", last_err)
    return None


def _call_gemini_structured(prompt: str, schema_model):
    """
    Invoca a Gemini Flash bajo demanda con validación Pydantic.
    Retorna None si no hay GEMINI_API_KEY o si ocurre cualquier error de red/cuota.
    """
    api_key = _get_api_key()
    if not api_key:
        logger.warning("GEMINI_API_KEY no definida; usando motor heurístico local.")
        return None

    safe_prompt = _sanitize_prompt(prompt)
    schema_dict = schema_model.model_json_schema()

    sdk_result = _call_gemini_sdk(api_key, safe_prompt, schema_dict, schema_model)
    if sdk_result is not None:
        return sdk_result

    return _call_gemini_rest(api_key, safe_prompt, schema_dict, schema_model)


def predict_resource_spikes(use_llm: bool = False) -> AIPredictionResponse:
    """
    Analiza métricas actuales e históricas de CPU y RAM para predecir saturaciones.
    - Por defecto (use_llm=False): usa el motor heurístico local en <1ms sin consumir cuota.
    - Bajo demanda (use_llm=True): consulta a Gemini Flash con caché inteligente y fallback local.
    """
    current_cpu = psutil.cpu_percent(interval=None)
    current_ram = psutil.virtual_memory().percent

    history = get_history(hours=1)

    spike_detected = False
    cpu_delta = 0.0
    ram_delta = 0.0
    cpu_values: list[float] = []

    if history:
        now_ms = int(time.time() * 1000)
        window_cutoff = now_ms - (SPIKE_WINDOW_MINUTES * 60000)
        window_samples = [
            h for h in history
            if h.get("timestamp", 0) >= window_cutoff
        ]
        if not window_samples:
            window_samples = history[-5:]

        cpu_values = [float(h.get("cpu_percent", h.get("cpu", 0.0)) or 0.0) for h in window_samples]
        ram_values = [float(h.get("memory_percent", h.get("ram", 0.0)) or 0.0) for h in window_samples]
        cpu_values.append(current_cpu)
        ram_values.append(current_ram)

        if len(cpu_values) >= 2:
            cpu_delta = cpu_values[-1] - cpu_values[0]
            ram_delta = ram_values[-1] - ram_values[0]
            if cpu_delta >= SPIKE_DELTA_THRESHOLD or ram_delta >= SPIKE_DELTA_THRESHOLD:
                spike_detected = True

    if current_cpu >= CRITICAL_USAGE_THRESHOLD or current_ram >= CRITICAL_USAGE_THRESHOLD:
        risk_level = "critical"
        message = (
            f"Alerta Crítica: Recursos saturados (CPU: {current_cpu}%, RAM: {current_ram}%). "
            f"Riesgo inminente de degradación del sistema."
        )
    elif spike_detected:
        risk_level = "high"
        dominant_metric = "CPU" if cpu_delta >= ram_delta else "RAM"
        dominant_delta = max(cpu_delta, ram_delta)
        message = (
            f"Alerta Predictiva: Crecimiento acelerado de {dominant_metric} detectado (+{dominant_delta:.1f}%). "
            f"Posible cuello de botella en los próximos minutos."
        )
    elif current_cpu >= MODERATE_USAGE_THRESHOLD or current_ram >= MODERATE_USAGE_THRESHOLD:
        risk_level = "medium"
        message = (
            f"Consumo Moderado: Uso elevado sostenido (CPU: {current_cpu}%, RAM: {current_ram}%). "
            f"Monitoreo recomendado."
        )
    else:
        risk_level = "low"
        message = "Sistema funcionando de manera óptima y estable. Sin riesgos previstos."

    heuristic_response = AIPredictionResponse(
        risk_level=risk_level,
        message=message,
        cpu_usage=round(current_cpu, 1),
        memory_usage=round(current_ram, 1),
    )

    if not use_llm:
        return heuristic_response

    now = time.time()
    with _cache_lock:
        cached_resp = _llm_prediction_cache.get("response")
        cache_age = now - _llm_prediction_cache.get("timestamp", 0.0)
        cpu_diff = abs(current_cpu - _llm_prediction_cache.get("cpu_at_cache", 0.0))
        ram_diff = abs(current_ram - _llm_prediction_cache.get("ram_at_cache", 0.0))

    if cached_resp is not None and cache_age < LLM_CACHE_TTL_SECONDS and cpu_diff < 10.0 and ram_diff < 10.0:
        return cached_resp

    prompt = (
        "Eres un ingeniero SRE experto monitoreando un servidor Linux con Docker (TGMon).\n"
        f"Métricas actuales: CPU={current_cpu:.1f}%, RAM={current_ram:.1f}%.\n"
        f"Serie reciente de CPU: {cpu_values if cpu_values else [current_cpu]}.\n"
        f"Variación reciente: CPU={cpu_delta:+.1f}%, RAM={ram_delta:+.1f}%.\n"
        "Genera un diagnóstico conciso en español (máximo 180 caracteres en 'message', "
        "iniciando con '✨ [Gemini IA] '). Clasifica 'risk_level' estrictamente en: "
        "'low', 'medium', 'high' o 'critical'. Incluye cpu_usage y memory_usage actuales."
    )

    llm_res = _call_gemini_structured(prompt, AIPredictionResponse)
    if llm_res is not None:
        msg = llm_res.message.strip()
        if not msg.startswith("✨"):
            msg = f"✨ [Gemini IA] {msg}"
        final_res = AIPredictionResponse(
            risk_level=llm_res.risk_level.lower() if llm_res.risk_level else risk_level,
            message=msg,
            cpu_usage=round(current_cpu, 1),
            memory_usage=round(current_ram, 1),
        )
        with _cache_lock:
            _llm_prediction_cache["timestamp"] = now
            _llm_prediction_cache["cpu_at_cache"] = current_cpu
            _llm_prediction_cache["ram_at_cache"] = current_ram
            _llm_prediction_cache["response"] = final_res
        return final_res

    return heuristic_response


def analyze_idle_containers(use_llm: bool = False) -> AIRecommendationsResponse:
    """
    Examina los contenedores activos de Docker para detectar servicios inactivos
    y generar sugerencias de optimización orientadas a la acción (pause/stop).
    """
    recommendations: list[ContainerRecommendation] = []
    containers_data: list[dict] = []

    try:
        import docker_api

        if not docker_api.client:
            return AIRecommendationsResponse(
                recommendations=[
                    ContainerRecommendation(
                        container_id="sys_opt",
                        container_name="Contenedores Activos",
                        recommended_action="none",
                        reason="Docker no disponible o no accesible.",
                    )
                ]
            )

        # 1. Obtener contenedores mediante función thread-safe del módulo docker_api
        try:
            containers_data = docker_api.get_cached_containers()
        except Exception as exc:
            logger.debug("Error leyendo caché de contenedores: %s", exc)
            containers_data = []

        # 2. Si el caché aún está vacío, listar contenedores marcando cpu_percent=None
        #    para NO recomendar pausas sin mediciones reales de CPU.
        if not containers_data and docker_api.client:
            try:
                for c in docker_api.client.containers.list():
                    containers_data.append({
                        "id": c.id,
                        "name": c.name,
                        "status": c.status.lower(),
                        "cpu_percent": None,
                    })
            except Exception as exc:
                logger.debug("Error listando contenedores en frío: %s", exc)

        # 3. Analizar contenedores heurísticamente sólo cuando existe medición real de CPU
        for c in containers_data:
            status = str(c.get("status", "")).lower()
            if status != "running":
                continue

            cpu_raw = c.get("cpu_percent")
            if cpu_raw is None:
                continue

            cpu_percent = float(cpu_raw)
            name = str(c.get("name", ""))
            cid = str(c.get("id", ""))[:12]

            if cpu_percent < IDLE_CPU_THRESHOLD and not _is_essential_container(name):
                recommendations.append(
                    ContainerRecommendation(
                        container_id=cid,
                        container_name=name,
                        recommended_action="pause",
                        reason=f"Actividad nula de CPU ({cpu_percent:.1f}%). Se sugiere pausar para liberar recursos.",
                    )
                )

    except Exception as exc:
        logger.warning("Error en análisis de IA de contenedores: %s", exc)

    if not recommendations:
        recommendations.append(
            ContainerRecommendation(
                container_id="sys_opt",
                container_name="Contenedores Activos",
                recommended_action="none",
                reason="Todos los contenedores en ejecución presentan actividad equilibrada.",
            )
        )

    heuristic_result = AIRecommendationsResponse(recommendations=recommendations)

    if not use_llm:
        return heuristic_result

    now = time.time()
    with _cache_lock:
        cached_recs = _llm_recommendations_cache.get("response")
        cache_age = now - _llm_recommendations_cache.get("timestamp", 0.0)
        same_count = len(containers_data) == _llm_recommendations_cache.get("container_count", -1)

    if cached_recs is not None and cache_age < LLM_CACHE_TTL_SECONDS and same_count:
        return cached_recs

    measured_containers = [c for c in containers_data if c.get("cpu_percent") is not None]
    if measured_containers:
        summary_lines = []
        for c in measured_containers[:25]:
            cid = str(c.get("id", ""))[:12]
            cname = str(c.get("name", ""))
            cstatus = str(c.get("status", ""))
            ccpu = float(c.get("cpu_percent", 0.0))
            cmem = float(c.get("memory_mb", 0.0))
            summary_lines.append(f"- id={cid}, name={cname}, status={cstatus}, cpu={ccpu:.1f}%, ram={cmem:.1f}MB")

        essential_list_str = ", ".join(sorted(ESSENTIAL_CONTAINER_TOKENS))
        prompt = (
            "Eres un ingeniero SRE optimizando contenedores Docker en un servidor Linux.\n"
            "Lista de contenedores actuales:\n"
            + "\n".join(summary_lines)
            + "\n\nGenera recomendaciones de optimización en español. "
            f"No sugieras pausar contenedores esenciales ({essential_list_str}). "
            "Para contenedores en 'running' con 0.0% de CPU no esenciales, sugiere recommended_action='pause' "
            "y usa exactamente la misma frase en 'reason' para los que compartan el mismo diagnóstico "
            "(iniciando con '✨ [Gemini IA] '), de modo que la interfaz pueda agruparlos. "
            "Si todos están bien, devuelve un único elemento con container_id='sys_opt', "
            "container_name='Contenedores Activos', recommended_action='none'."
        )

        llm_recs = _call_gemini_structured(prompt, AIRecommendationsResponse)
        if llm_recs is not None and llm_recs.recommendations:
            with _cache_lock:
                _llm_recommendations_cache["timestamp"] = now
                _llm_recommendations_cache["container_count"] = len(containers_data)
                _llm_recommendations_cache["response"] = llm_recs
            return llm_recs

    return heuristic_result


def _linear_forecast(
    samples: list[tuple[int, float]],
    now_ms: int,
    horizon_minutes: int,
    step_minutes: int,
    metric: str,
    threshold: float = FORECAST_CRITICAL_THRESHOLD,
) -> MetricForecast | None:
    """
    Ajusta y = a + b*x por mínimos cuadrados ordinarios (OLS), con x en minutos
    relativos a "ahora" (x <= 0 para el pasado), y proyecta hasta `horizon_minutes`.
    El intervalo de predicción al 95% utiliza el cuantil t de Student con n-2 grados de libertad:
        y_hat +/- t_{0.975, n-2} * s * sqrt(1 + 1/n + (x - x_media)^2 / Sxx)
    """
    n = len(samples)
    if n < FORECAST_MIN_SAMPLES:
        return None

    xs = [(ts - now_ms) / 60000.0 for ts, _ in samples]
    ys = [float(v) for _, v in samples]

    x_mean = sum(xs) / n
    y_mean = sum(ys) / n
    sxx = sum((x - x_mean) ** 2 for x in xs)
    if sxx <= 1e-9:
        return None

    sxy = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys))
    slope = sxy / sxx
    intercept = y_mean - slope * x_mean

    ss_tot = sum((y - y_mean) ** 2 for y in ys)
    ss_res = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys))
    r_squared = 1.0 - (ss_res / ss_tot) if ss_tot > 1e-9 else 0.0
    r_squared = max(0.0, min(1.0, r_squared))
    resid_std = math.sqrt(ss_res / (n - 2)) if n > 2 else 0.0
    t_crit = _student_t_975(n - 2)

    def clamp(v: float) -> float:
        return max(0.0, min(100.0, v))

    points: list[ForecastPoint] = []
    for minute in range(0, horizon_minutes + 1, step_minutes):
        y_hat = intercept + slope * minute
        margin = t_crit * resid_std * math.sqrt(1.0 + 1.0 / n + ((minute - x_mean) ** 2) / sxx)
        points.append(
            ForecastPoint(
                timestamp=int(now_ms + minute * 60000),
                value=round(clamp(y_hat), 2),
                lower=round(clamp(y_hat - margin), 2),
                upper=round(clamp(y_hat + margin), 2),
            )
        )

    change_over_horizon = slope * horizon_minutes
    if abs(change_over_horizon) < 2.0:
        trend = "stable"
    else:
        trend = "rising" if slope > 0 else "falling"

    minutes_to_threshold = None
    if intercept >= threshold:
        minutes_to_threshold = 0.0
    elif trend == "rising" and slope > 1e-6 and r_squared >= FORECAST_MIN_R_SQUARED_FOR_ETA:
        eta = (threshold - intercept) / slope
        if eta <= FORECAST_ETA_MAX_MINUTES:
            minutes_to_threshold = round(eta, 1)

    return MetricForecast(
        metric=metric,
        slope_per_min=round(slope, 4),
        r_squared=round(r_squared, 3),
        trend=trend,
        threshold=threshold,
        minutes_to_threshold=minutes_to_threshold,
        points=points,
    )


def forecast_resources(
    window_minutes: int = 60,
    horizon_minutes: int = 30,
    step_minutes: int = 5,
) -> ForecastResponse:
    """
    Proyecta CPU y RAM a partir de las últimas `window_minutes` de telemetría en SQLite.
    100% local (sin LLM, sin cuota de API): corre en milisegundos.
    """
    now_ms = int(time.time() * 1000)
    history = get_history(hours=max(1, math.ceil(window_minutes / 60)))
    cutoff = now_ms - window_minutes * 60000
    recent = [h for h in history if h.get("timestamp", 0) >= cutoff]

    cpu_samples = [(h["timestamp"], h.get("cpu_percent", 0.0) or 0.0) for h in recent]
    mem_samples = [(h["timestamp"], h.get("memory_percent", 0.0) or 0.0) for h in recent]

    cpu_fc = _linear_forecast(cpu_samples, now_ms, horizon_minutes, step_minutes, "cpu")
    mem_fc = _linear_forecast(mem_samples, now_ms, horizon_minutes, step_minutes, "memory")

    return ForecastResponse(
        available=cpu_fc is not None and mem_fc is not None,
        samples_used=len(recent),
        window_minutes=window_minutes,
        horizon_minutes=horizon_minutes,
        cpu=cpu_fc,
        memory=mem_fc,
    )
