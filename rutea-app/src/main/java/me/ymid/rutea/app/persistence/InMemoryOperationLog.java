package me.ymid.rutea.app.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Sin base de datos: nada sobrevive a un reinicio. Es lo que usan los tests y {@code pnpm dev}. */
public final class InMemoryOperationLog implements OperationLog {

    private final AtomicLong shifts = new AtomicLong();

    @Override
    public long newShift() {
        return shifts.incrementAndGet();
    }

    @Override
    public Optional<Shift> lastShift() {
        return Optional.empty();
    }

    @Override
    public void command(long shift, int seq, Command command) {
    }

    @Override
    public void positions(long shift, List<Position> positions) {
    }
}
