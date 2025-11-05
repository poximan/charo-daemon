# Charo Daemon

Charo Daemon es un servicio en Java 17 compuesto por tres modulos principales:

- system-monitor: obtiene metricas del sistema operativo (CPU, memoria RAM, interfaces de red) y controla una lista configurable de procesos de interes.
- rest-server: expone una API HTTP minima basada en com.sun.net.httpserver.HttpServer para consultar metricas recientes y ajustar parametros del monitor.
- mqtt-publisher: consume la API REST, promedia ventanas configurables de muestras y publica los resultados en un topico MQTT.

El modulo daemon-app enlaza a los tres anteriores y ofrece un proceso ejecutable estilo demonio.

## Requisitos

- JDK 17+
- Gradle 8+ (o utilizar el wrapper si se agrega posteriormente)
- Un broker MQTT accesible (por defecto tcp://localhost:1883)

## Estructura de directorios

```
config/
  daemon.properties     # Configuracion principal del daemon
  processes.txt         # Lista de procesos a vigilar (uno por linea)
  network-interface-excludes.txt # Palabras clave para filtrar interfaces virtuales/no deseadas
system-monitor/
rest-server/
mqtt-publisher/
daemon-app/
```

## Configuracion

Editar config/daemon.properties para ajustar:

- monitor.interval.seconds: frecuencia de muestreo del monitor (default 20s).
- monitor.process.watchlist: archivo con procesos a vigilar (ruta relativa al archivo de propiedades).
- monitor.network.interface.exclude: archivo con palabras clave para excluir interfaces de red.
- rest.port: puerto de la API REST.
- mqtt.*: parametros del publicador MQTT (URI, topico, ventana de muestreo y client id).

La lista de procesos se define en config/processes.txt, anotando un ejecutable por linea (por ejemplo mysqld.exe, nginx).

Las exclusiones de interfaces de red se especifican en config/network-interface-excludes.txt. Cada linea relevante agrega una palabra clave (case-insensitive) que, si aparece en el displayName o en la ruta de la interfaz, evita que esa NIC forme parte de las metricas. Las lineas vacias o iniciadas con # se ignoran.

## Endpoints REST

- GET /metrics: Ultima muestra disponible (CPU, memoria, redes, procesos vigilados).
- GET /config: configuracion activa (intervalo de muestreo, lista de procesos y filtros de interfaces).
- POST /config/interval: actualiza el intervalo en segundos. JSON esperado: {"seconds":30}.
- POST /config/processes: reemplaza la watch-list. JSON esperado: {"processNames":["chrome.exe","nginx"]}.

## Ejecucion

Compilar y ejecutar el daemon:

```
gradle :daemon-app:run
```

Se puede indicar un archivo de configuracion alternativo pasando la ruta como argumento en Gradle:

```
gradle :daemon-app:run --args="c:/ruta/a/mi-configuracion.properties"
```

Durante la ejecucion se registrara el monitor, el servidor REST y el publicador MQTT. Detener con Ctrl+C.

## Notas

- Si el broker MQTT no esta disponible, el daemon arrancara igualmente y seguira intentando reconectar gracias a automaticReconnect.
- Las metricas dependen de la informacion brindada por el sistema operativo y pueden variar en precision segun la plataforma.

