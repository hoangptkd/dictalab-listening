package com.dictalab.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
@SpringBootTest
class DatabaseDeadlineIntegrationTest {
    @DynamicPropertySource static void productionDeadlines(DynamicPropertyRegistry registry) {
        // Load the shipped setting, not a hand-copied test configuration.
        var yaml=new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        String sql=yaml.getObject().getProperty("spring.datasource.hikari.connection-init-sql");
        registry.add("spring.datasource.hikari.connection-init-sql",()->sql);
    }
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;

    @Test void poolAppliesStatementLockAndIdleTransactionDeadlines() {
        assertThat(db.queryForObject("SHOW statement_timeout",String.class)).isEqualTo("15s");
        assertThat(db.queryForObject("SHOW lock_timeout",String.class)).isEqualTo("3s");
        assertThat(db.queryForObject("SHOW idle_in_transaction_session_timeout",String.class)).isEqualTo("1min");
    }

    @Test void blockedWriterTimesOutAndItsEarlierWriteRollsBack() throws Exception {
        db.execute("CREATE TABLE test_deadline_lock(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)");
        db.update("INSERT INTO test_deadline_lock VALUES (1,0),(2,0)");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
                db.queryForObject("SELECT id FROM test_deadline_lock WHERE id=1 FOR UPDATE",Integer.class);
                locked.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue(); long start=System.nanoTime();
            assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{
                db.update("UPDATE test_deadline_lock SET value=1 WHERE id=2"); db.update("UPDATE test_deadline_lock SET value=1 WHERE id=1");
            })).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class)
                    .satisfies(error->assertThat(((java.sql.SQLException)error.getCause()).getSQLState()).isEqualTo("55P03"));
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(2),Duration.ofSeconds(8));
            release.countDown();holder.get(3,TimeUnit.SECONDS);
            assertThat(db.queryForObject("SELECT SUM(value) FROM test_deadline_lock",Integer.class)).isZero();
        } finally {release.countDown();pool.shutdownNow();db.execute("DROP TABLE test_deadline_lock");}
    }

    @Test void slowStatementIsCancelledAndConnectionRemainsUsable() {
        long start=System.nanoTime();
        assertThatThrownBy(()->db.execute("SELECT pg_sleep(30)"))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(13),Duration.ofSeconds(23));
        assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
    }

    @Test void statementTimeoutIs503AndRollsBackTheHttpTransaction() throws Exception {
        String table="test_http_deadline_"+java.util.UUID.randomUUID().toString().replace("-","");
        db.execute("CREATE TABLE "+table+"(value INTEGER NOT NULL)");
        db.update("INSERT INTO "+table+" VALUES (0)");
        var controller=new DeadlineController(db,manager,table);
        try {
            long start=System.nanoTime();
            var result=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new com.dictalab.web.ApiExceptionHandler()).build()
                    .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/test-deadline"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After","3"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value(503))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorId").isString());
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(13),Duration.ofSeconds(24));
            assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("pg_sleep",table);
            assertThat(controller.calls).isEqualTo(1);
            assertThat(db.queryForObject("SELECT value FROM "+table,Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
        } finally {db.execute("DROP TABLE "+table);}
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("database-deadline-fixture")
    static class DeadlineController {
        final JdbcTemplate db;final PlatformTransactionManager manager;final String table;int calls;
        DeadlineController(JdbcTemplate db,PlatformTransactionManager manager,String table){this.db=db;this.manager=manager;this.table=table;}
        @org.springframework.web.bind.annotation.GetMapping("/test-deadline")
        public void deadline(){
            calls++;
            new TransactionTemplate(manager).executeWithoutResult(tx->{
                db.update("UPDATE "+table+" SET value=1");db.execute("SELECT pg_sleep(30)");
            });
        }
    }
}
