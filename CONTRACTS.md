# Contratos MQTT Normalizados

Este documento define los contratos de publicador/suscriptor entre:

- charo-daemon (agente por host)
- monimonitor (server central)
- panelito (app Android)

Objetivo: homogeneizar nombres de tópicos, facilitar enrutamiento por `clientId` y exponer estados con retained/Will para que las vistas se actualicen correctamente.

## Principios

- Todos los tópicos usan minúsculas y `/` como separador.
- Identidades en el tópico: `{clientId}` para hosts, `{hypervisorId}`, `{vmId}` donde corresponda.
- Mensajes de estado usan retained y LWT (`online`/`offline`).
- No existe compatibilidad hacia atrás: no se aceptan tópicos legados.
- Configuración estricta: cada servicio debe leer exclusivamente su archivo/env configurado. Si faltan claves o el archivo no existe, el servicio debe fallar; no hay valores por defecto.

## 1. Telemetría de hosts (charo-daemon)

- Topico unico y normalizado: `charodaemon/host/{clientId}/metrics`
- Estado del agente: `charodaemon/host/{clientId}/status` → `online|offline` (retained)

Payload: objeto agregado de ventana con campo `latest` (muestra más reciente). Sin cambios de estructura; solo se suma el enrutamiento por tópico.

Configuración en `daemon.properties`:

```
mqtt.topic.template=charodaemon/host/{clientId}/metrics
mqtt.availability.enabled=true
# mqtt.availability.topic=charodaemon/host/{clientId}/status
mqtt.retain.availability=true
```

Panelito debe suscribirse a: `charodaemon/host/+/metrics` y `charodaemon/host/+/status`.

## 2. Hypervisor (monimonitor)

- Métricas resumen: `monimonitor/hv/{hypervisorId}/metrics`
- Estado/available: `monimonitor/hv/{hypervisorId}/status` (retained `online|offline`)
- Opcional VMs: `monimonitor/hv/{hypervisorId}/vm/{vmId}/metrics`

Panelito se suscribe a `monimonitor/hv/+/metrics` y `.../status`.

## 3. Eventos y correo (monimonitor)

- Estado SMTP: `monimonitor/email/status` → `connected|disconnected` (retained)
- Comando probar email: `monimonitor/email/cmd/test` (payload: `{ "to": "destino@dominio" }`)
- Resultado probar email: `monimonitor/email/evt/test` (payload: `{ "ok": true, "error": "..." }`)

Panelito:
- Escucha `monimonitor/email/status` para pintar estado en la UI.
- Publica en `monimonitor/email/cmd/test` al pulsar “probar email”.
- Escucha `monimonitor/email/evt/test` para mostrar el resultado.

## Deprecaciones

- Se eliminan tópicos ad-hoc y cualquier publicacion legada. Solo se admite el esquema normalizado.

## Notas de implementación

- Este repo publica únicamente en la plantilla normalizada. El server central debe adoptar los tópicos propuestos arriba para hypervisor y correo.
