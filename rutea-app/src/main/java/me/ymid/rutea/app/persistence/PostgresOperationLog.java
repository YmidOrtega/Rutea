package me.ymid.rutea.app.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/** Las órdenes como JSON y las posiciones como puntos de PostGIS (SRID 4326, [lon, lat]). */
public final class PostgresOperationLog implements OperationLog {

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final JsonMapper json;

    public PostgresOperationLog(JdbcClient jdbc, JdbcTemplate batch, JsonMapper json) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.json = json;
    }

    @Override
    public long newShift() {
        return jdbc.sql("INSERT INTO shift DEFAULT VALUES RETURNING id").query(Long.class).single();
    }

    @Override
    public Optional<Shift> lastShift() {
        Optional<Long> id = jdbc.sql("SELECT max(id) FROM shift").query(Long.class).optional();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        List<Command> commands = jdbc.sql("SELECT kind, args FROM command WHERE shift_id = ? ORDER BY seq")
                .param(id.get())
                .query((row, n) -> parse(row.getString("kind"), row.getString("args")))
                .list();
        double lastSeconds = jdbc.sql("SELECT coalesce(max(at_seconds), 0) FROM vehicle_position WHERE shift_id = ?")
                .param(id.get()).query(Double.class).single();
        return Optional.of(new Shift(id.get(), commands, lastSeconds));
    }

    @Override
    public void command(long shift, int seq, Command command) {
        jdbc.sql("INSERT INTO command (shift_id, seq, at_seconds, kind, args) VALUES (?, ?, ?, ?, ?::jsonb)")
                .params(shift, seq, command.atSeconds(), kind(command), json.writeValueAsString(command))
                .update();
    }

    @Override
    public void positions(long shift, List<Position> positions) {
        if (positions.isEmpty()) {
            return;
        }
        batch.batchUpdate("INSERT INTO vehicle_position (shift_id, vehicle, at_seconds, state, position)"
                + " VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))", positions, positions.size(),
                (statement, position) -> {
                    statement.setLong(1, shift);
                    statement.setString(2, position.vehicle());
                    statement.setDouble(3, position.atSeconds());
                    statement.setString(4, position.state().orElse(null));
                    statement.setDouble(5, position.lon());
                    statement.setDouble(6, position.lat());
                });
    }

    private static String kind(Command command) {
        return switch (command) {
            case Command.Breakdown breakdown -> "breakdown";
            case Command.Overflow overflow -> "overflow";
            case Command.Closure closure -> "closure";
        };
    }

    private Command parse(String kind, String args) {
        return switch (kind) {
            case "breakdown" -> json.readValue(args, Command.Breakdown.class);
            case "overflow" -> json.readValue(args, Command.Overflow.class);
            case "closure" -> json.readValue(args, Command.Closure.class);
            default -> throw new IllegalStateException("orden desconocida en la base: " + kind);
        };
    }
}
