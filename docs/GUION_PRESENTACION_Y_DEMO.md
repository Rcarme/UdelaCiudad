# Estructura de Presentación Visual (PPT / Canva) y Guion de Demostración en Vivo

Este documento contiene la estructura exacta de diapositivas y los comandos listos para la **Presentación Visual y Defensa Oral (5 pts)** ante la cátedra.

---

## Estructura de Diapositivas Sugerida (8 Slides)

1. **Slide 1 — Portada**:
   - **Título**: TGMon v2 — Monitoreo SRE en Tiempo Real, Orquestación Docker y Analítica Predictiva Híbrida con IA.
   - **Asignatura**: Programación de Vanguardia — Lic. en Tecnologías Informáticas (U de la Ciudad, 2026).
   - **Integrantes**: Rodrigo Carme, Roberto Carlos Barragán, Gastón Joel Leppen.
2. **Slide 2 — El Problema y el Valor de la Solución**:
   - Suites tradicionales pesadas y reactivas vs. necesidad de control móvil inmediato y predicción temprana de cuellos de botella.
   - Desplegado en producción real (`shodan`) con `< 50 MB` de huella de RAM.
3. **Slide 3 — Arquitectura de Microservicios y Tecnologías**:
   - **Cliente JVM**: Kotlin + Jetpack Compose + Hilt + Coroutines/Flow + Room SQLite (Offline-First).
   - **Servicio Core & Docker**: Python FastAPI + RBAC (`Admin` vs `Viewer`) + WebSocket `/ws/telemetry` + Caché Asíncrono de Docker.
   - **Servicio Predictivo e IA**: Heurística estadística `< 1 ms` + **Google Gemini 3.8 Flash** bajo demanda.
4. **Slide 4 — Innovación: Motor Híbrido de IA con Costo Cero en Reposo**:
   - Explicación del flujo: Polling automático gratuito (`use_llm=false`, `< 1 ms`) vs. Botón explícito **"✨ Analizar con IA"** (`use_llm=true`).
   - Caché Inteligente (`120 s` TTL + variación `< 10%`), sanitización de secretos y *fallback* indestructible si se corta internet.
5. **Slide 5 — Seguridad por Diseño (RBAC y Criptografía)**:
   - Comparación en tiempo constante (`secrets.compare_digest`), separación estricta de roles (`403 Forbidden` para `Viewer` en acciones mutables), validación hexadecimal anti-inyección en IDs de Docker y redacción automática de credenciales en logs.
6. **Slide 6 — Recorrido Visual (UI/UX en Android)**:
   - Dashboard compacto de una sola pantalla, Carrusel `HorizontalPager` de múltiples discos (`/media`, `/mnt`), Top 4 contenedores y agrupación expandible de recomendaciones repetidas (`Ver lista (N)`).
7. **Slide 7 — Calidad de Software (QA), GitFlow y Despliegue**:
   - Grafo real de ramas (`gaston` + `Robert+}` → `main`), **37 tests en `pytest`** + tests unitarios JUnit4 en GitHub Actions CI/CD.
8. **Slide 8 — Demostración en Vivo sobre `shodan`**.

---

## Comandos para la Demostración en Vivo (5 minutos)

### Paso 1: Mostrar el estado normal y pedir diagnóstico a Gemini 3.8 Flash
1. Abrir la app en el teléfono conectado a `shodan` (o ejecutar por terminal):
   ```bash
   curl -s -H "Authorization: Bearer shodan_secret_2026" "http://localhost:8000/ai/predict-spike?use_llm=true"
   ```
2. Tocar el botón **"✨ Analizar con IA"** en el Dashboard y en Contenedores para mostrar cómo responde `✨ [Gemini IA] ...` y cómo agrupa los contenedores con `0.0%` de CPU.

### Paso 2: Simular un pico de consumo de CPU en vivo en `shodan`
Ejecutar en la consola de `shodan` durante 30 segundos:
```bash
timeout 30s bash -c 'for i in $(seq 1 $(nproc)); do yes > /dev/null & done; wait'
```
- Observar cómo las agujas de CPU en la app Android suben en vivo por WebSocket (`/ws/telemetry`), la tarjeta **AI Prediction** cambia a **`CRITICAL` / `HIGH`** en rojo, y al tocar **"✨ Analizar con IA"** Gemini diagnostica la saturación en tiempo real.
