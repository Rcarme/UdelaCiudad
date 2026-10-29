import logging
import os
import math
import re
import time
import psutil

try:
    from db import get_history
    from models import (
        AIPredictionResponse,
        ContainerRecommendation,
        AIRecommendationsResponse,
        ForecastPoint,
        MetricForecast,
        ForecastResponse,
    )
except ImportError:
    from app.db import get_history
    from app.models import (
        AIPredictionResponse,
        ContainerRecommendation,
        AIRecommendationsResponse,
        ForecastPoint,
        MetricForecast,
        ForecastResponse,
    )

logger = logging.getLogger("tgmon.ai")

# --- Configuración de Gemini Flash y Caché Inteligente ---
GEMINI_MODEL = "gemini-3.8-flash"
LLM_CACHE_TTL_SECONDS = 120.0  # 2 minutos de caché inteligente para proteger cuota gratuita

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


def _sanitize_prompt(text: str) -> str:
    """Enmascara cualquier posible secreto o token antes de enviarlo a la IA."""
    try:
        return _SENSITIVE_PATTERN.sub(r"\1=***REDACTED***", text)
    except Exception:
        return text


def clear_ai_cache() -> None:
    """Limpia el caché inteligente de IA (útil para tests y recargas forzadas)."""
    _llm_prediction_cache["timestamp"] = 0.0
    _llm_prediction_cache["response"] = None
    _llm_recommendations_cache["timestamp"] = 0.0
    _llm_recommendations_cache["response"] = None


def _call_gemini_structured(prompt: str, schema_model):
    """
    Invoca a Gemini Flash bajo demanda con Structured Output (JSON Schema de Pydantic).
    Usa el SDK oficial google-genai o urllib.request nativo de Python (sin requerir pip extra).
    Retorna None si no hay GEMINI_API_KEY o si ocurre cualquier error de red/cuota.
    """
    import json
    import urllib.request
    import urllib.error

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        # Leer en vivo desde .env.api o .env (por si docker-compose inyectó GEMINI_API_KEY="" vacío)
        for candidate in (
            os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env.api"),
            os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"),
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env"),
        ):
            try:
                if os.path.exists(candidate):
                    with open(candidate, "r", encoding="utf-8") as f:
                        for line in f:
                            s = line.strip()
                            if s.startswith("GEMINI_API_KEY="):
                                val = s.split("=", 1)[1].strip().strip('"').strip("'")
                                if val and val != "tu_clave_aqui" and val != "your_gemini_api_key_here":
                                    api_key = val
                                    break
                if api_key:
                    break
            except Exception:
                pass

    if not api_key:
        logger.warning("GEMINI_API_KEY vacía o no definida en .env.api/.env; usando motor heurístico local.")
        return None

    safe_prompt = _sanitize_prompt(prompt)
    schema_dict = schema_model.model_json_schema()

    # 1. Intentar con el SDK oficial google-genai si está instalado
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
        logger.debug(f"SDK google-genai no disponible o falló ({sdk_err}), probando cliente REST nativo...")

    # 2. Cliente REST nativo con urllib.request (cero dependencias externas en el contenedor)
    headers = {
        "Content-Type": "application/json",
        "x-goog-api-key": api_key,
    }

    # 2. Endpoint REST de Google AI Studio con urllib.request (cero dependencias externas)
    schema_prompt = (
        f"{safe_prompt}\n\n"
        f"Responde ÚNICAMENTE con un objeto JSON válido que cumpla este esquema exacto:\n"
        f"{json.dumps(schema_dict, ensure_ascii=False)}"
    )

    last_err = None
    for model_name in ("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-flash-latest"):
        try:
            url_v1b = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent"
            gen_config: dict = {
                "responseMimeType": "application/json",
                "temperature": 0.2,
            }

            payload_v1b = json.dumps({
                "contents": [{"parts": [{"text": schema_prompt}]}],
                "generationConfig": gen_config,
            }).encode("utf-8")

            req = urllib.request.Request(url_v1b, data=payload_v1b, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=15.0) as resp:
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
                err_body = http_err.read().decode("utf-8")[:160]
            except Exception:
                pass
            last_err = f"HTTP {http_err.code} en {model_name} ({err_body})"
            if http_err.code in (404, 400, 429, 503):
                continue
            break
        except Exception as model_err:
            last_err = f"{model_err} en {model_name}"
            continue

    if last_err:
        logger.warning(f"Fallback local activado tras error REST en Gemini: {last_err}")
    return None

    return None


def predict_resource_spikes(use_llm: bool = False) -> AIPredictionResponse:
    """
    Analiza métricas actuales e históricas de CPU y RAM para predecir saturaciones.
    - Por defecto (use_llm=False): usa el motor heurístico local en <1ms sin consumir cuota.
    - Bajo demanda (use_llm=True): consulta a Gemini Flash con caché inteligente y fallback local.
    """
    # 1. Obtener uso instantáneo del sistema
    current_cpu = psutil.cpu_percent(interval=None)
    current_ram = psutil.virtual_memory().percent

    # 2. Consultar historial reciente en SQLite (última hora)
    history = get_history(hours=1)

    # 3. Evaluar umbral dinámico (tendencia de crecimiento incluyendo lectura actual)
    spike_detected = False
    rate_of_change = 0.0
    cpu_values = []

    if history:
        recent_samples = history[-5:]
        cpu_values = [h.get("cpu_percent", h.get("cpu", 0.0)) for h in recent_samples]
        cpu_values.append(current_cpu)

        if len(cpu_values) >= 2:
            initial_cpu = cpu_values[0]
            latest_cpu = cpu_values[-1]
            rate_of_change = latest_cpu - initial_cpu

            if rate_of_change >= 20.0:
                spike_detected = True

    # 4. Calcular respuesta heurística local base (siempre lista en <1ms como fallback)
    if current_cpu >= 85.0 or current_ram >= 85.0:
        risk_level = "critical"
        message = (
            f"Alerta Crítica: Recursos saturados (CPU: {current_cpu}%, RAM: {current_ram}%). "
            f"Riesgo inminente de degradación del sistema."
        )
    elif spike_detected:
        risk_level = "high"
        message = (
            f"Alerta Predictiva: Crecimiento acelerado de CPU detectado (+{rate_of_change:.1f}%). "
            f"Posible cuello de botella en los próximos minutos."
        )
    elif current_cpu >= 70.0 or current_ram >= 70.0:
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

    # 5. Modo bajo demanda (use_llm=True): revisar caché inteligente primero
    now = time.time()
    cached_resp = _llm_prediction_cache.get("response")
    cache_age = now - _llm_prediction_cache.get("timestamp", 0.0)
    cpu_diff = abs(current_cpu - _llm_prediction_cache.get("cpu_at_cache", 0.0))
    ram_diff = abs(current_ram - _llm_prediction_cache.get("ram_at_cache", 0.0))

    if cached_resp is not None and cache_age < LLM_CACHE_TTL_SECONDS and cpu_diff < 10.0 and ram_diff < 10.0:
        return cached_resp

    # 6. Consultar Gemini Flash con contexto real y fallback transparente
    prompt = (
        "Eres un ingeniero SRE experto monitoreando un servidor Linux con Docker (TGMon).\n"
        f"Métricas actuales: CPU={current_cpu:.1f}%, RAM={current_ram:.1f}%.\n"
        f"Serie reciente de CPU: {cpu_values if cpu_values else [current_cpu]}.\n"
        f"Variación reciente de CPU: {rate_of_change:+.1f}%.\n"
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
    - Por defecto (use_llm=False): usa el caché asíncrono local en <1ms sin consumir cuota.
    - Bajo demanda (use_llm=True): consulta a Gemini Flash con caché inteligente y fallback local.
    """
    recommendations = []
    containers_data = []

    try:
        try:
            from docker_api import cached_containers, _cache_lock, client
        except ImportError:
            from app.docker_api import cached_containers, _cache_lock, client

        if not client:
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

        # 1. Obtener contenedores desde el caché de TGMon (no bloqueante, ultra rápido)
        try:
            with _cache_lock:
                containers_data = list(cached_containers)
        except Exception:
            containers_data = []

        # 2. Si el caché aún está vacío (recién arrancó el server), consultar lista rápida
        if not containers_data and client:
            try:
                for c in client.containers.list():
                    containers_data.append({
                        "id": c.id,
                        "name": c.name,
                        "status": c.status.lower(),
                        "cpu_percent": 0.0,
                    })
            except Exception as e:
                logger.debug(f"Error listando contenedores: {e}")

        # 3. Analizar contenedores heurísticamente (fallback base)
        for c in containers_data:
            status = str(c.get("status", "")).lower()

            if status == "running":
                name = str(c.get("name", ""))
                cid = str(c.get("id", ""))[:12]
                cpu_percent = float(c.get("cpu_percent", 0.0))

                if cpu_percent < 0.1:
                    name_lower = name.lower()
                    if not any(k in name_lower for k in ["system", "proxy", "core", "agent", "tgmon"]):
                        recommendations.append(
                            ContainerRecommendation(
                                container_id=cid,
                                container_name=name,
                                recommended_action="pause",
                                reason=f"Actividad nula de CPU ({cpu_percent:.1f}%). Se sugiere pausar para liberar recursos.",
                            )
                        )

    except Exception as e:
        logger.warning(f"Error en análisis de IA de contenedores: {e}")

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

    # 4. Modo bajo demanda (use_llm=True): revisar caché inteligente primero
    now = time.time()
    cached_recs = _llm_recommendations_cache.get("response")
    cache_age = now - _llm_recommendations_cache.get("timestamp", 0.0)
    same_count = len(containers_data) == _llm_recommendations_cache.get("container_count", -1)

    if cached_recs is not None and cache_age < LLM_CACHE_TTL_SECONDS and same_count:
        return cached_recs

    # 5. Consultar Gemini Flash si hay contenedores para analizar
    if containers_data:
        summary_lines = []
        for c in containers_data[:25]:
            cid = str(c.get("id", ""))[:12]
            cname = str(c.get("name", ""))
            cstatus = str(c.get("status", ""))
            ccpu = float(c.get("cpu_percent", 0.0))
            cmem = float(c.get("memory_mb", 0.0))
            summary_lines.append(f"- id={cid}, name={cname}, status={cstatus}, cpu={ccpu:.1f}%, ram={cmem:.1f}MB")

        prompt = (
            "Eres un ingeniero SRE optimizando contenedores Docker en un servidor Linux.\n"
            "Lista de contenedores actuales:\n"
            + "\n".join(summary_lines)
            + "\n\nGenera recomendaciones de optimización en español. "
            "No sugieras pausar contenedores esenciales ('system', 'proxy', 'core', 'agent', 'tgmon'). "
            "Para contenedores en 'running' con 0.0% de CPU no esenciales, sugiere recommended_action='pause' "
            "y usa exactamente la misma frase en 'reason' para los que compartan el mismo diagnóstico "
            "(iniciando con '✨ [Gemini IA] '), de modo que la interfaz pueda agruparlos. "
            "Si todos están bien, devuelve un único elemento con container_id='sys_opt', "
            "container_name='Contenedores Activos', recommended_action='none'."
        )

        llm_recs = _call_gemini_structured(prompt, AIRecommendationsResponse)
        if llm_recs is not None and llm_recs.recommendations:
            _llm_recommendations_cache["timestamp"] = now
            _llm_recommendations_cache["container_count"] = len(containers_data)
            _llm_recommendations_cache["response"] = llm_recs
            return llm_recs

    return heuristic_result


# ---------------------------------------------------------------------------
# Pronóstico de series temporales (regresión lineal por mínimos cuadrados)
# ---------------------------------------------------------------------------
FORECAST_CRITICAL_THRESHOLD = 85.0   # mismo umbral "critical" que predict_resource_spikes
FORECAST_MIN_SAMPLES = 3
FORECAST_ETA_MAX_MINUTES = 24 * 60   # no se informa un ETA más lejano que 24 h


def _linear_forecast(
    samples: list[tuple[int, float]],
    now_ms: int,
    horizon_minutes: int,
    step_minutes: int,
    metric: str,
    threshold: float = FORECAST_CRITICAL_THRESHOLD,
) -> MetricForecast | None:
    """
    Ajusta y = a + b*x por mínimos cuadrados (OLS), con x en minutos relativos a "ahora"
    (x <= 0 para el pasado), y proyecta hasta `horizon_minutes`.
    El intervalo de predicción al 95% usa el error estándar de los residuos:
        y_hat +/- 1.96 * s * sqrt(1 + 1/n + (x - x_media)^2 / Sxx)
    Devuelve None si la serie es degenerada (menos de 3 puntos o todos con el mismo timestamp).
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
    intercept = y_mean - slope * x_mean          # valor ajustado en x = 0 (ahora)

    ss_tot = sum((y - y_mean) ** 2 for y in ys)
    ss_res = sum((y - (intercept + slope * x)) ** 2 for x, y in zip(xs, ys))
    r_squared = 1.0 - (ss_res / ss_tot) if ss_tot > 1e-9 else 0.0
    r_squared = max(0.0, min(1.0, r_squared))
    resid_std = math.sqrt(ss_res / (n - 2)) if n > 2 else 0.0

    def clamp(v: float) -> float:
        return max(0.0, min(100.0, v))

    points: list[ForecastPoint] = []
    for minute in range(0, horizon_minutes + 1, step_minutes):
        y_hat = intercept + slope * minute
        margin = 1.96 * resid_std * math.sqrt(1.0 + 1.0 / n + ((minute - x_mean) ** 2) / sxx)
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
    elif slope > 1e-6:
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
    Es 100% local (sin LLM, sin cuota de API): corre en milisegundos.
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
