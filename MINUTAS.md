# Registro de Minutas de Reunión y Gestión Ágil — Proyecto TGMon v2 (`v1.2.34`)

- **Institución**: Universidad de la Ciudad de Buenos Aires (*U de la Ciudad*)
- **Asignatura**: Programación de Vanguardia (Ciclo Lectivo 2026)
- **Docente**: Ing. Alejandro Vázquez
- **Equipo de Desarrollo**:
  - **Rodrigo Carme** (`@Rcarme`) — Arquitectura, seguridad por roles (RBAC), telemetría, pronóstico OLS (`/ai/forecast`), integración opcional con IA y despliegue en `Shodan`.
  - **Luis Alberto Quispe** (`luisalbertoq@gmail.com`) — Co-diseño inicial cliente-servidor, persistencia SQLite/Room, telemetría base y estructura inicial de Android.
  - **Roberto Carlos Barragán** (`Roberto`) — Modelos Pydantic, lógica heurística y endpoints Python del módulo predictivo (`server/app/ai.py`, `server/app/models.py`).
  - **Gastón Joel Leppen** (`Gaston Joel`) — Componentes de interfaz de IA en Android (`AIPredictionCard`, `AIRecommendationsCard`), estados en ViewModels y consumo Retrofit.
- **Tablero de Issues y Milestones en GitHub**: [`https://github.com/Rcarme/UdelaCiudad/issues`](https://github.com/Rcarme/UdelaCiudad/issues)

---

## 1. Historias de Usuario e Issues Verificables en GitHub

Las historias de usuario y tareas técnicas se registraron como Issues en el repositorio oficial bajo tres Milestones (`Sprint 1`, `Sprint 2`, `Sprint 3`). No se declaran enlaces a Pull Requests individuales porque la integración entre ramas (`gaston`, `Robert+` y `main`) se realizó mediante *merge commits* directos en Git.

| Issue | ID | Historia de Usuario / Tarea | Responsable | Sprint | Commit / Evidencia |
| :---: | :---: | :--- | :--- | :---: | :--- |
| [#1](https://github.com/Rcarme/UdelaCiudad/issues/1) | US-01 | Monitor de telemetría en tiempo real (CPU, RAM, Disco) en backend | Luis Quispe | Sprint 1 | [`60b138d`](https://github.com/Rcarme/UdelaCiudad/commit/60b138d) |
| [#2](https://github.com/Rcarme/UdelaCiudad/issues/2) | US-02 | Visualización nativa de métricas en cliente Android (Jetpack Compose) | Rodrigo Carme | Sprint 1 | [`8760ba2`](https://github.com/Rcarme/UdelaCiudad/commit/8760ba2) |
| [#3](https://github.com/Rcarme/UdelaCiudad/issues/3) | US-03 | Listado y monitoreo de contenedores Docker vía `/api/docker/containers` | Rodrigo Carme | Sprint 2 | [`a4d7d10`](https://github.com/Rcarme/UdelaCiudad/commit/a4d7d10) |
| [#4](https://github.com/Rcarme/UdelaCiudad/issues/4) | US-04 | Control de ciclo de vida de contenedores (restart, stop, pause) y RBAC | Rodrigo Carme | Sprint 2 | [`21f57b6`](https://github.com/Rcarme/UdelaCiudad/commit/21f57b6) |
| [#5](https://github.com/Rcarme/UdelaCiudad/issues/5) | US-05 | Historial de telemetría y persistencia dual (SQLite WAL en server, Room v6 en app) | Luis Quispe | Sprint 2 | [`9ebcf99`](https://github.com/Rcarme/UdelaCiudad/commit/9ebcf99) |
| [#6](https://github.com/Rcarme/UdelaCiudad/issues/6) | US-06 | Módulo predictivo y análisis de saturación por regresión lineal OLS (`/ai/forecast`) | Roberto Barragán / Rodrigo Carme | Sprint 3 | [`f9748b6`](https://github.com/Rcarme/UdelaCiudad/commit/f9748b6) |
| [#7](https://github.com/Rcarme/UdelaCiudad/issues/7) | US-07 | Interfaz de diagnóstico con IA y recomendaciones en Android | Gastón Leppen | Sprint 3 | [`c4aa577`](https://github.com/Rcarme/UdelaCiudad/commit/c4aa577) |
| [#8](https://github.com/Rcarme/UdelaCiudad/issues/8) | TASK-08 | Suite de pruebas automatizadas: 43 tests en pytest y 50 tests en JUnit 4 | Rodrigo Carme | Sprint 3 | [`484ce96`](https://github.com/Rcarme/UdelaCiudad/commit/484ce96) |

---

## 2. Minutas de Ceremonias Ágiles

### Minuta Sprint Planning 1 — 26 de Agosto de 2026
- **Asistentes**: Rodrigo Carme, Luis Alberto Quispe, Roberto Carlos Barragán, Gastón Joel Leppen.
- **Objetivo**: Definir arquitectura base cliente-servidor, contratos OpenAPI y modelo de datos para telemetría continua.
- **Acuerdos**:
  1. Backend implementado en Python 3.12 con FastAPI por su rendimiento asíncrono y generación automática de OpenAPI (`/docs`).
  2. Cliente desarrollado exclusivamente en Android nativo moderno (Kotlin 2.0+, Jetpack Compose, Material Design 3, Corrutinas/Flow).
  3. Comunicación asegurada mediante cabeceras HTTP `X-API-Key` y arquitectura RBAC básica.
- **Riesgos identificados**: Sobrecarga de I/O por escritura frecuente de telemetría. Mitigado habilitando modo WAL en SQLite.

### Minuta Sprint Review & Planning 2 — 23 de Septiembre de 2026
- **Asistentes**: Rodrigo Carme, Luis Alberto Quispe, Roberto Carlos Barragán, Gastón Joel Leppen.
- **Demostración**: Dashboard Android en tiempo real consumiendo métricas de CPU, memoria y discos múltiples (`/`, `/media/hd2`) desde `Shodan`.
- **Retrospectiva**: El polling HTTP generaba picos innecesarios cuando la app estaba en segundo plano. Se implementó gestión de ciclo de vida con CoroutineScopes vinculados a ViewModels.
- **Planificación Sprint 2**: Integración completa con el socket de Docker (`/var/run/docker.sock`), lectura de contenedores, streaming de logs y persistencia local en Android con Room Database (versión de esquema 6).

### Minuta Sprint Review & Planning 3 — 14 de Octubre de 2026
- **Asistentes**: Rodrigo Carme, Luis Alberto Quispe, Roberto Carlos Barragán, Gastón Joel Leppen.
- **Demostración**: Operaciones seguras sobre contenedores (`POST /api/docker/containers/{id}/restart`), aislamiento estricto de roles (`Operator` vs `Viewer`) y almacenamiento histórico en base de datos.
- **Planificación Sprint 3**: Incorporación del módulo de Inteligencia Artificial y analítica predictiva:
  1. Endpoint analítico determinístico `/ai/forecast` con regresión lineal por Mínimos Cuadrados Ordinarios (OLS) para estimar tiempo hasta saturación sin dependencias externas.
  2. Integración opcional con modelos generativos (Gemini Flash) para recomendaciones de infraestructura en lenguaje natural.
  3. Cobertura integral de pruebas unitarias: validación de 43 pruebas en backend (`pytest`) y 50 pruebas en Android (`JUnit 4`).

---

## 3. Matriz de Trazabilidad y Calidad de Software

| Componente | Capa | Tecnología | Archivo Clave | Cobertura / Tests |
| :--- | :--- | :--- | :--- | :--- |
| **Backend API** | Servidor | FastAPI / Python 3.12 | `server/app/main.py` | 43 tests passing (`test_api.py`) |
| **Módulo Analítico** | Servidor | OLS Linear Regression | `server/app/ai.py` | Validado en `test_ai_endpoints` |
| **Cliente Móvil** | Frontend | Kotlin / Compose | `app/src/main/java/` | 50 tests passing (JUnit 4) |
| **Persistencia Local** | Mobile DB | Room Database (v6) | `app/.../data/local/` | Validado en Room Migrations |
| **Despliegue** | Infraestructura | Docker / Linux | `server/docker-compose.yml`| Desplegado en host `Shodan` |
