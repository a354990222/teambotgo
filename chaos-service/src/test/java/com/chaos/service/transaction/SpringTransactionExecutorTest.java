package com.chaos.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SpringTransactionExecutor} 单元测试。
 */
class SpringTransactionExecutorTest {

    private JdbcTemplate jdbcTemplate;

    private SpringTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("create table tx_item (id int primary key, name varchar(64))");
        executor = new SpringTransactionExecutor(new TransactionTemplate(new DataSourceTransactionManager(database)));
    }

    @Test
    void shouldCommitSupplierAndRunnable() {
        String value = executor.execute(() -> {
            jdbcTemplate.update("insert into tx_item(id, name) values (1, 'a')");
            return "ok";
        });
        executor.execute(() -> jdbcTemplate.update("insert into tx_item(id, name) values (2, 'b')"));

        assertThat(value).isEqualTo("ok");
        assertThat(jdbcTemplate.queryForObject("select count(*) from tx_item", Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldRollbackOnRuntimeException() {
        assertThatThrownBy(() -> executor.execute(() -> {
            jdbcTemplate.update("insert into tx_item(id, name) values (3, 'c')");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from tx_item", Integer.class)).isZero();
    }
}
