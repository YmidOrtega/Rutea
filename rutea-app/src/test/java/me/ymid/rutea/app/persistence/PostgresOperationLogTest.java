package me.ymid.rutea.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

/** Contra la misma imagen de PostGIS que levanta compose.yaml; sin Docker, se salta. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresOperationLogTest {

    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    private static JdbcClient jdbc;
    private static PostgresOperationLog log;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(DB.getJdbcUrl());
        source.setUser(DB.getUsername());
        source.setPassword(DB.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        log = new PostgresOperationLog(jdbc, new JdbcTemplate(source), JsonMapper.builder().build());
    }

    @Test
    void theLastShiftOfAnOperationComesBackWithItsCommandsInOrderAndWhereTheClockWas() {
        log.newShift("11-LMVNoche");
        long shift = log.newShift("11-LMVNoche");
        long other = log.newShift("8-MJSDia");
        log.command(shift, 1, new Command.Breakdown("C-03", 600));
        log.command(shift, 2, new Command.Overflow("N0001", 900, "sensor"));
        log.command(shift, 3, new Command.Closure(4_242, 1_200));
        log.positions(shift, List.of(new OperationLog.Position("C-03", 590.5, Optional.empty(), -74.08, 4.73),
                new OperationLog.Position("C-03", 600, Optional.of("BROKEN"), -74.081, 4.731)));

        OperationLog.Shift saved = log.lastShift("11-LMVNoche").orElseThrow();

        assertThat(saved.id()).isEqualTo(shift);
        assertThat(log.lastShift("8-MJSDia").orElseThrow().id()).isEqualTo(other);
        assertThat(log.lastShift("3-LMVDia")).isEmpty();
        assertThat(saved.commands()).containsExactly(new Command.Breakdown("C-03", 600),
                new Command.Overflow("N0001", 900, "sensor"), new Command.Closure(4_242, 1_200));
        assertThat(saved.lastSeconds()).isEqualTo(600);
    }

    @Test
    void positionsArePostgisPointsThatSqlCanMeasure() {
        long shift = log.newShift("11-LMVNoche");
        log.positions(shift, List.of(new OperationLog.Position("C-01", 0, Optional.empty(), -74.1, 4.7),
                new OperationLog.Position("C-01", 60, Optional.of("DRIVING"), -74.1, 4.71)));

        double meters = jdbc.sql("""
                SELECT ST_Length(ST_MakeLine(position ORDER BY at_seconds)::geography)
                FROM vehicle_position WHERE shift_id = ? AND vehicle = 'C-01'""").param(shift)
                .query(Double.class).single();

        // 0,01° de latitud son unos 1.106 m a esta latitud.
        assertThat(meters).isBetween(1_100.0, 1_112.0);
    }
}
