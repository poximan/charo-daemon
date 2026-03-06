# Charo Daemon WinXP

Implementacion de charo-daemon para Windows XP SP3 (2002), orientada a ejecucion en JRE 1.6.

## Alcance

- Mantiene el contrato funcional de win7/win8 siempre que sea posible.
- Endpoints HTTP: `/identity`, `/metrics`, `/config`, `/config/interval`, `/config/processes`.
- Publicacion MQTT en topicos normalizados:
  - `charodaemon/host/{clientId}/metrics`
  - `charodaemon/host/{clientId}/status`
- Sin soporte SCADA adapter en esta variante.

## Particularidades XP

- Metricas no soportadas por XP se exponen como `no disponible` en texto y `-1` en campos numericos.
- Si MQTT con TLS falla al iniciar, el modulo MQTT se desactiva y queda solo servicio HTTP activo.
- Se conserva la convencion de `instanceId` usada en win7/win8.

## Requisitos

- Build: JDK 8 (toolchain fijo para compilar `-source 1.6 -target 1.6`).
- Runtime: JRE 1.6.
- MQTT client fijado a `org.eclipse.paho.client.mqttv3:1.1.0` para compatibilidad con Java 6.
- Archivo de configuracion obligatorio: `config/daemon.properties`.

## Build y ejecucion manual

- Wrapper fijado a Gradle `6.9.4` en `gradle/wrapper/gradle-wrapper.properties`.
- Para compilar target Java 6: usar `jdk8-gradlew.bat` (toma JDK fijo de `winxp/java8`) y ejecutar `clean build :daemon-app:installDist`.
- El script descarga `gradle-6.9.4-bin.zip` con PowerShell al cache local del wrapper si aun no existe.
- La ejecucion del distribuible en destino se realiza directamente con `daemon-app/bin/daemon-app.bat`.

## Configuracion

Se usan las mismas claves de `daemon.properties` que win7/win8.
No hay defaults silenciosos: faltantes invalidan inicio.
