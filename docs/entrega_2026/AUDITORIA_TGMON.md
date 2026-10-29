# Auditoría técnica de TGMon

Revisión de código y plan de mejoras

Corte de revisión 28 de octubre de 2026
Commit 7d70763f38b118fc0736052084e46ca96552fe4f

### Conclusión

El proyecto tiene una base funcional integrada y decisiones útiles para una infraestructura pequeña. Los principales riesgos están en el aislamiento de servidores, la consistencia de credenciales, la revocación de conexiones activas y la interpretación de telemetría. El esfuerzo prioritario debe dirigirse a esos recorridos antes que a ampliar funcionalidades.

La aprobación docente de la implementación se toma como contexto confirmado por el usuario. Esta auditoría evalúa el comportamiento técnico; no asigna calificación ni incluye una matriz de cumplimiento.

### Alcance y método

Inventario de 131 archivos versionados de código y configuración, con 17 364 líneas incluyendo copias. Revisión estática de arquitectura, seguridad, persistencia, redes, IA, interfaz, workers, pruebas y despliegue; validación sintáctica Python, comparación de duplicados, pruebas automatizadas y revisión de cinco capturas. El inventario incluye hashes por archivo. No constituye una prueba exhaustiva de todos los estados de ejecución.

No se realizaron cambios al código de producción ni operaciones sobre contenedores reales. La reproducción del disco raíz usa la función de producción aislada con dependencias simuladas. Los restantes hallazgos se apoyan en rutas de código y tienen criterios de aceptación para su verificación posterior.

### Resultados de pruebas

Android: 48 pruebas, cero fallos y errores, ejecución completa con --rerun-tasks. Backend: 37 pruebas; 0 fallos y 0 errores. No se ejecutaron pruebas instrumentadas en dispositivo ni una llamada real a Gemini. Una suite aprobada no invalida los hallazgos que no cubre.


## Fortalezas y decisiones que conviene conservar

### Autorización en el servidor

Las mutaciones Docker exigen rol admin, con comprobación independiente de la UI. Los tokens se comparan mediante secrets.compare_digest y los identificadores se validan antes de operar.

### Persistencia de credenciales con fallo cerrado

PreferencesManager usa EncryptedSharedPreferences y no degrada a almacenamiento sin cifrar. El manifiesto deshabilita backups. Conviene agregar recuperación segura cuando falle el KeyStore sin borrar indiscriminadamente configuración válida.

### Caché Docker fuera de las solicitudes

La adquisición de estadísticas se centraliza en un hilo con un pool acotado. La API devuelve antigüedad del dato. Es una base razonable para controlar el costo de monitorear varios contenedores.

### Interfaz y continuidad de consulta

ViewModels, StateFlow, Room y estados de carga/caché estructuran la experiencia. Las capturas evidencian un tablero compacto, agrupación de recomendaciones y registro de alertas.

### Analítica local independiente del proveedor

use_llm=false evita invocaciones remotas en el camino habitual. El fallback reduce dependencia de disponibilidad externa; debe acompañarse con mejor trazabilidad del origen del diagnóstico.

### Pruebas y trazabilidad

Hay suites Python y Kotlin, un workflow de integración y un historial de contribuciones identificable. Los reportes se conservan con el paquete de revisión.

La organización Android se aproxima a MVVM, pero la capa denominada domain importa Android, Room y Retrofit. Describirla como separación práctica por capas es más preciso que afirmar independencia completa de infraestructura. Los archivos de interfaz más grandes tienen 996 y 922 líneas; separar componentes y lógica facilitaría pruebas y mantenimiento.


## Hallazgos 01 y 02

### H01 Credenciales cruzadas entre servidores

Prioridad Alta. Evidencia: app/src/main/java/com/tgmon/app/data/api/RetrofitClient.kt:84

ServersViewModel crea repositorios con server.id, pero TgMonRepository.getApi usa RetrofitClient con un tokenProvider global ligado al servidor por defecto. Una consulta al servidor B puede llevar el token de A; un 401 puede además limpiar el token del perfil activo.

Mejora propuesta: Crear clientes con credenciales ligadas a la identidad de servidor y limitar la invalidación al perfil que falló.

Criterio de aceptación: Dos servidores de prueba con tokens distintos reciben exclusivamente su token. Un 401 en B conserva la sesión A.

### H02 Historial y caché sin separación por servidor

Prioridad Alta. Evidencia: app/src/main/java/com/tgmon/app/domain/repository/TgMonRepository.kt:384

La sincronización deduplica sólo por timestamp e inserta hostname vacío. MetricHistory y ContainerCache no tienen serverId; sus DAOs consultan globalmente. Al alternar perfiles pueden mezclarse curvas y contenedores, o descartarse muestras coincidentes.

Mejora propuesta: Agregar serverId a entidades, índices, claves y consultas. Migrar datos y separar TTL y limpieza por origen.

Criterio de aceptación: A y B con igual timestamp conservan ambas muestras. La consulta offline de B no muestra datos de A.


## Hallazgos 03 y 04

### H03 Revocación incompleta de sesiones

Prioridad Alta. Evidencia: server/main.py:594

is_token_valid se consulta durante el handshake, no en el bucle autenticado ni al transmitir. Invalidar el token bloquea nuevas peticiones, pero no desconecta las conexiones ya aceptadas. Además auth.py elimina locks mayores de 86400 segundos durante mantenimiento.

Mejora propuesta: Cerrar conexiones al revocar y verificar una versión de credencial; mantener la revocación hasta una rehabilitación explícita.

Criterio de aceptación: Autenticar un WebSocket, revocar por REST y exigir cierre inmediato y rechazo de reconexión; avanzar 24 horas no rehabilita el acceso.

### H04 La raíz Linux se omite del listado de discos

Prioridad Alta. Evidencia: server/main.py:168

Para mountpoint /, rstrip produce una cadena vacía. El guard if not norm_key continúa antes de evaluar is_root. get_health_data termina usando otro disco como principal o ceros si no hay más. La reproducción con función original aislada y psutil simulado obtuvo cero entradas para una raíz válida.

Mejora propuesta: Normalizar la raíz conservando / y deduplicar por identidad del filesystem, no por capacidad y uso redondeados.

Criterio de aceptación: En Linux, una única partición / devuelve una entrada System. Dos discos con igual total y uso se conservan.


## Hallazgos 05 y 06

### H05 Reintentos de operaciones con efectos

Prioridad Alta. Evidencia: app/src/main/java/com/tgmon/app/domain/repository/TgMonRepository.kt:332

Las operaciones start, stop, restart, pause, unpause y delete usan executeWithRetry. Si Docker ejecuta y se pierde la respuesta, el repositorio puede repetir la orden por otra URL o intento. El filtro GET del interceptor no evita los reintentos de la capa repositorio.

Mejora propuesta: Separar políticas de lectura y mutación. Usar claves de idempotencia o consultar el estado tras un resultado incierto antes de decidir.

Criterio de aceptación: Simular respuesta perdida después de restart: se ejecuta una sola vez y la UI muestra estado incierto hasta reconciliar.

### H06 Defaults de acceso y transporte sin cifrado

Prioridad Alta. Evidencia: server/docker-compose.yml:23

Compose permite iniciar con una credencial administrativa de ejemplo y expone 8000. Android permite HTTP en base-config, no sólo en dominios privados. La combinación es riesgosa si se publica el servicio sin una protección adicional. El socket Docker aumenta el impacto del acceso al backend.

Mejora propuesta: Exigir secretos al iniciar, restringir la exposición y usar HTTPS/WSS o una red privada cifrada comprobada. Separar permisos de operación Docker.

Criterio de aceptación: Arranque rechazado si falta secreto. Pruebas release no transmiten tokens sobre una red sin cifrar.


## Hallazgos 07 y 08

### H07 Recomendaciones basadas en ausencia de actividad instantánea

Prioridad Alta. Evidencia: server/ai.py:343

Una sola lectura baja se transforma en sugerencia de pausa. Con caché vacía se consulta la lista de Docker asignando 0,0 a cada CPU desconocida. Eso convierte falta de medición en inactividad. Los nombres excluidos no constituyen un catálogo de servicios esenciales.

Mejora propuesta: Representar datos desconocidos, exigir ventana temporal, evaluar disponibilidad y dependencias y mantener confirmación humana. No prometer liberar RAM por pausar.

Criterio de aceptación: Una caché vacía no sugiere pausas. Un servicio esencial sin carga queda protegido por una regla explícita.

### H08 Validación semántica insuficiente de respuestas de IA

Prioridad Media. Evidencia: server/models.py:45

Los campos risk_level y recommended_action son strings libres. Pydantic valida la estructura, pero no un conjunto cerrado de acciones ni pertenencia del ID al inventario. La exclusión de servicios esenciales para la respuesta LLM existe como instrucción de prompt.

Mejora propuesta: Usar enums y límites de longitud; validar IDs, acciones, estado y política de protección con código independiente del proveedor.

Criterio de aceptación: Una respuesta con acción no permitida, ID inventado o servicio protegido se rechaza y activa fallback.


## Hallazgos 09 y 10

### H09 Caché de recomendaciones invalida sólo por cantidad

Prioridad Media. Evidencia: server/ai.py:377

Durante el TTL, dos inventarios con igual cantidad pero distinto contenido, estado o consumo reutilizan la respuesta anterior. El diagnóstico de recursos devuelve también los valores numéricos almacenados en la respuesta de caché.

Mejora propuesta: Incluir huella de IDs y estados, versión de muestra y antigüedad; separar diagnóstico reutilizado de métricas actuales.

Criterio de aceptación: Sustituir un contenedor manteniendo el total obliga a recalcular; la UI informa cuándo se calculó el resultado.

### H10 Latencia de IA sin presupuesto total

Prioridad Media. Evidencia: server/ai.py:138

El fallback REST prueba hasta cuatro modelos con timeout individual de 15 segundos, además del intento SDK. El cliente habitual tiene lectura de 30 segundos y el repositorio reintenta. Un intento de análisis puede seguir trabajando tras el abandono del cliente y multiplicar solicitudes.

Mejora propuesta: Definir deadline total, controlar concurrencia y deduplicar consultas simultáneas. Propagar estado de proveedor y fallback.

Criterio de aceptación: Con proveedor lento, la operación termina dentro del presupuesto elegido y un doble clic no genera consultas duplicadas.


## Hallazgos 11 y 12

### H11 Fallback de WebSocket no equivalente al REST

Prioridad Media. Evidencia: app/src/main/java/com/tgmon/app/presentation/viewmodel/DashboardViewModel.kt:578

El WebSocket elige la URL local mediante Elvis aunque sea una cadena vacía, y sus reintentos vuelven a construir la misma URL. El camino REST sí prueba local y remota. En uso fuera de la LAN puede perderse el streaming aunque funcione la consulta REST.

Mejora propuesta: Compartir selección de destino validado entre REST y WebSocket, con política de reconexión y estado por servidor.

Criterio de aceptación: Con URL local inaccesible y remota válida, REST y WebSocket se conectan al destino remoto.

### H12 Alerta de memoria no se restablece como CPU y disco

Prioridad Media. Evidencia: app/src/main/java/com/tgmon/app/domain/Infrastructure.kt:92

CPU y disco tienen una rama de recuperación que descarta la notificación y reinicia el cooldown. La condición de memoria no tiene esa rama. La notificación puede permanecer tras normalizarse el consumo.

Mejora propuesta: Implementar recuperación explícita y unificar reglas de transición, incluyendo la identidad de servidor.

Criterio de aceptación: Secuencia RAM alta, normal y alta actualiza el estado y notifica según la política documentada.


## Hallazgos 13 y 14

### H13 El worker no solicita un reintento

Prioridad Media. Evidencia: app/src/main/java/com/tgmon/app/widget/SnapshotWorker.kt:89

El camino de error calcula retry_count y retorna failure. No retorna retry, y outputData no se convierte automáticamente en inputData de un nuevo intento. El comentario de tres reintentos no corresponde al comportamiento implementado.

Mejora propuesta: Usar runAttemptCount y Result.retry con backoff cuando el fallo sea transitorio; mantener failure para errores permanentes.

Criterio de aceptación: Una pérdida temporal de red produce el reintento esperado y el límite se comprueba con el scheduler de prueba.

### H14 Dos copias de backend y pruebas sobre una sola

Prioridad Media. Evidencia: server/Dockerfile:9

Los seis pares de módulos son idénticos en el corte analizado, pero Docker usa server/app y pytest importa server. Una corrección futura aplicada a una sola copia puede dejar el despliegue divergente de lo probado.

Mejora propuesta: Consolidar un único paquete y ejecutar pruebas sobre el mismo artefacto que se incluye en la imagen.

Criterio de aceptación: Modificar un módulo cambia tanto las pruebas como la imagen sin sincronización manual de archivos.


## Hallazgos 15 y 16

### H15 Publicación del APK apunta a otro nombre

Prioridad Media. Evidencia: .github/workflows/ci.yml:35

El workflow espera app-debug.apk, mientras applicationVariants renombra el archivo a TGMon-v2-${versionName}.apk. Puede finalizar el build sin subir el APK esperado.

Mejora propuesta: Alinear patrón de subida con nombre generado y fallar explícitamente si falta el artefacto.

Criterio de aceptación: El job publica exactamente un APK con checksum y versión identificables.

### H16 Versionado con efectos durante configuración

Prioridad Media. Evidencia: app/build.gradle.kts:32

Solicitar assemble incrementa version.properties durante la configuración de Gradle. La misma revisión puede producir versiones distintas y el checkout cambia sólo por compilar.

Mejora propuesta: Derivar la versión de una entrada explícita de release o tag, sin escribir al configurar Gradle.

Criterio de aceptación: Dos compilaciones del mismo commit y parámetros mantienen versión; git status queda limpio.


## Hallazgos 17 y 18

### H17 Límites de privacidad de logs y prompts

Prioridad Media. Evidencia: server/ai.py:391

El prompt incluye nombres e IDs de contenedores; no son sólo métricas numéricas anónimas. Las expresiones regulares de redacción no garantizan cubrir todos los formatos de secretos. update_shodan.sh imprime variables que coinciden con TOKEN.

Mejora propuesta: Minimizar contexto enviado, usar alias cuando sea posible y eliminar impresión de credenciales en scripts. Documentar qué sale del servidor.

Criterio de aceptación: Un conjunto de secretos en formatos JSON y texto no aparece en salida; los prompts contienen sólo campos autorizados.

### H18 Calidad de pruebas y evolución del esquema

Prioridad Media. Evidencia: app/src/main/java/com/tgmon/app/data/database/AppDatabase.kt:12

Room no exporta esquemas y permite migración destructiva como fallback. Algunas pruebas de alertas repiten comparaciones locales en vez de invocar el gestor real. Un test verde no demuestra recuperación de notificaciones ni conservación de datos en actualizaciones.

Mejora propuesta: Exportar esquemas, probar migraciones y extraer lógica de alertas invocable con reloj y notificaciones simulados.

Criterio de aceptación: Migraciones conservan registros y pruebas ejecutan transiciones reales del gestor, incluyendo recuperación.


## Plan de mejora y validación

| Etapa | Trabajo | Condición de cierre |
| --- | --- | --- |
| 1 Integridad y acceso | H01 a H06: credenciales, identidad de datos, revocación, raíz y mutaciones. | Pruebas de dos servidores, revocación activa y operaciones inciertas. |
| 2 Analítica y continuidad | H07 a H13: datos faltantes, validación IA, caché, tiempos, WebSocket y workers. | Resultados explicables y fallos transitorios controlados. |
| 3 Reproducibilidad | H14 a H18: paquete único, APK, versiones, privacidad y esquema. | CI produce el mismo artefacto probado y conserva datos. |

### Pruebas que aportan evidencia adicional

Agregar pruebas de integración con dos destinos HTTP simulados y tokens diferentes; ensayar la pérdida de respuesta tras una operación; conectar un WebSocket antes de revocar; ejecutar migraciones con datos conocidos; probar UI en rotación, segundo plano y letra ampliada. Para el predictor, preparar series con timestamps, datos faltantes y cargas conocidas.

### Criterios para afirmaciones documentales

No usar “menos de 1 ms”, “menos de 50 MB”, “alta disponibilidad” o “100 % de cumplimiento” sin una medición o evidencia específica. Una llamada Gemini simulada prueba el contrato local, no la disponibilidad de un modelo. Una captura con un servidor online documenta ese instante, no una garantía de operación continua. Una política de reinicio no equivale a redundancia.

La documentación anterior contenía afirmaciones más amplias que la evidencia. El nuevo informe usa la arquitectura real, cinco capturas aportadas, el resultado de pruebas y límites explícitos. Mantiene las minutas históricas como registros del equipo, sin inventar reuniones, PRs ni capturas de tablero.

### Material de reproducción

evidencias/inventario.json: commit y hashes. evidencias/verificaciones.json: reproducción de raíz Linux y resumen Android. evidencias/backend.xml y TEST-*.xml: resultados. fuentes/analisis.py: comprobación aislada. fuentes/generar_documentos.py: contenido y generación de documentos. Referencias técnicas: documentación oficial de Android Network Security Configuration, Docker pause, SQLite WAL y FastAPI Deployments Concepts, citadas en el informe principal.
