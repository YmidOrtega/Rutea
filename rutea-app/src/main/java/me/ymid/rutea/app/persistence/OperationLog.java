package me.ymid.rutea.app.persistence;

import java.util.List;
import java.util.Optional;

/** Lo que sobrevive a un reinicio: las órdenes de cada turno y las posiciones que transmitieron los vehículos. */
public interface OperationLog {

    /** @param commands en el orden en que llegaron; {@code lastSeconds}, la hora del último punto transmitido */
    record Shift(long id, List<Command> commands, double lastSeconds) {

        public Shift {
            commands = List.copyOf(commands);
        }
    }

    record Position(String vehicle, double atSeconds, Optional<String> state, double lon, double lat) {
    }

    long newShift();

    Optional<Shift> lastShift();

    void command(long shift, int seq, Command command);

    void positions(long shift, List<Position> positions);
}
