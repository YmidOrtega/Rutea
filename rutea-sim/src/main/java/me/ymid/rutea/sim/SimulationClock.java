package me.ymid.rutea.sim;

import java.util.function.LongSupplier;

/**
 * Reloj de la simulación, en segundos desde el inicio del turno: corre a {@code speed} veces el tiempo real y se
 * detiene solo al llegar al final del turno.
 */
public final class SimulationClock {

    public record State(double seconds, double speed, boolean running, double endSeconds) {
    }

    private final LongSupplier nanos;
    private double baseSeconds;
    private long baseNanos;
    private double speed;
    private boolean running;
    private double endSeconds;

    public SimulationClock(double speed, double endSeconds) {
        this(System::nanoTime, speed, endSeconds);
    }

    SimulationClock(LongSupplier nanos, double speed, double endSeconds) {
        this.nanos = nanos;
        speed(speed);
        end(endSeconds);
    }

    public synchronized double seconds() {
        if (!running) {
            return baseSeconds;
        }
        double seconds = baseSeconds + (nanos.getAsLong() - baseNanos) / 1e9 * speed;
        if (seconds >= endSeconds) {
            baseSeconds = endSeconds;
            running = false;
            return endSeconds;
        }
        return seconds;
    }

    public synchronized State state() {
        double seconds = seconds();
        return new State(seconds, speed, running, endSeconds);
    }

    public synchronized boolean finished() {
        return seconds() >= endSeconds;
    }

    public synchronized void play() {
        if (!running && !finished()) {
            baseNanos = nanos.getAsLong();
            running = true;
        }
    }

    public synchronized void pause() {
        baseSeconds = seconds();
        running = false;
    }

    /** Pausado en {@code seconds}: para retomar un turno guardado donde quedó. */
    public synchronized void seek(double seconds) {
        baseSeconds = Math.min(Math.max(0, seconds), endSeconds);
        running = false;
    }

    public synchronized void restart() {
        baseSeconds = 0;
        baseNanos = nanos.getAsLong();
        running = true;
    }

    public synchronized void speed(double speed) {
        if (!(speed > 0)) {
            throw new IllegalArgumentException("la velocidad debe ser positiva: " + speed);
        }
        baseSeconds = seconds();
        baseNanos = nanos.getAsLong();
        this.speed = speed;
    }

    /** El turno puede alargarse durante la operación: una reserva que sale tarde vuelve después. */
    public synchronized void end(double endSeconds) {
        if (!(endSeconds > 0)) {
            throw new IllegalArgumentException("el turno debe durar algo: " + endSeconds);
        }
        baseSeconds = seconds();
        baseNanos = nanos.getAsLong();
        this.endSeconds = endSeconds;
    }
}
