# ADR 0008: Reloj y posiciones transmitidos por el servidor

- Estado: aceptada
- Fecha: 2026-10-21

## Contexto

R08 (Decreto 1077 de 2015, art. 2.3.2.2.2.3.49) pide al prestador monitorear la recolección: la posición de los vehículos, sus tiempos y movimientos. Hasta ahora cada navegador reproducía el plan con su propio reloj y calculaba dónde iba cada camión. El mapa no mostraba lo que hacían los vehículos, sino lo que decía el plan. Además, dos personas mirando el mismo turno veían horas distintas, y una avería se marcaba con la hora del navegador que la pidió.

## Decisión

1. **Un solo reloj, el del servidor** (`rutea-sim`, `SimulationClock`). Corre a 15, 60 o 240 veces el tiempo real y se detiene al final del turno, que se alarga si una reserva vuelve después. Reproducir, pausar o cambiar la velocidad (`PUT /api/clock`) vale para todos los clientes. Las averías, los desbordes y los cierres sin hora explícita ocurren a la hora de este reloj. Reproducir con el turno terminado empieza un turno nuevo: se borran los eventos y los clientes reciben `reset`.
2. **Telemetría por WebSocket.** Cuatro veces por segundo, el servidor reporta cada vehículo que se movió o cambió de estado: en camino, recogiendo, descargando, averiado o con el turno terminado. Cada reporte lleva lo recorrido desde el anterior, con todos los vértices de la calle y su hora (`Track.between`), no solo el último punto. A 240×, un reporte cubre un minuto de turno, y con solo el último punto la estela cortaría las esquinas.
3. **El mapa dibuja la telemetría, no el plan.** Interpola entre puntos que ya llegaron y dibuja un reporte atrás (250 ms), así que nunca adivina hacia dónde va un camión. Con `prefers-reduced-motion`, se queda en la hora del último reporte, sin animar. Sin API, el mapa sigue reproduciendo el plan estático por su cuenta.
4. **Lo que hace cada vehículo sale de su recorrido vigente.** El plan cambia con los eventos: el averiado se queda donde se averió, las reservas aparecen cuando las despachan y los turnos redibujados por desbordes o cierres reemplazan a los anteriores.

Medido con un cliente aparte durante un minuto a 60× con 15 vehículos en la calle: 4,00 mensajes por segundo, 2,1 KB de media, 3,5 KB en P95 y 4,9 KB como máximo, 8,4 KB/s en total. La instantánea con la que arranca un cliente (`GET /api/telemetry`, 15 min de estela por vehículo) pesa unos 79 KB.

## Consecuencias

- El reloj es compartido: si alguien pausa, pausa para todos. Para una sala de control es lo correcto; para dos personas probando a la vez, no.
- Las posiciones siguen siendo simuladas: salen del recorrido planificado de cada vehículo, no de un GPS. El protocolo ya es el de una flota real: cuando haya GPS, los reportes cambian de origen, no de forma.
- Un vehículo quieto que no cambia de estado no se reporta: en una descarga de 40 min no gasta nada.
