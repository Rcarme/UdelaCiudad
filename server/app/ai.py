import logging
import psutil

try:
    from db import get_history
    from models import (
        AIPredictionResponse,
        ContainerRecommendation,
        AIRecommendationsResponse,
    )
except ImportError:
    from app.db import get_history
    from app.models import (
        AIPredictionResponse,
        ContainerRecommendation,
        AIRecommendationsResponse,
    )

logger = logging.getLogger("tgmon.ai")


def predict_resource_spikes() -> AIPredictionResponse:
    """
    Analiza métricas actuales e históricas de CPU y RAM para predecir saturaciones.
    Combina:
    - Umbral estático: Uso superior al 85% sostenido.
    - Umbral dinámico: Crecimiento vertical acelerado (> 20% entre muestras recientes y actual).
    """
    # 1. Obtener uso instantáneo del sistema
    current_cpu = psutil.cpu_percent(interval=None)
    current_ram = psutil.virtual_memory().percent

    # 2. Consultar historial reciente en SQLite (última hora)
    history = get_history(hours=1)

    # 3. Evaluar umbral dinámico (tendencia de crecimiento incluyendo lectura actual)
    spike_detected = False
    rate_of_change = 0.0

    if history:
        recent_samples = history[-5:]
        cpu_values = [h.get("cpu_percent", h.get("cpu", 0.0)) for h in recent_samples]
        cpu_values.append(current_cpu)

        if len(cpu_values) >= 2:
            initial_cpu = cpu_values[0]
            latest_cpu = cpu_values[-1]
            rate_of_change = latest_cpu - initial_cpu

            # Si subió más de un 20% verticalmente respecto a la base reciente
            if rate_of_change >= 20.0:
                spike_detected = True

    # 4. Determinar nivel de riesgo y armar mensaje analítico
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

    return AIPredictionResponse(
        risk_level=risk_level,
        message=message,
        cpu_usage=round(current_cpu, 1),
        memory_usage=round(current_ram, 1),
    )


def analyze_idle_containers() -> AIRecommendationsResponse:
    """
    Examina los contenedores activos de Docker para detectar servicios inactivos
    y generar sugerencias de optimización orientadas a la acción (pause/stop).
    Utiliza el caché asíncrono de Docker de TGMon para responder de forma instantánea sin bloquear.
    """
    recommendations = []

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
        containers_data = []
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

        # 3. Analizar contenedores
        for c in containers_data:
            status = str(c.get("status", "")).lower()

            # Solo evaluamos contenedores encendidos y no pausados
            if status == "running":
                name = str(c.get("name", ""))
                cid = str(c.get("id", ""))[:12]
                cpu_percent = float(c.get("cpu_percent", 0.0))

                # Criterio: Contenedor con consumo de CPU insignificante o 0.0%
                if cpu_percent < 0.1:
                    # Filtrar contenedores esenciales del sistema por nombre
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

    # Fallback descriptivo si Docker no está corriendo o no hay candidatos
    if not recommendations:
        recommendations.append(
            ContainerRecommendation(
                container_id="sys_opt",
                container_name="Contenedores Activos",
                recommended_action="none",
                reason="Todos los contenedores en ejecución presentan actividad equilibrada.",
            )
        )

    return AIRecommendationsResponse(recommendations=recommendations)
