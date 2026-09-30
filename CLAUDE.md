@AGENTS.md

## Notas específicas para Claude Code

- Antes de tocar el solver, lee `docs/roadmap.md` para saber en qué fase vamos y cuál es su criterio de cierre.
- Después de cambiar el solver, corre `mvn -pl rutea-solver test` y el arnés de benchmarks; pega la tabla de resultados en tu respuesta.
- Antes de agregar una regla normativa, busca el texto oficial. Si no lo encuentras, márcala `PENDIENTE_VERIFICAR` y dilo.
- Para cambios de UI, respeta `docs/diseno.md` y los tokens de `web/src/styles/tokens.css`; revisa el resultado en el navegador antes de dar la tarea por terminada.
- Responde en español; escribe código, identificadores y mensajes de commit en inglés.
