# ADR 0001: Monolito modular

- Estado: aceptada
- Fecha: 2026-09-30

## Contexto

Rutea tiene piezas con ritmos muy distintos: un solver de optimización que necesita medirse aislado, un grafo vial, un dominio con reglas normativas, una simulación y una API. En Clínica ya se demostró una arquitectura de microservicios; repetirla aquí añadiría red, despliegue y observabilidad sin mejorar las rutas.

## Decisión

Un solo proceso con módulos Maven separados (`solver`, `geo`, `domain`, `sim`, `app`) y dependencias en una sola dirección. El solver no depende de nada.

## Consecuencias

- El solver se prueba y se mide sin Spring ni base de datos.
- Se puede extraer un módulo a un servicio más adelante si la carga lo pide (por ejemplo, el solver detrás de una cola).
- Hay que vigilar que nadie importe `rutea-app` desde otro módulo; Maven lo impide por construcción.
