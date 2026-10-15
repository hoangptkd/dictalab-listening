package com.dictalab.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** PostgreSQL-only DDL acceptance; H2 does not certify transactional DDL or partial indexes. */
class MigrationUpgradeTest {
    @Test void upgradeRejectsInvalidLegacyRowsWithoutDeletingThemThenAcceptsValidData() throws Exception {
        String url=System.getProperty("spring.datasource.url",System.getenv("TEST_DB_URL"));
        assumeTrue(url!=null && url.startsWith("jdbc:postgresql:"),"Requires isolated PostgreSQL test database");
        var source=new DriverManagerDataSource(url,
                System.getProperty("spring.datasource.username",System.getenv("TEST_DB_USERNAME")),
                System.getProperty("spring.datasource.password",System.getenv("TEST_DB_PASSWORD")));
        String schema="migration_test_"+UUID.randomUUID().toString().replace("-","");
        var db=new JdbcTemplate(source);
        String database=db.queryForObject("SELECT current_database()",String.class);
        assertThat(database).matches("dictalab_test(?:_[a-f0-9]{32})?");
        var old=Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/vendor/postgresql").schemas(schema).defaultSchema(schema).target("22").load();
        var upgrade=Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/vendor/postgresql").schemas(schema).defaultSchema(schema).load();
        try{
            old.migrate();
            db.update("INSERT INTO "+schema+".app_users(email,display_name,password_hash,created_at,updated_at) VALUES('INVALID@TEST.LOCAL','fixture','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            assertThatThrownBy(upgrade::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            assertThat(db.queryForObject("SELECT email FROM "+schema+".app_users WHERE email='INVALID@TEST.LOCAL'",String.class)).isEqualTo("INVALID@TEST.LOCAL");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='email_outbox'",Integer.class,schema)).isZero();
            assertThat(db.queryForObject("SELECT MAX(version::int) FROM "+schema+".flyway_schema_history WHERE success=TRUE",Integer.class)).isEqualTo(22);
            // Deliberate correction is test-fixture-only; deployment must never repair learner rows implicitly.
            db.update("UPDATE "+schema+".app_users SET email=LOWER(email)");
            upgrade.migrate();upgrade.validate();
            long user=db.queryForObject("SELECT id FROM "+schema+".app_users WHERE email='invalid@test.local'",Long.class);
            long lesson=db.queryForObject("SELECT MIN(id) FROM "+schema+".lessons",Long.class);
            String insert="INSERT INTO "+schema+".practice_sessions(id,learner_id,user_id,lesson_id,status,started_at) VALUES(?,'fixture',?,?,'IN_PROGRESS',CURRENT_TIMESTAMP)";
            db.update(insert,UUID.randomUUID().toString(),user,lesson);
            assertThatThrownBy(()->db.update(insert,UUID.randomUUID().toString(),user,lesson))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+schema+".practice_sessions WHERE user_id=?",Integer.class,user)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND NOT i.indisvalid",Integer.class,schema)).isZero();
        }finally{
            // Exact UUID namespace inside a database checked above; never DROP the test/public database.
            if(schema.matches("migration_test_[a-f0-9]{32}")) db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
}
