# Charo Daemon

Charo Daemon es un servicio en Java 17 compuesto por tres modulos principales:

- system-monitor: obtiene metricas del sistema operativo (CPU, memoria RAM, interfaces de red) y controla una lista configurable de procesos de interes.
- rest-server: expone una API HTTP minima basada en com.sun.net.httpserver.HttpServer para consultar metricas recientes y ajustar parametros del monitor.
- mqtt-publisher: consume la API REST, promedia ventanas configurables de muestras y publica los resultados en un topico MQTT.

El modulo daemon-app enlaza a los tres anteriores y ofrece un proceso ejecutable estilo demonio.

## Integracion con otros proyectos

- monimonitor (server central) y/o cualquier consumidor se suscriben al broker MQTT en los topicos normalizados `charodaemon/host/{clientId}/metrics` y `charodaemon/host/{clientId}/status`.
- Panelito (app Android) consume exclusivamente por MQTT. No utiliza HTTP.
- La API HTTP incluida en este proyecto es solo para uso interno del daemon (telemetria local y ajuste de configuracion) y para herramientas de depuracion; no es el canal de consumo de Panelito.

## Contratos MQTT normalizados

- Topico normalizado por host: `charodaemon/host/{clientId}/metrics` (unico esquema soportado).
  - `{clientId}` se obtiene segun la logica de `CharoDaemon` (id configurado + hostname + huella HW).
- Disponibilidad del agente (LWT): `charodaemon/host/{clientId}/status` con mensajes retenidos `online`/`offline`.

No existe publicacion legada ni dual-publish. Solo se publica en el topico normalizado por host.

## Requisitos

- JDK 17+
- Gradle 8+ (o utilizar el wrapper si se agrega posteriormente)
- Un broker MQTT accesible (configurado explícitamente en `config/daemon.properties`)

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

Editar exclusivamente `config/daemon.properties` para ajustar:

- monitor.interval.seconds: frecuencia de muestreo del monitor (default 20s).
- monitor.process.watchlist: archivo con procesos a vigilar (ruta relativa al archivo de propiedades).
- monitor.network.interface.exclude: archivo con palabras clave para excluir interfaces de red.
- rest.port: puerto de la API REST.
- mqtt.*: parametros del publicador MQTT (URI, plantilla de topico `charodaemon/host/{clientId}/metrics`, ventana de muestreo y client id).

Campos MQTT adicionales para topicos normalizados y disponibilidad:

- mqtt.topic.template: plantilla del topico por host. Ej: `charodaemon/host/{clientId}/metrics`.
- mqtt.availability.enabled: `true|false` publica estado online/offline mediante LWT.
- mqtt.availability.topic: plantilla del topico de disponibilidad. Por defecto `charodaemon/host/{clientId}/status`.
- mqtt.retain.availability: `true|false` usa retained para el estado (recomendado `true`).

La lista de procesos se define en `config/processes.txt`, anotando un ejecutable por linea (por ejemplo mysqld.exe, nginx). Debe existir.

Las exclusiones de interfaces de red se especifican en config/network-interface-excludes.txt. Cada linea relevante agrega una palabra clave (case-insensitive) que, si aparece en el displayName o en la ruta de la interfaz, evita que esa NIC forme parte de las metricas. Las lineas vacias o iniciadas con # se ignoran.

## Endpoints REST

- GET /metrics: Ultima muestra disponible (CPU, memoria, redes, procesos vigilados).
- GET /config: configuracion activa (intervalo de muestreo, lista de procesos y filtros de interfaces).
- POST /config/interval: actualiza el intervalo en segundos. JSON esperado: {"seconds":30}.
- POST /config/processes: reemplaza la watch-list. JSON esperado: {"processNames":["chrome.exe","nginx"]}.

Nota: estos endpoints son expuestos por el daemon en el host local y sirven como fuente para el modulo `mqtt-publisher`. Panelito no los consume.

## Ejecucion

Compilar y ejecutar el daemon:

```
gradle :daemon-app:run
```

Notas de ejecucion:
- El daemon SIEMPRE lee configuracion de `config/daemon.properties` (ruta fija relativa al proyecto/directorio de trabajo). Si el archivo no existe o falta una clave obligatoria, el proceso falla y sale con error. No hay valores por defecto ni rutas alternativas.

Durante la ejecucion se registrara el monitor, el servidor REST y el publicador MQTT. Detener con Ctrl+C.

## Notas

- Si el broker MQTT no esta disponible, el daemon arrancara igualmente y seguira intentando reconectar gracias a automaticReconnect.
- Las metricas dependen de la informacion brindada por el sistema operativo y pueden variar en precision segun la plataforma.
