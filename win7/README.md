# Charo Daemon Win7

Servicio Java 17 para metricas de sistema, procesos vigilados y publicacion MQTT.

## Modulos

- `system-monitor`: CPU, memoria, red y procesos.
- `rest-server`: API HTTP local.
- `mqtt-publisher`: promedios y publicacion MQTT.
- `daemon-app`: proceso ejecutable.

## Contratos

- Metricas: `charodaemon/host/{clientId}/metrics`.
- Disponibilidad LWT retenida: `charodaemon/host/{clientId}/status` con `online`/`offline`.
- HTTP local: `GET /metrics`, `GET /config`, `POST /config/interval` y `POST /config/processes`.

Panelito consume MQTT, no la API HTTP.

## Configuracion

Copiar `config/daemon.properties.example` como `config/daemon.properties`. El archivo local no se versiona y es la unica configuracion de ejecucion.

- `monitor.interval.seconds`: periodo de muestreo.
- `monitor.process.watchlist`: archivo de procesos.
- `monitor.network.interface.exclude`: filtros de interfaces.
- `rest.port`: puerto HTTP.
- `mqtt.*`: broker, topicos, cadencia, disponibilidad y client ID.

`config/processes.txt` y `config/network-interface-excludes.txt` deben existir. Si falta una clave obligatoria, el proceso aborta. Si MQTT cae, el daemon conserva HTTP y reintenta la conexion.

## Ejecucion

Requiere JDK 17 y Gradle 8:

```text
gradle :daemon-app:run
```
