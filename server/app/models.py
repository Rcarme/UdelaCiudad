from pydantic import BaseModel, Field

class DockerSummary(BaseModel):
    total: int = 0
    running: int = 0
    restarting: int = 0
    stopped: int = 0

class DiskInfo(BaseModel):
    device: str = ""
    mountpoint: str = ""
    fstype: str = ""
    total_gb: float = 0.0
    used_gb: float = 0.0
    free_gb: float = 0.0
    percent: float = 0.0

class HealthResponse(BaseModel):
    hostname: str
    cpu_percent: float
    memory_percent: float
    disk_used_gb: float
    disk_free_gb: float
    disk_total_gb: float
    disk_percent: float | None = 0.0
    net_recv_mb: float | None = 0.0
    net_sent_mb: float | None = 0.0
    disk_read_mb: float | None = 0.0
    disk_write_mb: float | None = 0.0
    docker: DockerSummary | None = None
    uptime_seconds: int | None = None
    load_avg: list[float] | None = None
    memory_total_gb: float | None = None
    disks: list[DiskInfo] = Field(default_factory=list)

class StatusResponse(BaseModel):
    uptime_seconds: int
    load_avg: list[float] = Field(default_factory=list)
    docker_active: bool
    ssh_active: bool

# --- Modelos del Módulo de IA (Fase 1) ---

class AIPredictionResponse(BaseModel):
    risk_level: str          # "low", "medium", "moderate", "high", "critical"
    message: str             # Explicación de la tendencia o alerta
    cpu_usage: float | None = None
    memory_usage: float | None = None

class ContainerRecommendation(BaseModel):
    container_id: str
    container_name: str
    recommended_action: str  # "pause", "stop", etc.
    reason: str              # Justificación analítica de la acción sugerida

class AIRecommendationsResponse(BaseModel):
    recommendations: list[ContainerRecommendation]