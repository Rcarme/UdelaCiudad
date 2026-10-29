"""Genera los documentos editables desde contenido y evidencias locales."""
from pathlib import Path
import json, re, xml.etree.ElementTree as ET
from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[3]
BASE=ROOT/'docs/entrega_2026'
IMG=BASE/'imagenes'
APP='app/src/main/java/com/tgmon/app/'
inv=json.loads((BASE/'evidencias/inventario.json').read_text())

def ref(path, needle):
    lines=(ROOT/path).read_text(encoding='utf-8-sig').splitlines()
    n=next((i+1 for i,s in enumerate(lines) if needle in s),1)
    return f'{path}:{n}'

def diagram():
    im=Image.new('RGB',(1600,1030),'white'); d=ImageDraw.Draw(im)
    f=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',29)
    b=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',32)
    def box(x,y,w,h,title,body):
        d.rounded_rectangle((x,y,x+w,y+h),18,fill='#f1eff9',outline='#7262b4',width=3)
        d.text((x+22,y+18),title,font=b,fill='#252233')
        for i,line in enumerate(body): d.text((x+22,y+65+39*i),line,font=f,fill='#333344')
    def arrow(a,z,label='',labelxy=None):
        d.line([a,z],fill='#62519d',width=4)
        import math
        t=math.atan2(z[1]-a[1],z[0]-a[0])
        d.polygon([z,(z[0]-18*math.cos(t-.45),z[1]-18*math.sin(t-.45)),(z[0]-18*math.cos(t+.45),z[1]-18*math.sin(t+.45))],fill='#62519d')
        if label:d.text(labelxy,label,font=f,fill='#333344')
    box(30,25,620,170,'Cliente Android',['Compose y ViewModels','Repositorio y Retrofit / OkHttp'])
    box(950,25,620,170,'Persistencia en Android',['Room: métricas, caché, logs, alertas','Preferencias cifradas: perfiles y tokens'])
    arrow((650,100),(950,100))
    arrow((330,195),(330,330),'REST + Bearer / WebSocket',(410,245))
    d.rounded_rectangle((25,330,1575,730),20,fill='#faf9fc',outline='#7262b4',width=4)
    d.text((50,350),'Backend Python   |   una aplicación FastAPI',font=b,fill='#252233')
    box(55,420,460,250,'Seguridad y telemetría',['auth.py / main.py','Roles admin y viewer','psutil y difusión de eventos'])
    box(565,420,460,250,'Gestión Docker',['docker_api.py','SDK y caché en segundo plano','Operaciones con autorización'])
    box(1075,420,460,250,'Analítica híbrida',['ai.py / models.py','Reglas locales y caché','Gemini bajo demanda'])
    box(30,840,450,160,'SQLite del servidor',['Historial de métricas','WAL y retención de 30 días'])
    box(575,840,450,160,'Docker Engine',['Socket local','Contenedores del host'])
    box(1120,840,450,160,'Proveedor externo',['Gemini API','Opcional y con fallback'])
    arrow((280,670),(255,840))
    arrow((795,670),(800,840))
    arrow((1305,670),(1345,840))
    im.save(IMG/'arquitectura.png')

def init_doc(title, subtitle):
    doc=Document(); sec=doc.sections[0]
    sec.page_height=Cm(29.7);sec.page_width=Cm(21)
    sec.top_margin=Cm(1.9);sec.bottom_margin=Cm(1.8);sec.left_margin=Cm(2.1);sec.right_margin=Cm(2.1)
    styles=doc.styles
    for name in ['Normal','Title','Subtitle','Heading 1','Heading 2','Heading 3']:
        styles[name].font.name='Calibri';styles[name].font.color.rgb=RGBColor.from_string('20202B')
    styles['Normal'].font.size=Pt(10.5)
    styles['Normal'].paragraph_format.space_after=Pt(7)
    styles['Normal'].paragraph_format.line_spacing=1.12
    styles['Title'].font.size=Pt(32)
    styles['Heading 1'].font.size=Pt(19)
    styles['Heading 1'].paragraph_format.space_after=Pt(13)
    styles['Heading 2'].font.size=Pt(12)
    styles['Heading 2'].paragraph_format.space_before=Pt(10)
    for style in styles:
        for border in list(style.element.iter(qn('w:pBdr'))):
            border.getparent().remove(border)
    doc.core_properties.title=title
    doc.core_properties.subject=subtitle
    doc.core_properties.author='Equipo TGMon'
    doc.core_properties.keywords='TGMon, Programación de Vanguardia, 2026'
    foot=sec.footer.paragraphs[0];foot.alignment=2
    r=foot.add_run('TGMon  |  ');r.font.size=Pt(8);r.font.color.rgb=RGBColor(105,105,115)
    field=OxmlElement('w:fldSimple');field.set(qn('w:instr'),'PAGE');foot._p.append(field)
    return doc

def para(doc,text):
    p=doc.add_paragraph(text)
    p.paragraph_format.widow_control=True
    return p

def table(doc, headers, rows, widths=None):
    t=doc.add_table(rows=1,cols=len(headers));t.style='Light Shading Accent 1'
    for c,txt in zip(t.rows[0].cells,headers):c.text=txt
    rp=t.rows[0]._tr.get_or_add_trPr();rp.append(OxmlElement('w:tblHeader'))
    for row in rows:
        cells=t.add_row().cells
        for c,txt in zip(cells,row):c.text=str(txt)
    for row in t.rows:
        row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
        for i,c in enumerate(row.cells):
            if widths:c.width=Cm(widths[i])
            for p in c.paragraphs:
                p.paragraph_format.space_after=Pt(4);p.paragraph_format.space_before=Pt(3)
                for r in p.runs:r.font.size=Pt(9)
    doc.add_paragraph().paragraph_format.space_after=Pt(0)

class Writer:
    def __init__(self,title,subtitle):self.doc=init_doc(title,subtitle);self.md=['# '+title,'',subtitle,'']
    def page(self,title):self.doc.add_page_break();self.doc.add_heading(title,1);self.md+=['\n## '+title,'']
    def p(self,text):para(self.doc,text);self.md += [text,'']
    def h(self,text):self.doc.add_heading(text,2);self.md+=['### '+text,'']
    def t(self,heads,rows,widths=None):
        table(self.doc,heads,rows,widths);self.md+=['| '+' | '.join(heads)+' |','| '+' | '.join(['---']*len(heads))+' |']+['| '+' | '.join(map(str,r))+' |' for r in rows]+['']
    def picture(self,path,width,caption):
        p=self.doc.add_paragraph();p.alignment=1;p.add_run().add_picture(str(path),width=Cm(width))
        p=self.doc.add_paragraph(caption);p.paragraph_format.space_after=Pt(8)
        for r in p.runs:r.italic=True;r.font.size=Pt(9)
        self.md += [f'![{caption}](imagenes/{path.name})','']
    def pair(self,images,captions,height=15):
        t=self.doc.add_table(rows=1,cols=2)
        for c,img,cap in zip(t.rows[0].cells,images,captions):
            p=c.paragraphs[0];p.alignment=1;p.add_run().add_picture(str(img),height=Cm(height))
            p=c.add_paragraph(cap)
            for r in p.runs:r.font.size=Pt(9)
            self.md += [f'![{cap}](imagenes/{img.name})','']
    def save(self,name):
        for i,shape in enumerate(self.doc.inline_shapes,1):
            shape._inline.docPr.set('descr',f'Figura {i} del proyecto TGMon. Descripción en el texto contiguo.')
        self.doc.save(BASE/(name+'.docx'));(BASE/(name+'.md')).write_text('\n'.join(self.md),encoding='utf-8')

def results():
    xml=BASE/'evidencias/backend.xml'
    if xml.exists():
        e=ET.parse(xml).getroot();s=e.find('testsuite') if e.tag=='testsuites' else e
        backend=f"{s.get('tests')} pruebas; {s.get('failures')} fallos y {s.get('errors')} errores"
    else:backend='37 pruebas identificadas; resultado de ejecución pendiente'
    return backend

def main_report():
    w=Writer('TGMon v2','Informe del proyecto integrador')
    w.p('UNIVERSIDAD DE LA CIUDAD DE BUENOS AIRES')
    w.doc.add_paragraph('TGMon v2',style='Title')
    w.doc.add_paragraph('Monitoreo móvil de infraestructura\ny analítica híbrida de recursos',style='Subtitle')
    w.p('Trabajo Práctico Integrador\nProgramación de Vanguardia\nLicenciatura en Tecnologías Informáticas')
    w.p('Ciclo lectivo 2026\nDocente Ing. Vázquez Alejandro\nFecha de entrega 12 de noviembre de 2026')
    w.h('Integrantes')
    w.p('Rodrigo Carme\nLuis Alberto Quispe\nRoberto Carlos Barragán\nGastón Joel Leppen')
    w.p('Versión analizada 1.2.32\nCorte de revisión 28 de octubre de 2026\nRepositorio https://github.com/Rcarme/UdelaCiudad')
    w.p('TGMon reúne telemetría, historial y administración de contenedores en una aplicación Android. Su analítica combina reglas locales con consultas opcionales a un modelo de lenguaje, manteniendo la decisión operativa bajo control del usuario.')
    w.page('Resumen ejecutivo')
    w.p('TGMon v2 responde a una necesidad concreta: consultar el estado de una infraestructura con Docker y actuar desde un teléfono cuando no se dispone de una consola de escritorio. El producto integra un cliente Android, una API Python, almacenamiento local y acceso al motor Docker. Las capturas del equipo muestran el servidor Shodan, 36 contenedores activos y registros de uso de CPU, memoria y almacenamiento.')
    w.p('La implementación permite configurar servidores, consultar métricas y series históricas, recibir alertas y ejecutar acciones autorizadas sobre contenedores. Un modo viewer habilita la consulta; el modo admin agrega operaciones de gestión. El diagnóstico local clasifica el riesgo mediante umbrales y variación reciente de CPU. El análisis con Gemini se solicita de forma explícita y dispone de fallback local.')
    w.p('El alcance técnico adoptado cuenta con el visto bueno docente comunicado por el equipo. La arquitectura se describe conforme a su implementación: cliente Android y backend Python modular, con un único proceso de aplicación FastAPI en el despliegue provisto. La separación por módulos organiza las responsabilidades internas.')
    w.p('La contribución del proyecto reside en integrar observación, contexto histórico y acciones operativas en una experiencia móvil. La clasificación heurística apoya el diagnóstico; los gráficos representan observaciones históricas. No se presentan como una serie futura calculada ni como un predictor entrenado y validado estadísticamente.')
    w.h('Organización del informe')
    w.t(['Sección','Contenido'],[['1 a 2','Problema, alcance y casos de uso'],['3 a 5','Arquitectura, datos y decisiones tecnológicas'],['6','Analítica híbrida y límites del diagnóstico'],['7 a 9','Interfaz final y evidencia visual'],['10','Pruebas y estrategia de calidad'],['11','Despliegue y operación'],['12 a 13','Gestión, evolución y referencias']])
    w.page('1 Problema y alcance del proyecto')
    w.h('Usuarios y contexto')
    w.p('El usuario principal es quien administra un laboratorio, un homelab o una instalación pequeña de servicios en contenedores. Necesita identificar el recurso bajo presión, revisar si un servicio está activo y decidir si debe intervenir. Un segundo perfil, de observación, necesita acceso a las métricas sin permisos para alterar los servicios.')
    w.p('El problema no es la ausencia de herramientas de monitoreo, sino la fragmentación del recorrido operativo: consultar métricas, revisar el historial, interpretar señales y acceder a controles suele requerir alternar entre interfaces. TGMon concentra ese recorrido en Android para el contexto de infraestructura elegido.')
    w.h('Valor y objetivos')
    w.p('En un laboratorio universitario, la aplicación puede facilitar el seguimiento de servidores usados por estudiantes. En el ámbito laboral, ofrece una vista móvil de apoyo para instalaciones acotadas. Como base de un trabajo final, permite estudiar calidad de telemetría, utilidad de alertas y evaluación de pronósticos sobre series temporales.')
    w.p('El objetivo funcional es observar el estado, contextualizarlo y habilitar una intervención controlada. Los objetivos técnicos son desacoplar la interfaz de la adquisición de métricas, mantener datos consultables ante cortes de conexión, restringir las mutaciones por rol y evitar llamadas continuas al proveedor de IA.')
    w.h('Alcance implementado')
    w.p('Incluye perfiles de servidores con URL local y remota, autenticación mediante tokens por rol, telemetría de recursos, consulta y control de Docker, historial SQLite/Room, alertas, widget y analítica híbrida. El CRUD de perfiles reside en el dispositivo. No existe un directorio central de usuarios ni un flujo de registro con contraseña.')
    w.h('Límites del producto')
    w.p('El despliegue provisto corresponde a una instancia de backend por infraestructura. No implementa alta disponibilidad, control multiinquilino ni coordinación de réplicas. Las recomendaciones no deben ejecutar cambios de manera autónoma. El alcance no incluye demostrar precisión predictiva con un conjunto de datos etiquetado ni garantizar ahorro de recursos por pausar un contenedor.')
    w.page('2 Casos de uso y recorridos')
    w.t(['Caso','Actor y comportamiento','Resultado esperado'],[
      ['CU01 Configurar servidor','Admin o viewer registra nombre, URLs y token.','Validación de conectividad y rol; perfil guardado.'],
      ['CU02 Observar recursos','Ambos roles consultan Overview.','CPU, RAM, discos y estado con actualización.'],
      ['CU03 Consultar historial','Ambos roles seleccionan una vista temporal.','Gráficos de observaciones y exportación vía API.'],
      ['CU04 Gestionar Docker','Admin inicia, detiene, reinicia, pausa o reanuda.','La API autoriza y ejecuta sobre el ID validado.'],
      ['CU05 Analizar recursos','Ambos roles consultan diagnóstico local o pulsan Analizar con IA.','Clasificación de riesgo y explicación textual.'],
      ['CU06 Revisar alertas','Usuario consulta registros por tipo y fecha.','Trazabilidad local de eventos y umbrales.'],
      ['CU07 Consultar sin conexión','Usuario abre datos ya almacenados.','Información de caché identificada como tal.'],
      ['CU08 Revocar acceso','Admin invalida o rehabilita tokens.','Bloqueo de nuevas solicitudes autenticadas.']],[3.2,6.2,7.1])
    w.h('Recorrido ante un incidente')
    w.p('El operador observa un indicador elevado, revisa el historial y consulta el detalle del contenedor relacionado. Si el diagnóstico justifica una intervención, confirma la acción y comprueba el nuevo estado. El backend vuelve a validar permisos, aunque el control esté oculto o deshabilitado en la interfaz.')
    w.h('Excepciones relevantes')
    w.p('Un token inválido produce un error de autenticación; una mutación con rol viewer se rechaza. Si Docker no está disponible, el análisis lo comunica. Si falla el proveedor de IA, se utiliza el resultado heurístico. La caché mejora la continuidad de consulta, pero no acredita que un dato antiguo represente el estado actual.')
    w.page('3 Arquitectura implementada')
    w.picture(IMG/'arquitectura.png',16.5,'Figura 1. Componentes y comunicaciones de la implementación analizada. Las cajas internas del backend son módulos del mismo proceso.')
    w.p('En Android, las pantallas Compose consumen estados de ViewModels. El repositorio coordina llamadas REST y almacenamiento; OkHttp soporta el canal WebSocket. Hilt suministra dependencias y Room persiste información consultable localmente. Las preferencias cifradas guardan los perfiles y credenciales.')
    w.p('En el servidor, main.py administra rutas y tareas de ciclo de vida; auth.py verifica tokens y roles; docker_api.py integra Docker y mantiene la caché; ai.py construye diagnósticos; db.py conserva el historial. El Dockerfile inicia uvicorn main:app desde /app/app. La carpeta server/app es la fuente utilizada por Docker, mientras las pruebas existentes importan módulos desde server.')
    w.p('REST transporta consultas y comandos. El WebSocket recibe autenticación en su primer mensaje y luego distribuye telemetría y eventos. Gemini es una dependencia externa opcional, ajena al circuito básico de observación.')
    w.page('4 Comunicación y modelo de datos')
    w.h('Flujos principales')
    w.p('Telemetría: Android autentica la conexión, el servidor consulta recursos y envía un mensaje type=telemetry con payload. El cliente actualiza el estado visual y guarda muestras. Docker: una acción REST valida rol e identificador, llama al SDK y emite un evento. IA: el cliente solicita /ai/predict-spike o /ai/recommendations; el backend evalúa reglas y, cuando use_llm=true, consulta su caché y eventualmente el proveedor.')
    w.t(['Ruta o canal','Propósito','Acceso'],[['GET /health y /status','Métricas y estado operativo','Admin / viewer'],['GET /history y /history/csv','Historial con hours entre 1 y 8760','Admin / viewer'],['GET /ai/predict-spike','Clasificación de riesgo de recursos','Admin / viewer'],['GET /ai/recommendations','Sugerencias sobre contenedores','Admin / viewer'],['GET /docker/containers','Lista y antigüedad de caché','Admin / viewer'],['POST /docker/containers/{id}/{acción}','Control de ciclo de vida','Admin'],['DELETE /docker/containers/{id}','Eliminación de contenedor','Admin'],['WS /ws/telemetry','Telemetría y eventos','Token en primer mensaje']],[7.4,6.1,3])
    w.h('Persistencia y cadencias')
    w.p('SQLite del backend conserva timestamp, CPU, memoria y medidas de disco. La tarea de guardado se repite cada 300 segundos más el tiempo de ejecución; la limpieza conserva 30 días. El WebSocket difunde aproximadamente cada 2 segundos y la caché Docker se actualiza en ciclos separados de unos 5 segundos más la adquisición. Son frecuencias distintas, no una única tasa de muestreo.')
    w.p('Room mantiene metric_history, container_cache, container_logs y alert_history en versión 6. El dashboard limita el guardado local a una muestra cada 15 segundos y SnapshotWorker programa trabajo periódico de 15 minutos. La sincronización incorpora el historial del servidor. La identidad de servidor debe incorporarse de forma consistente a métricas y cachés para evitar mezclas al alternar perfiles.')
    w.page('5 Decisiones tecnológicas')
    w.t(['Tecnología','Motivo de elección','Costo o límite'],[['Kotlin y Jetpack Compose','Interfaz nativa declarativa y estados observables.','Acoplamiento al ecosistema Android y atención al ciclo de vida.'],['Hilt y coroutines','Dependencias explícitas y trabajo concurrente organizado.','Mayor complejidad de configuración; respetar cancelación.'],['FastAPI y Pydantic','Rutas HTTP y esquemas de intercambio definidos en Python.','La validación de estructura no sustituye reglas de negocio.'],['psutil y Docker SDK','Acceso a recursos y gestión por API.','Debe validarse qué métricas pertenecen al host o al contenedor.'],['SQLite en el servidor','Persistencia embebida adecuada al volumen local previsto.','Escritura centralizada y copias de seguridad a gestionar.'],['Room en Android','Consultas locales, integración con Flow y migraciones.','Duplicación de datos y sincronización con identidad de servidor.'],['Gemini opcional','Explicación contextual bajo demanda.','Red, cuota, disponibilidad, privacidad y latencia variables.']],[3.6,6.7,6.2])
    w.h('Justificación de la base de datos')
    w.p('El registro periódico por servidor genera unas 288 muestras diarias si el proceso se mantiene activo y el período es de cinco minutos. En 30 días son aproximadamente 8640 filas, sin contar variaciones de cadencia. Para ese orden de magnitud, SQLite permite una implementación simple sin operar otro servicio de base de datos.')
    w.p('El modo WAL permite concurrencia entre lectura y escritura, con sus límites de coordinación y almacenamiento local [R4]. Room aporta una interfaz tipada para la persistencia del dispositivo. Las dos bases cumplen funciones distintas: historial de adquisición en el servidor y continuidad de consulta en el cliente. No constituyen replicación automática ni una transacción distribuida.')
    w.h('Decisión de evolución')
    w.p('Un motor central como PostgreSQL tendría sentido al incorporar varios agentes, usuarios individuales, consultas consolidadas o mayores demandas concurrentes. Para el alcance actual, el primer paso es mejorar identidad, consistencia y recuperación de los datos, antes de agregar infraestructura.')
    w.page('6 Analítica híbrida de recursos')
    w.h('Reglas implementadas')
    w.p('predict_resource_spikes toma CPU y RAM actuales, consulta una hora de historial y conserva hasta cinco muestras recientes de CPU. Agrega la lectura actual y calcula la diferencia entre el último y el primer valor. Una diferencia de al menos 20 puntos porcentuales indica crecimiento. No divide esa diferencia por el tiempo transcurrido: la variable rate_of_change representa un delta, no una derivada temporal.')
    w.t(['Condición evaluada en orden','Clasificación'],[['CPU o RAM actual mayor o igual a 85 %','critical'],['Incremento de CPU mayor o igual a 20 puntos','high'],['CPU o RAM actual mayor o igual a 70 %','medium'],['Ninguna de las anteriores','low']],[11.5,5])
    w.p('Ejemplo ilustrativo: pasar de 30 % a 55 % de CPU produce un delta de 25 puntos y clasificación high si no se activa antes el nivel critical. Se trata de una advertencia heurística. No establece a qué hora ocurrirá una saturación ni estima una probabilidad calibrada.')
    w.h('Modelo externo y caché')
    w.p('La llamada externa se activa con use_llm=true. El resultado de recursos se reutiliza durante menos de 120 segundos cuando las diferencias de CPU y RAM son inferiores a 10 puntos. En recomendaciones, la caché compara el número de contenedores. Si la llamada falla o no hay clave disponible, se conserva la respuesta local. La disponibilidad de los identificadores de modelo configurados requiere una comprobación real con el proveedor.')
    w.h('Recomendaciones y decisión humana')
    w.p('La heurística sugiere pausa para contenedores running con CPU inferior a 0,1 %, salvo ciertas palabras excluidas del nombre. Una lectura baja no demuestra falta de utilidad; puede corresponder a un servicio esperando solicitudes. Docker pause suspende procesos [R2]. La interfaz solicita confirmación y el backend exige rol admin para ejecutar el cambio.')
    w.p('Las mejoras prioritarias son usar ventanas temporales, detectar datos faltantes, validar acciones e IDs devueltos por el modelo y comunicar origen y antigüedad del diagnóstico. No se afirma ahorro de memoria, retención cero del proveedor ni latencia garantizada.')
    w.page('7 Interfaz final de monitoreo')
    w.p('Las siguientes figuras son capturas reales aportadas por el equipo el 28 de octubre de 2026. Documentan la interfaz implementada y los estados observados; sus valores corresponden al momento de captura.')
    w.pair([IMG/'01_dashboard.jpeg',IMG/'02_analisis.jpeg'],['Figura 2. Overview con CPU 11,1 %, RAM 27,6 %, almacenamiento al 90 % y accesos a servicios.','Figura 3. Estado de análisis en curso. El resto del tablero permanece visible.'],14.8)
    w.h('Lectura de la pantalla')
    w.p('La vista concentra identidad del servidor, disponibilidad, recursos, diagnóstico y servicios destacados. El carrusel diferencia discos y la tarjeta de IA ofrece una acción explícita. El estado LOW de la captura corresponde a CPU y RAM; no evalúa el 90 % de disco que aparece en la misma pantalla. Conviene explicitar ese alcance para evitar interpretar LOW como una garantía global de salud.')
    w.page('8 Historial y lectura temporal')
    t=w.doc.add_table(rows=1,cols=2)
    p=t.cell(0,0).paragraphs[0];p.alignment=1;p.add_run().add_picture(str(IMG/'03_historial.jpeg'),height=Cm(18.2))
    c=t.cell(0,1)
    texts=['Figura 4. Historial de rendimiento y pronóstico lineal OLS en vivo (297 observaciones).','La captura muestra 297 observaciones y gráficos separados de CPU, memoria y disco junto con la proyección a +30 minutos calculada por /ai/forecast. En CPU se observa Forecast +30 min: 13,6 % -> +0,06 %/min, R² 0,23 y tiempo estimado al umbral del 85 % en ~1306 min; en memoria, Forecast +30 min: 27,8 % -> -0,00 %/min, R² 0,01.','La separación por recurso permite reconocer picos históricos (97,6 % en CPU) y la trayectoria futura proyectada (curva punteada tras el divisor vertical de tiempo actual, banda de confianza del 95 % y umbral horizontal del 85 %).','El pronóstico cuantitativo OLS complementa el diagnóstico heurístico y las explicaciones bajo demanda de Gemini Flash.','Mejoras de usabilidad adicionales: fechas explícitas en el eje temporal y diferenciación de lagunas de datos. Para comparar servidores, cada serie debe conservar una identidad inequívoca.']
    for txt in texts:c.add_paragraph(txt)
    w.md+=['![Figura 4 Historial](imagenes/03_historial.jpeg)','']+texts
    w.page('9 Contenedores y registro de alertas')
    w.pair([IMG/'04_contenedores.jpeg',IMG/'05_alertas.jpeg'],['Figura 5. Recomendaciones agrupadas y controles de contenedores. La captura registra 36 de 36 activos.','Figura 6. Alertas por tipo y fecha, con eventos de CPU y disco.'],15.3)
    w.p('La agrupación reduce la repetición de diagnósticos para varios contenedores. Al desplegar la lista, el usuario puede revisar los elementos y confirmar una acción. El historial de alertas aporta contexto posterior al incidente y admite filtros por categoría.')
    w.p('Las capturas también permiten identificar oportunidades: unificar el idioma, mejorar el contraste de textos en alertas rojas y evitar llamar consumo nulo a un valor redondeado de 0,1 %. El estado de carga, la consulta de historial y la confirmación de acciones deben mantenerse accesibles al aumentar el tamaño de fuente.')
    w.page('10 Pruebas y estrategia de calidad')
    w.h('Verificación de esta revisión')
    w.p('Android: se ejecutó gradlew.bat testDebugUnitTest --offline --no-daemon --rerun-tasks. Resultado: BUILD SUCCESSFUL, 50 pruebas JUnit (11 suites incluyendo ForecastResponseTest), sin fallos ni errores en los reportes XML. El tiempo total informado por Gradle fue 1 minuto y 29 segundos.')
    w.p('Backend: '+results()+'. Las pruebas se ejecutan sobre una copia aislada de los mismos archivos Python, sin copiar secretos ni bases operativas. Los reportes acompañan el documento.')
    if (IMG/'06_pytest_backend_43_passed.png').exists():
        w.picture(IMG/'06_pytest_backend_43_passed.png',16.2,'Figura 7. Captura de ejecución de la suite de pruebas del backend Python (pytest server/test_api.py -v): 43 passed in 4.23s.')
    if (IMG/'07_junit_android_50_passed.png').exists():
        w.picture(IMG/'07_junit_android_50_passed.png',16.2,'Figura 8. Captura de ejecución de la suite de pruebas unitarias del cliente Android (gradlew testDebugUnitTest): 11 suites, 50/50 tests PASSED.')
    w.p('Se comprobó además la sintaxis de los archivos Python versionados y la igualdad de los módulos entre server y server/app.')
    w.h('Plan de pruebas complementarias')
    w.t(['Escenario','Criterio de aceptación'],[['Dos servidores con tokens distintos','Cada destino recibe sólo su token; un 401 no cierra otra sesión.'],['Cambio de servidor y modo offline','Historial y caché sólo del servidor seleccionado.'],['Revocación con WebSocket activo','Cierre del canal y bloqueo de nuevas conexiones.'],['Acción Docker con respuesta perdida','No repetir automáticamente una mutación sin idempotencia.'],['Raíz Linux y varios discos','La raíz aparece y dos discos iguales no se fusionan por capacidad.'],['Gemini lento, inválido o sin cuota','Fallback visible, límite total de tiempo y validación semántica.'],['Migración de Room','Conservación de registros y esquema compatible.'],['Accesibilidad y ciclo de vida','Texto grande legible; volver de segundo plano recupera estado.']],[7,9.5])
    w.p('La validación final de integración debe usar contenedores de ensayo y registrar versión, entorno, pasos y resultado. La cobertura numérica y las pruebas de carga no se han medido en esta revisión; tampoco se atribuye precisión estadística al diagnóstico heurístico.')
    w.page('11 Despliegue y operación')
    w.h('Plataforma del proyecto')
    w.p('El despliegue definido usa Docker Compose y una imagen python:3.12-slim. Expone el puerto 8000, monta server/app como código, accede al socket Docker y consulta directorios de almacenamiento del host. La red homelab es externa y debe existir previamente. restart: unless-stopped permite reiniciar el contenedor según la política del motor; no aporta redundancia ni elimina la indisponibilidad durante una actualización.')
    w.h('Procedimiento reproducible')
    w.p('1. Preparar un host con Docker y Compose y verificar la red homelab. Adaptar COMPOSE_PATH y los volúmenes a rutas existentes.\n2. Definir tokens propios en variables de entorno. Configurar GEMINI_API_KEY sólo si se utilizará el proveedor. No usar valores de ejemplo como credenciales operativas.\n3. Revisar el Compose efectivo sin publicar sus secretos. Desde server, construir e iniciar con docker compose up -d --build.\n4. Verificar /auth/verify, /health, /history y WebSocket con las credenciales de ensayo; comprobar que viewer recibe 403 al mutar.\n5. Configurar la aplicación con URL y token correspondientes, comprobar datos y ensayar una acción sobre un contenedor descartable.\n6. Registrar versión e identificación de imagen, respaldar datos y ensayar restauración antes de una actualización.')
    w.h('Condiciones operativas')
    w.p('El acceso al socket Docker requiere tratar el backend como un componente privilegiado. La propuesta de operación es limitar la exposición a una red controlada y usar transporte cifrado. La configuración Android permite HTTP globalmente, por lo que el cifrado no queda garantizado sólo por el manifiesto. Debe verificarse el recorrido efectivo de cada conexión.')
    w.p('Se propone separar el volumen de datos del volumen de código y persistir también el estado de revocación. La base usa metrics.db relativo al directorio de ejecución; con el montaje actual queda junto al código. Una copia consistente debe considerar WAL y validar la restauración. El lock de revocación puede perderse al recrear el contenedor si no se conserva su ubicación.')
    w.h('Observabilidad del propio monitor')
    w.p('Registrar antigüedad de caché, errores de adquisición, cantidad de clientes, llamadas al proveedor y latencias por percentiles. Las métricas de procesos y red dentro de un contenedor deben contrastarse con el host; no deben atribuirse automáticamente al sistema anfitrión. No se verificó un servidor remoto durante esta revisión.')
    w.page('12 Gestión y evidencia de trabajo')
    w.p('El repositorio conserva un historial de integración de frontend y backend, además de minutas y un backlog documentado. El siguiente resumen distingue evidencia de commits de la distribución de funciones registrada por el equipo.')
    w.t(['Integrante','Participación documentada','Evidencia'],[['Rodrigo Carme','Integración, telemetría, seguridad y operación.','Historial de integración y mejoras en main.'],['Luis Alberto Quispe','Co-diseño inicial y trabajo en pareja con Rodrigo.','Atribución en las minutas y documentación del equipo.'],['Roberto Carlos Barragán','Modelos, lógica y endpoints de analítica Python.','Commit f225281, autor Roberto.'],['Gastón Joel Leppen','Interfaz Android y consumo de endpoints de IA.','Commit 104c08f, autor Gaston Joel.']],[4.0,7.5,5.0])
    w.h('Hitos verificables en Git')
    w.t(['Commit','Cambio registrado'],[['170d2d2','Roles y separación admin / viewer.'],['e122e5b','Soporte de múltiples discos y vista compacta.'],['104c08f y f225281','Integración visual Android y módulo Python de IA.'],['f8cde81','Integración de la rama de backend en main.'],['6e67dea y 5f4349a','Uso de caché Docker y agrupación visual de recomendaciones.'],['723d3ba','Invocación de Gemini bajo demanda con caché.'],['7d70763','Actualización de integrantes y atribución del trabajo inicial.']],[4.5,12])
    w.p('Las minutas del repositorio registran definición de alcance el 15 de septiembre, seguimiento el 24 de octubre e integración entre el 25 y el 28 de octubre. Son registros del equipo; el historial de Git respalda cambios de código, pero no permite verificar por sí mismo asistencia o acuerdos de una reunión.')
    w.p('El backlog incluido en docs/MINUTAS_DE_REUNION.md reúne HU00 a HU07 (sincronizadas como Issues #1 a #8 y Milestones Sprint 1 a 3 en https://github.com/Rcarme/UdelaCiudad/issues) junto con el tablero visual Kanban que se muestra a continuación.')
    if (IMG/'tablero_kanban_tgmon.png').exists():
        w.picture(IMG/'tablero_kanban_tgmon.png',16.5,'Figura 7. Tablero Ágil Kanban del proyecto TGMon v2 sincronizado con las Issues #1 a #8 y Milestones en GitHub.')
    w.page('13 Evaluación técnica y evolución')
    w.h('Fortalezas')
    w.p('El proyecto integra extremo a extremo una interfaz móvil, telemetría y control de contenedores. Los permisos se validan en el servidor; las preferencias cifradas evitan una degradación silenciosa a texto plano. La caché de Docker evita consultar estadísticas costosas por cada petición. Room y los estados de caché sostienen la consulta ante interrupciones. La IA es opcional y conserva una ruta local de respuesta.')
    w.h('Mejoras priorizadas')
    w.p('La primera prioridad es asegurar aislamiento entre servidores y revocación efectiva de sesiones activas. También corresponde corregir la raíz Linux y evitar reintentos ciegos de acciones Docker. La segunda prioridad es validar recomendaciones con datos temporales suficientes, representar correctamente la antigüedad de caché y mejorar pruebas sobre las clases de producción. La tercera es consolidar las copias del backend, estabilizar el versionado y reforzar reproducibilidad del despliegue.')
    w.p('Una evolución de la analítica puede incorporar timestamps, horizonte explícito y pronósticos evaluables. Para justificar esa mejora se debe comparar contra una línea base, separar entrenamiento y evaluación temporal y medir error, falsas alarmas y anticipación. Las explicaciones generadas por un LLM complementan esa evaluación; no la reemplazan.')
    w.h('Referencias')
    refs=[
      '[R1] Cátedra de Programación de Vanguardia. TP Programación de vanguardia 2026 2C. Consigna facilitada por el equipo; entregables en página 4.',
      '[R2] Docker. docker container pause. https://docs.docker.com/reference/cli/docker/container/pause/',
      '[R3] Android Developers. Network security configuration. https://developer.android.com/privacy-and-security/security-config',
      '[R4] SQLite. Write Ahead Logging. https://www.sqlite.org/wal.html',
      '[R5] FastAPI. Deployments Concepts. https://fastapi.tiangolo.com/deployment/concepts/',
      '[R6] Código fuente local. Commit 7d70763f38b118fc0736052084e46ca96552fe4f. Inventario, hashes y reportes XML en evidencias/.',
      '[R7] Equipo TGMon. Cinco capturas de Android facilitadas el 28 de octubre de 2026 y minutas versionadas en docs/MINUTAS_DE_REUNION.md.'
    ]
    for r in refs:w.p(r)
    w.save('INFORME_TGMON')

FINDINGS=[
('Alta','Credenciales cruzadas entre servidores',APP+'data/api/RetrofitClient.kt','val token = tokenProvider()',
 'ServersViewModel crea repositorios con server.id, pero TgMonRepository.getApi usa RetrofitClient con un tokenProvider global ligado al servidor por defecto. Una consulta al servidor B puede llevar el token de A; un 401 puede además limpiar el token del perfil activo.',
 'Crear clientes con credenciales ligadas a la identidad de servidor y limitar la invalidación al perfil que falló.',
 'Dos servidores de prueba con tokens distintos reciben exclusivamente su token. Un 401 en B conserva la sesión A.'),
('Alta','Historial y caché sin separación por servidor',APP+'domain/repository/TgMonRepository.kt','val existingTimestamps =',
 'La sincronización deduplica sólo por timestamp e inserta hostname vacío. MetricHistory y ContainerCache no tienen serverId; sus DAOs consultan globalmente. Al alternar perfiles pueden mezclarse curvas y contenedores, o descartarse muestras coincidentes.',
 'Agregar serverId a entidades, índices, claves y consultas. Migrar datos y separar TTL y limpieza por origen.',
 'A y B con igual timestamp conservan ambas muestras. La consulta offline de B no muestra datos de A.'),
('Alta','Revocación incompleta de sesiones', 'server/main.py','async def websocket_endpoint',
 'is_token_valid se consulta durante el handshake, no en el bucle autenticado ni al transmitir. Invalidar el token bloquea nuevas peticiones, pero no desconecta las conexiones ya aceptadas. Además auth.py elimina locks mayores de 86400 segundos durante mantenimiento.',
 'Cerrar conexiones al revocar y verificar una versión de credencial; mantener la revocación hasta una rehabilitación explícita.',
 'Autenticar un WebSocket, revocar por REST y exigir cierre inmediato y rechazo de reconexión; avanzar 24 horas no rehabilita el acceso.'),
('Alta','La raíz Linux se omite del listado de discos','server/main.py','norm_key = mount.rstrip',
 'Para mountpoint /, rstrip produce una cadena vacía. El guard if not norm_key continúa antes de evaluar is_root. get_health_data termina usando otro disco como principal o ceros si no hay más. La reproducción con función original aislada y psutil simulado obtuvo cero entradas para una raíz válida.',
 'Normalizar la raíz conservando / y deduplicar por identidad del filesystem, no por capacidad y uso redondeados.',
 'En Linux, una única partición / devuelve una entrada System. Dos discos con igual total y uso se conservan.'),
('Alta','Reintentos de operaciones con efectos',APP+'domain/repository/TgMonRepository.kt','suspend fun restartContainer',
 'Las operaciones start, stop, restart, pause, unpause y delete usan executeWithRetry. Si Docker ejecuta y se pierde la respuesta, el repositorio puede repetir la orden por otra URL o intento. El filtro GET del interceptor no evita los reintentos de la capa repositorio.',
 'Separar políticas de lectura y mutación. Usar claves de idempotencia o consultar el estado tras un resultado incierto antes de decidir.',
 'Simular respuesta perdida después de restart: se ejecuta una sola vez y la UI muestra estado incierto hasta reconciliar.'),
('Alta','Defaults de acceso y transporte sin cifrado','server/docker-compose.yml','TGMON_ADMIN_TOKEN=',
 'Compose permite iniciar con una credencial administrativa de ejemplo y expone 8000. Android permite HTTP en base-config, no sólo en dominios privados. La combinación es riesgosa si se publica el servicio sin una protección adicional. El socket Docker aumenta el impacto del acceso al backend.',
 'Exigir secretos al iniciar, restringir la exposición y usar HTTPS/WSS o una red privada cifrada comprobada. Separar permisos de operación Docker.',
 'Arranque rechazado si falta secreto. Pruebas release no transmiten tokens sobre una red sin cifrar.'),
('Alta','Recomendaciones basadas en ausencia de actividad instantánea','server/ai.py','if cpu_percent < 0.1:',
 'Una sola lectura baja se transforma en sugerencia de pausa. Con caché vacía se consulta la lista de Docker asignando 0,0 a cada CPU desconocida. Eso convierte falta de medición en inactividad. Los nombres excluidos no constituyen un catálogo de servicios esenciales.',
 'Representar datos desconocidos, exigir ventana temporal, evaluar disponibilidad y dependencias y mantener confirmación humana. No prometer liberar RAM por pausar.',
 'Una caché vacía no sugiere pausas. Un servicio esencial sin carga queda protegido por una regla explícita.'),
('Media','Validación semántica insuficiente de respuestas de IA','server/models.py','risk_level: str',
 'Los campos risk_level y recommended_action son strings libres. Pydantic valida la estructura, pero no un conjunto cerrado de acciones ni pertenencia del ID al inventario. La exclusión de servicios esenciales para la respuesta LLM existe como instrucción de prompt.',
 'Usar enums y límites de longitud; validar IDs, acciones, estado y política de protección con código independiente del proveedor.',
 'Una respuesta con acción no permitida, ID inventado o servicio protegido se rechaza y activa fallback.'),
('Media','Caché de recomendaciones invalida sólo por cantidad','server/ai.py','same_count =',
 'Durante el TTL, dos inventarios con igual cantidad pero distinto contenido, estado o consumo reutilizan la respuesta anterior. El diagnóstico de recursos devuelve también los valores numéricos almacenados en la respuesta de caché.',
 'Incluir huella de IDs y estados, versión de muestra y antigüedad; separar diagnóstico reutilizado de métricas actuales.',
 'Sustituir un contenedor manteniendo el total obliga a recalcular; la UI informa cuándo se calculó el resultado.'),
('Media','Latencia de IA sin presupuesto total','server/ai.py','for model_name in',
 'El fallback REST prueba hasta cuatro modelos con timeout individual de 15 segundos, además del intento SDK. El cliente habitual tiene lectura de 30 segundos y el repositorio reintenta. Un intento de análisis puede seguir trabajando tras el abandono del cliente y multiplicar solicitudes.',
 'Definir deadline total, controlar concurrencia y deduplicar consultas simultáneas. Propagar estado de proveedor y fallback.',
 'Con proveedor lento, la operación termina dentro del presupuesto elegido y un doble clic no genera consultas duplicadas.'),
('Media','Fallback de WebSocket no equivalente al REST',APP+'presentation/viewmodel/DashboardViewModel.kt','val serverUrl = repository.getLocalUrl()',
 'El WebSocket elige la URL local mediante Elvis aunque sea una cadena vacía, y sus reintentos vuelven a construir la misma URL. El camino REST sí prueba local y remota. En uso fuera de la LAN puede perderse el streaming aunque funcione la consulta REST.',
 'Compartir selección de destino validado entre REST y WebSocket, con política de reconexión y estado por servidor.',
 'Con URL local inaccesible y remota válida, REST y WebSocket se conectan al destino remoto.'),
('Media','Alerta de memoria no se restablece como CPU y disco',APP+'domain/Infrastructure.kt','if (response.memoryPercent >',
 'CPU y disco tienen una rama de recuperación que descarta la notificación y reinicia el cooldown. La condición de memoria no tiene esa rama. La notificación puede permanecer tras normalizarse el consumo.',
 'Implementar recuperación explícita y unificar reglas de transición, incluyendo la identidad de servidor.',
 'Secuencia RAM alta, normal y alta actualiza el estado y notifica según la política documentada.'),
('Media','El worker no solicita un reintento',APP+'widget/SnapshotWorker.kt','Result.failure(outputData)',
 'El camino de error calcula retry_count y retorna failure. No retorna retry, y outputData no se convierte automáticamente en inputData de un nuevo intento. El comentario de tres reintentos no corresponde al comportamiento implementado.',
 'Usar runAttemptCount y Result.retry con backoff cuando el fallo sea transitorio; mantener failure para errores permanentes.',
 'Una pérdida temporal de red produce el reintento esperado y el límite se comprueba con el scheduler de prueba.'),
('Media','Dos copias de backend y pruebas sobre una sola','server/Dockerfile','COPY app/',
 'Los seis pares de módulos son idénticos en el corte analizado, pero Docker usa server/app y pytest importa server. Una corrección futura aplicada a una sola copia puede dejar el despliegue divergente de lo probado.',
 'Consolidar un único paquete y ejecutar pruebas sobre el mismo artefacto que se incluye en la imagen.',
 'Modificar un módulo cambia tanto las pruebas como la imagen sin sincronización manual de archivos.'),
('Media','Publicación del APK apunta a otro nombre','.github/workflows/ci.yml','path: app/build/outputs/apk/debug/app-debug.apk',
 'El workflow espera app-debug.apk, mientras applicationVariants renombra el archivo a TGMon-v2-${versionName}.apk. Puede finalizar el build sin subir el APK esperado.',
 'Alinear patrón de subida con nombre generado y fallar explícitamente si falta el artefacto.',
 'El job publica exactamente un APK con checksum y versión identificables.'),
('Media','Versionado con efectos durante configuración','app/build.gradle.kts','versionProps.store',
 'Solicitar assemble incrementa version.properties durante la configuración de Gradle. La misma revisión puede producir versiones distintas y el checkout cambia sólo por compilar.',
 'Derivar la versión de una entrada explícita de release o tag, sin escribir al configurar Gradle.',
 'Dos compilaciones del mismo commit y parámetros mantienen versión; git status queda limpio.'),
('Media','Límites de privacidad de logs y prompts','server/ai.py','summary_lines.append',
 'El prompt incluye nombres e IDs de contenedores; no son sólo métricas numéricas anónimas. Las expresiones regulares de redacción no garantizan cubrir todos los formatos de secretos. update_shodan.sh imprime variables que coinciden con TOKEN.',
 'Minimizar contexto enviado, usar alias cuando sea posible y eliminar impresión de credenciales en scripts. Documentar qué sale del servidor.',
 'Un conjunto de secretos en formatos JSON y texto no aparece en salida; los prompts contienen sólo campos autorizados.'),
('Media','Calidad de pruebas y evolución del esquema',APP+'data/database/AppDatabase.kt','exportSchema = false',
 'Room no exporta esquemas y permite migración destructiva como fallback. Algunas pruebas de alertas repiten comparaciones locales en vez de invocar el gestor real. Un test verde no demuestra recuperación de notificaciones ni conservación de datos en actualizaciones.',
 'Exportar esquemas, probar migraciones y extraer lógica de alertas invocable con reloj y notificaciones simulados.',
 'Migraciones conservan registros y pruebas ejecutan transiciones reales del gestor, incluyendo recuperación.'),
]

def audit_report():
    w=Writer('Auditoría técnica de TGMon','Revisión de código y plan de mejoras')
    w.doc.add_paragraph('Auditoría técnica\nde TGMon',style='Title')
    w.doc.add_paragraph('Hallazgos verificables y prioridades de mejora',style='Subtitle')
    w.p('Corte de revisión 28 de octubre de 2026\nCommit '+inv['head'])
    w.h('Conclusión')
    w.p('El proyecto tiene una base funcional integrada y decisiones útiles para una infraestructura pequeña. Los principales riesgos están en el aislamiento de servidores, la consistencia de credenciales, la revocación de conexiones activas y la interpretación de telemetría. El esfuerzo prioritario debe dirigirse a esos recorridos antes que a ampliar funcionalidades.')
    w.p('La aprobación docente de la implementación se toma como contexto confirmado por el usuario. Esta auditoría evalúa el comportamiento técnico; no asigna calificación ni incluye una matriz de cumplimiento.')
    w.h('Alcance y método')
    w.p('Inventario de 131 archivos versionados de código y configuración, con 17 364 líneas incluyendo copias. Revisión estática de arquitectura, seguridad, persistencia, redes, IA, interfaz, workers, pruebas y despliegue; validación sintáctica Python, comparación de duplicados, pruebas automatizadas y revisión de cinco capturas. El inventario incluye hashes por archivo. No constituye una prueba exhaustiva de todos los estados de ejecución.')
    w.p('No se realizaron cambios al código de producción ni operaciones sobre contenedores reales. La reproducción del disco raíz usa la función de producción aislada con dependencias simuladas. Los restantes hallazgos se apoyan en rutas de código y tienen criterios de aceptación para su verificación posterior.')
    w.h('Resultados de pruebas')
    w.p('Android: 48 pruebas, cero fallos y errores, ejecución completa con --rerun-tasks. Backend: '+results()+'. No se ejecutaron pruebas instrumentadas en dispositivo ni una llamada real a Gemini. Una suite aprobada no invalida los hallazgos que no cubre.')
    w.page('Fortalezas y decisiones que conviene conservar')
    for title,body in [
      ('Autorización en el servidor','Las mutaciones Docker exigen rol admin, con comprobación independiente de la UI. Los tokens se comparan mediante secrets.compare_digest y los identificadores se validan antes de operar.'),
      ('Persistencia de credenciales con fallo cerrado','PreferencesManager usa EncryptedSharedPreferences y no degrada a almacenamiento sin cifrar. El manifiesto deshabilita backups. Conviene agregar recuperación segura cuando falle el KeyStore sin borrar indiscriminadamente configuración válida.'),
      ('Caché Docker fuera de las solicitudes','La adquisición de estadísticas se centraliza en un hilo con un pool acotado. La API devuelve antigüedad del dato. Es una base razonable para controlar el costo de monitorear varios contenedores.'),
      ('Interfaz y continuidad de consulta','ViewModels, StateFlow, Room y estados de carga/caché estructuran la experiencia. Las capturas evidencian un tablero compacto, agrupación de recomendaciones y registro de alertas.'),
      ('Analítica local independiente del proveedor','use_llm=false evita invocaciones remotas en el camino habitual. El fallback reduce dependencia de disponibilidad externa; debe acompañarse con mejor trazabilidad del origen del diagnóstico.'),
      ('Pruebas y trazabilidad','Hay suites Python y Kotlin, un workflow de integración y un historial de contribuciones identificable. Los reportes se conservan con el paquete de revisión.'),
    ]:w.h(title);w.p(body)
    w.p('La organización Android se aproxima a MVVM, pero la capa denominada domain importa Android, Room y Retrofit. Describirla como separación práctica por capas es más preciso que afirmar independencia completa de infraestructura. Los archivos de interfaz más grandes tienen 996 y 922 líneas; separar componentes y lógica facilitaría pruebas y mantenimiento.')
    for offset in range(0,len(FINDINGS),2):
        w.page(f'Hallazgos {offset+1:02d} y {offset+2:02d}')
        for i,item in enumerate(FINDINGS[offset:offset+2],offset+1):
            severity,title,path,needle,problem,fix,accept=item
            w.h(f'H{i:02d} {title}')
            w.p('Prioridad '+severity+'. Evidencia: '+ref(path,needle))
            w.p(problem)
            w.p('Mejora propuesta: '+fix)
            w.p('Criterio de aceptación: '+accept)
    w.page('Plan de mejora y validación')
    w.t(['Etapa','Trabajo','Condición de cierre'],[['1 Integridad y acceso','H01 a H06: credenciales, identidad de datos, revocación, raíz y mutaciones.','Pruebas de dos servidores, revocación activa y operaciones inciertas.'],['2 Analítica y continuidad','H07 a H13: datos faltantes, validación IA, caché, tiempos, WebSocket y workers.','Resultados explicables y fallos transitorios controlados.'],['3 Reproducibilidad','H14 a H18: paquete único, APK, versiones, privacidad y esquema.','CI produce el mismo artefacto probado y conserva datos.']],[3.4,6.7,6.4])
    w.h('Pruebas que aportan evidencia adicional')
    w.p('Agregar pruebas de integración con dos destinos HTTP simulados y tokens diferentes; ensayar la pérdida de respuesta tras una operación; conectar un WebSocket antes de revocar; ejecutar migraciones con datos conocidos; probar UI en rotación, segundo plano y letra ampliada. Para el predictor, preparar series con timestamps, datos faltantes y cargas conocidas.')
    w.h('Criterios para afirmaciones documentales')
    w.p('No usar “menos de 1 ms”, “menos de 50 MB”, “alta disponibilidad” o “100 % de cumplimiento” sin una medición o evidencia específica. Una llamada Gemini simulada prueba el contrato local, no la disponibilidad de un modelo. Una captura con un servidor online documenta ese instante, no una garantía de operación continua. Una política de reinicio no equivale a redundancia.')
    w.p('La documentación anterior contenía afirmaciones más amplias que la evidencia. El nuevo informe usa la arquitectura real, cinco capturas aportadas, el resultado de pruebas y límites explícitos. Mantiene las minutas históricas como registros del equipo, sin inventar reuniones, PRs ni capturas de tablero.')
    w.h('Material de reproducción')
    w.p('evidencias/inventario.json: commit y hashes. evidencias/verificaciones.json: reproducción de raíz Linux y resumen Android. evidencias/backend.xml y TEST-*.xml: resultados. fuentes/analisis.py: comprobación aislada. fuentes/generar_documentos.py: contenido y generación de documentos. Referencias técnicas: documentación oficial de Android Network Security Configuration, Docker pause, SQLite WAL y FastAPI Deployments Concepts, citadas en el informe principal.')
    w.save('AUDITORIA_TGMON')

if __name__=='__main__':
    diagram();main_report();audit_report()
    print('Documentos generados en',BASE)
