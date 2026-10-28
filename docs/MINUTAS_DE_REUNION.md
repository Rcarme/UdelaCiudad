# Registro de Minutas de Reunión y Tablero Ágil — Proyecto TGMon v2

**Institución**: Universidad de la Ciudad de Buenos Aires  
**Asignatura**: Programación de Vanguardia (Ciclo Lectivo 2026)  
**Docente**: Ing. Vázquez Alejandro  
**Equipo**: Rodrigo Carme (`@Rcarme`), Roberto Carlos Barragán (`Roberto`), Gastón Joel Leppen (`Gaston Joel`)

---

## 1. Tablero Kanban de Historias de Usuario (GitHub Projects / Backlog)

| ID | Historia de Usuario / Tarea | Responsable | Sprint | Estado | Commit Asociado |
| :---: | :--- | :--- | :---: | :---: | :---: |
| **HU-01** | Autenticación RBAC con tokens diferenciados (`Admin` vs `Viewer`) e invalidación de emergencia (`0o600`). | Rodrigo Carme | Sprint 1 | ✅ Done | `170d2d2` |
| **HU-02** | Detección de discos internos/externos (`/media`, `/mnt`, `/run/media`), carrusel deslizable y vista Overview de pantalla única con Top 4 contenedores. | Rodrigo Carme | Sprint 1 | ✅ Done | `e122e5b` |
| **HU-03** | Implementación de esquemas Pydantic (`models.py`), lógica predictiva (`ai.py`) y endpoints `/ai/predict-spike` y `/ai/recommendations`. | Roberto Barragán | Sprint 2 | ✅ Done | `f225281` |
| **HU-04** | Integración de tarjetas `AIPredictionCard` y `AIRecommendationsCard`, estados en ViewModels y llamadas Retrofit en Android. | Gastón Leppen | Sprint 2 | ✅ Done | `104c08f` |
| **HU-05** | Unificación de ramas `gaston` y `Robert+}` en `main`, optimización de contenedores con caché asíncrono (`< 1 ms`) y agrupación expandible en UI. | Rodrigo Carme | Sprint 3 | ✅ Done | `f8cde81`, `6e67dea`, `5f4349a` |
| **HU-06** | Integración bajo demanda con **Google Gemini Flash (`gemini-3.8-flash`)** vía botón `"✨ Analizar con IA"`, Caché Inteligente (`120s` TTL) y fallback heurístico local. | Rodrigo Carme | Sprint 3 | ✅ Done | `723d3ba`, `b3a7765` |
| **HU-07** | Suite de 37 tests unitarios en `pytest`, tests JUnit4 en Android, pipeline CI/CD en GitHub Actions y documentación final. | Equipo Completo | Sprint 3 | ✅ Done | `ed3a500` |

---

## 2. Minutas de Reunión

### Minuta N° 1 — Definición de Propuesta, Dominio y Arquitectura (Fase 1)
- **Fecha**: 15 de septiembre de 2026
- **Asistentes**: Rodrigo Carme, Roberto Carlos Barragán, Gastón Joel Leppen.
- **Orden del Día**:
  1. Definición del alcance y problemática para el Proyecto Integrador de *Programación de Vanguardia*.
  2. Elección del stack tecnológico: Front-end y lógica de cliente en **Kotlin / Jetpack Compose (JVM)**, servicios de backend y analítica predictiva en **Python / FastAPI**, y bases de datos relacionales **SQLite WAL + Room**.
  3. Distribución de módulos entre los integrantes del equipo.
- **Conclusiones**: Queda aprobada internamente la arquitectura de **TGMon v2** orientada al monitoreo en tiempo real y predicción de saturación de servidores Linux con Docker.

### Minuta N° 2 — Seguimiento de Desarrollo Ágil en Ramas (`gaston` y `Robert+}`) (Fase 2)
- **Fecha**: 24 de octubre de 2026
- **Asistentes**: Rodrigo Carme, Roberto Carlos Barragán, Gastón Joel Leppen.
- **Orden del Día**:
  1. Revisión de los componentes UI de IA desarrollados en la rama `gaston` (`104c08f`).
  2. Revisión de los modelos y endpoints predictivos desarrollados en la rama `Robert+}` (`5c57f1c`, `f225281`).
  3. Planificación de las pruebas de integración con la base de datos SQLite real y el demonio de Docker.
- **Conclusiones**: Se acuerda realizar el merge en `main` preservando la historia de commits de cada integrante y ejecutar pruebas de carga reales sobre el servidor `shodan`.

### Minuta N° 3 — Optimización, Integración de Gemini 3.8 Flash y Cierre de Entrega (Fase 3)
- **Fecha**: 25–28 de octubre de 2026
- **Asistentes**: Rodrigo Carme, Roberto Carlos Barragán, Gastón Joel Leppen.
- **Orden del Día**:
  1. Merge de `Robert+}` y `gaston` en `main` (`f8cde81`).
  2. Optimización del análisis de contenedores ociosos usando el hilo `cached_containers` (`< 1 ms`) y agrupación visual de recomendaciones repetidas (`5f4349a`).
  3. Incorporación de **Google Gemini Flash (`gemini-3.8-flash`)** bajo demanda (`?use_llm=true`) con el botón `"✨ Analizar con IA"`, Caché Inteligente (`120 s` TTL + `10%` delta) y fallback local automático (`723d3ba` → `b3a7765`).
  4. Validación de las 37 pruebas unitarias en `pytest` y compilación del APK final `v1.2.32`.
- **Conclusiones**: Sistema desplegado y validado en producción sobre el servidor `shodan`. Documentación técnica lista para la entrega del 12-11.
