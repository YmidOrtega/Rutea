package me.ymid.rutea.app.persistence;

/** Una orden de la operación, con lo justo para volver a ejecutarla igual. */
public sealed interface Command permits Command.Breakdown, Command.Overflow, Command.Closure {

    long atSeconds();

    record Breakdown(String truck, long atSeconds) implements Command {
    }

    /** @param cause "reporte" o "sensor"; los que vuelve a despachar una avería salen de repetir la avería */
    record Overflow(String container, long atSeconds, String cause) implements Command {
    }

    /** El trazo, no el clic: el clic se resuelve a la calle más cercana una sola vez. */
    record Closure(int shape, long atSeconds) implements Command {
    }
}
