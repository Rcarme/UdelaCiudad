# Defensa del proyecto TGMon

Duración sugerida: 10 minutos de exposición y 5 de demostración. Distribución propuesta; el equipo puede ajustarla. Este guion acompaña el Word y no reemplaza una presentación visual.

## Exposición

1. **Problema y alcance, 1 minuto.** Explicar quién necesita consultar infraestructura desde Android y qué decisiones facilita el sistema. Evitar comparaciones sin medición con otras plataformas.
2. **Arquitectura, 2 minutos.** Usar el diagrama del Word: cliente Android, backend FastAPI modular, SQLite/Room, Docker y proveedor externo opcional. Describir la implementación aprobada sin llamar microservicio independiente a cada archivo Python.
3. **Datos y continuidad, 1 minuto.** Diferenciar frecuencia del WebSocket, guardado del servidor y trabajo periódico Android. Explicar caché, antigüedad y conservación local.
4. **Analítica, 2 minutos.** Mostrar el cálculo del delta de CPU, los umbrales y la invocación explícita de Gemini. Aclarar que las curvas actuales son históricas y el diagnóstico es heurístico.
5. **Interfaz y roles, 1 minuto.** Recorrer capturas y distinguir consulta viewer de control admin. Mostrar que el backend vuelve a validar permisos.
6. **Pruebas y límites, 2 minutos.** Presentar 48 pruebas Android y 37 backend aprobadas con sus alcances. Elegir dos hallazgos relevantes y describir cómo se corregirían y verificarían.
7. **Evolución, 1 minuto.** Priorizar aislamiento de servidores, revocación activa y confiabilidad de métricas antes de agregar un pronóstico estadístico.

## Demostración

- Preparar un servidor de ensayo con datos y un contenedor descartable. No saturar un host operativo como parte de la presentación.
- Abrir Overview y explicar identidad, estado, CPU, RAM, discos y antigüedad.
- Mostrar el historial y señalar un pico registrado; no atribuirle una causa sin evidencia.
- Mostrar un diagnóstico local. Solicitar análisis externo sólo si la clave y conectividad de ensayo están verificadas; ante fallback, explicar el estado real.
- Consultar contenedores como viewer y demostrar rechazo de una operación de control. Cambiar al perfil admin y operar únicamente sobre el contenedor de ensayo.
- Mostrar la confirmación de pausa y comprobar el estado posterior. Aclarar que baja CPU no demuestra que el servicio sea prescindible.
- Consultar las alertas y cerrar mostrando el historial y resultados de pruebas.

## Preguntas de defensa

**¿Por qué dos bases?** SQLite conserva adquisición en el servidor; Room sostiene la consulta local del dispositivo. No son una base distribuida.

**¿Qué significa predictivo en esta versión?** Una advertencia heurística por umbral y variación reciente, con explicación opcional de un modelo externo. No hay una curva futura ni una probabilidad calibrada.

**¿Qué ocurre sin Gemini?** El sistema devuelve el diagnóstico local. Monitoreo y control no dependen de la disponibilidad del proveedor.

**¿Por qué no ejecutar automáticamente una recomendación?** Una lectura baja o una respuesta del modelo no acredita que una operación sea conveniente. La decisión requiere contexto, confirmación y autorización.

**¿Qué falta mejorar?** Aislamiento de credenciales y datos por servidor, revocación de WebSockets activos, normalización de la raíz Linux y reintentos de mutaciones. La auditoría especifica pruebas de aceptación.

## Evidencia para llevar

Word principal, capturas originales, reportes XML, commit de referencia, minutas existentes y captura auténtica del tablero. Evitar mostrar tokens, archivos `.env` o variables de entorno completas durante la exposición.
