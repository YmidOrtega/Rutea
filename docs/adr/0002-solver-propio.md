# ADR 0002: Solver CVRP propio

- Estado: aceptada
- Fecha: 2026-09-30

## Contexto

Existen solvers maduros (Timefold, OR-Tools, PyVRP). Usarlos daría buenas rutas rápido, pero el objetivo del proyecto es demostrar la capacidad de diseñar y optimizar el algoritmo.

## Decisión

El solver se escribe desde cero en Java puro: construcción (Clarke-Wright), búsqueda local y ALNS. Timefold se usa solo como punto de comparación en el arnés de benchmarks.

## Consecuencias

- Las métricas del proyecto son el gap frente a las BKS de CVRPLIB y la comparación con Timefold con el mismo tiempo.
- Más trabajo y más riesgo; se mitiga con el arnés desde F0 y con criterios de cierre por fase.
- Las distancias son enteras (`nint`, como CVRPLIB) para que las comparaciones sean exactas.
