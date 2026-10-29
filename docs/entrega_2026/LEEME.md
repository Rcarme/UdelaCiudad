# Entrega de TGMon

El documento principal es **INFORME_TGMON.docx**. La revisión de problemas y mejoras está separada en **AUDITORIA_TGMON.docx**. Ambos son editables. Las versiones Markdown conservan el texto y el generador permite reconstruir los Word.

Se tomó como válida la aprobación docente de la implementación comunicada por Rodrigo. No se incluyó una matriz de cumplimiento ni una calificación proyectada. La arquitectura describe el cliente Android y el backend Python modular tal como existen.

## Contenido

- Informe académico: problema, objetivos, casos de uso, arquitectura, comunicaciones, datos, tecnologías, analítica, cinco capturas reales, pruebas, despliegue, gestión y evolución.
- Auditoría: fortalezas, 18 hallazgos priorizados con ubicación de código, impacto, mejora y criterios de aceptación.
- `imagenes/`: capturas aportadas por el equipo y diagrama de arquitectura.
- `evidencias/`: inventario con hashes, reproducción aislada, resultados XML de pruebas y dependencias Python.
- `fuentes/`: scripts de generación y comprobación.
- `GUION_DEFENSA.md`: recorrido de exposición y demostración.

## Verificación

Android: 48 pruebas JUnit sin fallos ni errores; ejecución nueva con `--rerun-tasks`, finalizada en 1 minuto y 29 segundos. Backend: 37 pruebas aprobadas en 31,24 segundos, con una advertencia de deprecación de una dependencia. Se usó una copia aislada de archivos Python, sin credenciales ni datos de producción. La comparación inicial detectó seis pares de módulos idénticos en `server/` y `server/app/`.

La reproducción de la omisión de `/` en Linux usa la función original y una dependencia `psutil` simulada. No se realizó una intervención en el servidor remoto ni una llamada real a Gemini. No se modificó el código de producción.

## Evidencia adicional que sólo el equipo puede aportar

La consigna solicita capturas del tablero de gestión. No se recibió una captura auténtica de GitHub Projects, Trello o Jira; el informe explica qué evidencia existe sin inventar una. Conviene añadirla antes de entregar. Si hubo PRs, incorporar sus enlaces. Las contribuciones de trabajo en pareja y las minutas se atribuyen a la documentación del equipo, no se presentan como hechos demostrados únicamente por Git.

El documento registra cuatro integrantes: Rodrigo Carme, Luis Alberto Quispe, Roberto Carlos Barragán y Gastón Joel Leppen. Las cinco capturas facilitadas por el usuario están incorporadas sin alterar su contenido.

## Reproducción

Con Python y `python-docx` y `Pillow` disponibles, ejecutar desde la raíz del proyecto:

```text
python docs/entrega_2026/fuentes/analisis.py
python docs/entrega_2026/fuentes/generar_documentos.py
```

El generador lee el resultado del backend desde `evidencias/backend.xml`; conservar ese reporte al reconstruir. Después de modificar contenido, renderizar los Word y revisar cada página antes de entregar. No basta con regenerar el archivo.
