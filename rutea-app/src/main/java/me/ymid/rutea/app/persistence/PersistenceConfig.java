package me.ymid.rutea.app.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/** Con el perfil {@code postgres} hay base de datos y la operación sobrevive a un reinicio; sin él, vive en memoria. */
@Configuration
public class PersistenceConfig {

    @Bean
    OperationLog operationLog(ObjectProvider<JdbcClient> jdbc, ObjectProvider<JdbcTemplate> batch, JsonMapper json) {
        JdbcClient client = jdbc.getIfAvailable();
        return client == null ? new InMemoryOperationLog()
                : new PostgresOperationLog(client, batch.getObject(), json);
    }
}
