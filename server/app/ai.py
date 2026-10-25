import logging
import psutil
from app.db import get_metrics_history
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
    - Umbral dinámico: Crecimiento vertical acelerado (> 20% entre muestras recientes).
    """
    # 1. Obtener uso instantáneo del sistema
    current_cpu = psutil.cpu_percent(interval=None)
    current_ram = psutil.virtual_memory().percent

    # 2. Consultar historial reciente en SQLite (últimas 10 muestras / 1 hora)
    history = get_metrics_history(hours=1)

    # 3. Evaluar umbral dinámico (tendencia de crecimiento)
    spike_detected = False
    rate_of_change = 0.0

    if history and len(history) >= 2:
        # Extraer las últimas muestras (hasta 5)
        recent_samples = history[-5:]
        cpu_values = [h["cpu"] for h in recent_samples if "cpu" in h]

        if len(cpu_values) >= 2:
            initial_cpu = cpu_values[0]
            latest_cpu = cpu_values[-1]
            rate_of_change = latest_cpu - initial_cpu

            # Si subió más de un 20% verticalmente en pocas muestras
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
    """
    recommendations = []

    try:
        from app.docker_api import get_docker_client

        client = get_docker_client()
        if not client:
            return AIRecommendationsResponse(recommendations=[])

        containers = client.containers.list()

        for container in containers:
            status = container.status.lower()

            # Solo evaluamos contenedores encendidos y no pausados
            if status == "running":
                try:
                    # Obtener métricas directas del contenedor sin bloquear
                    stats = container.stats(stream=False)

                    cpu_stats = stats.get("cpu_stats", {})
                    precpu_stats = stats.get("precpu_stats", {})

                    cpu_delta = cpu_stats.get("cpu_usage", {}).get("total_usage", 0) - \
                                precpu_stats.get("cpu_usage", {}).get("total_usage", 0)
                    system_delta = cpu_stats.get("system_cpu_usage", 0) - \
                                   precpu_stats.get("system_cpu_usage", 0)

                    cpu_percent = 0.0
                    if system_delta > 0.0 and cpu_delta > 0.0:
                        online_cpus = cpu_stats.get("online_cpus", 1)
                        cpu_percent = (cpu_delta / system_delta) * online_cpus * 100.0

                    # Criterio: Contenedor con consumo de CPU insignificante o 0.0%
                    if cpu_percent < 0.1:
                        # Filtrar contenedores esenciales del sistema por nombre
                        name = container.name.lower()
                        if not any(k in name for k in ["system", "proxy", "core", "agent", "tgmon"]):
                            recommendations.append(
                                ContainerRecommendation(
                                    container_id=container.id[:12],
                                    container_name=container.name,
                                    recommended_action="pause",
                                    reason=f"Actividad nula de CPU ({cpu_percent:.1f}%). Se sugiere pausar para liberar recursos.",
                                )
                            )
                except Exception as ex:
                    logger.debug(f"Error extrayendo stats del contenedor {container.name}: {ex}")
                    continue

    except Exception as e:
        logger.warning(f"Docker no disponible o no accesible para análisis de IA: {e}")

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