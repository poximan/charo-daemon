# Contratos MQTT Normalizados

Este documento define los contratos de publicador/suscriptor entre:

- charo-daemon (agente por host)
- lechuza-server (server central)
- panelito (app Android)

Objetivo: homogeneizar nombres de tÃ³picos, facilitar enrutamiento por `clientId` y exponer estados con retained/Will para que las vistas se actualicen correctamente.

## Principios

- Todos los tÃ³picos usan minÃºsculas y `/` como separador.
- Identidades en el tÃ³pico: `{clientId}` para hosts, `{hypervisorId}`, `{vmId}` donde corresponda.
- Mensajes de estado usan retained y LWT (`online`/`offline`).
- No existe compatibilidad hacia atrÃ¡s: no se aceptan tÃ³picos legados.
- ConfiguraciÃ³n estricta: cada servicio debe leer exclusivamente su archivo/env configurado. Si faltan claves o el archivo no existe, el servicio debe fallar; no hay valores por defecto.

## Estado del repo hoy

El repo todavia no esta completamente alineado con el esquema objetivo de este documento.

Contrato vigente observado en codigo:

- `charo-daemon` ya publica en `charodaemon/host/{clientId}/metrics` y `charodaemon/host/{clientId}/status`
- `panelito` consume esos topicos directos de `charo-daemon`
- `charito-service` publica `charito/whitelist/instances` como topico auxiliar de reconciliacion
- `lechuza-server` sigue usando para movil los topicos `exemys/estado/*` y `exemys/eventos/email`
- `panelito` dispara pedidos RPC por `app/req/{accion}` y recibe respuesta en topicos de estado ya suscriptos

Regla de refactor:

- este documento define el contrato objetivo de MQTT normalizado
- mientras exista brecha, cada cambio debe indicar si opera sobre contrato vigente o sobre contrato objetivo
- el detalle consolidado de contratos vigentes y migracion vive en [docs/contratos-sistema.md](/c:/HSD/git/infra-monitor/docs/contratos-sistema.md)

## 1. TelemetrÃ­a de hosts (charo-daemon)

- Topico unico y normalizado: `charodaemon/host/{clientId}/metrics`
- Estado del agente: `charodaemon/host/{clientId}/status` â†’ `online|offline` (retained)

Payload: objeto agregado de ventana con campo `latest` (muestra mÃ¡s reciente). Sin cambios de estructura; solo se suma el enrutamiento por tÃ³pico.

ConfiguraciÃ³n en `daemon.properties`:

```
mqtt.topic.template=charodaemon/host/{clientId}/metrics
mqtt.publish.every.http.updates=5
mqtt.availability.enabled=true
# mqtt.availability.topic=charodaemon/host/{clientId}/status
mqtt.retain.availability=true
```

Panelito debe suscribirse a: `charodaemon/host/+/metrics` y `charodaemon/host/+/status`.

## 2. Hypervisor (lechuza-server)

- MÃ©tricas resumen: `lechuza-server/hv/{hypervisorId}/metrics`
- Estado/available: `lechuza-server/hv/{hypervisorId}/status` (retained `online|offline`)
- Opcional VMs: `lechuza-server/hv/{hypervisorId}/vm/{vmId}/metrics`

Panelito se suscribe a `lechuza-server/hv/+/metrics` y `.../status`.

## 3. Eventos y correo (lechuza-server)

- Estado SMTP: `lechuza-server/email/status` â†’ `connected|disconnected` (retained)
- Comando probar email: `lechuza-server/email/cmd/test` (payload: `{ "to": "destino@dominio" }`)
- Resultado probar email: `lechuza-server/email/evt/test` (payload: `{ "ok": true, "error": "..." }`)

Panelito:
- Escucha `lechuza-server/email/status` para pintar estado en la UI.
- Publica en `lechuza-server/email/cmd/test` al pulsar â€œprobar emailâ€.
- Escucha `lechuza-server/email/evt/test` para mostrar el resultado.

## Deprecaciones

- Se eliminan tÃ³picos ad-hoc y cualquier publicacion legada. Solo se admite el esquema normalizado.

## Notas de implementaciÃ³n

- Este repo publica Ãºnicamente en la plantilla normalizada. El server central debe adoptar los tÃ³picos propuestos arriba para hypervisor y correo.





