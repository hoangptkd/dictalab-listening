package com.dictalab.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.flywaydb.core.Flyway;
import com.dictalab.service.TranscriptTokenizer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="dictalab.race-tests",matches="true")
class CapacitySeedTest {
    @Test void fullSentenceFixtureIsIdempotentAndUsesRealWordCardinality() throws Exception {
        String url=System.getProperty("spring.datasource.url");
        String username=System.getProperty("spring.datasource.username"),password=System.getProperty("spring.datasource.password","");
        var source=new DriverManagerDataSource(url,username,password);
        try(var connection=source.getConnection()){
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        var control=new JdbcTemplate(source);
        assertThat(control.queryForObject("SELECT current_database()",String.class)).matches("dictalab_test(?:_[a-f0-9]{32})?");
        String name="dictalab_capacity_"+UUID.randomUUID().toString().replace("-","");
        control.execute("CREATE DATABASE "+name);
        var fixtureSource=new DriverManagerDataSource(url.substring(0,url.lastIndexOf('/')+1)+name,username,password);
        Flyway.configure().dataSource(fixtureSource).locations("classpath:db/migration", "classpath:db/vendor/postgresql").load().migrate();
        var db=new JdbcTemplate(fixtureSource);
        var seed=new CapacitySeed(db,new BCryptPasswordEncoder(4),"Fixture-password-2026",4,3);
        seed.run();
        long users=db.queryForObject("SELECT count(*) FROM app_users WHERE email LIKE 'capacity-%@loadtest.local'",Long.class);
        long sessions=db.queryForObject("SELECT count(*) FROM practice_sessions",Long.class);
        long words=db.queryForObject("SELECT count(*) FROM word_attempts",Long.class);
        assertThat(users).isEqualTo(4);assertThat(sessions).isEqualTo(12);assertThat(words).isPositive();
        assertThat(db.queryForObject("SELECT count(DISTINCT lesson_id) FROM practice_sessions",Integer.class)).isGreaterThan(1);
        assertThat(db.queryForObject("""
                SELECT count(*) FROM practice_sessions p WHERE
                    (SELECT count(*) FROM sentence_attempts a WHERE a.practice_session_id=p.id)<>
                    (SELECT count(*) FROM lesson_sentences s WHERE s.lesson_id=p.lesson_id)
                """,Integer.class)).isZero();
        var tokenizer=new TranscriptTokenizer();
        for(var sentence:db.queryForList("SELECT id,transcript FROM lesson_sentences")){
            Integer expected=tokenizer.words((String)sentence.get("transcript")).size();
            for(Integer actual:db.queryForList("SELECT count(w.id)::integer FROM sentence_attempts a LEFT JOIN word_attempts w ON w.sentence_attempt_id=a.id WHERE a.sentence_id=? GROUP BY a.id",Integer.class,sentence.get("id")))
                assertThat(actual).isEqualTo(expected);
        }
        seed.run();assertThat(db.queryForObject("SELECT count(*) FROM practice_sessions",Long.class)).isEqualTo(sessions);
        assertThat(db.queryForObject("SELECT count(*) FROM word_attempts",Long.class)).isEqualTo(words);
        assertThat(db.queryForObject("SELECT count(*) FROM word_attempt_events",Long.class)).isEqualTo(words);
        assertThatThrownBy(()->new CapacitySeed(new JdbcTemplate(source),new BCryptPasswordEncoder(4),"Fixture-password-2026",4,3).run())
                .isInstanceOf(IllegalStateException.class);
        System.out.println("CAPACITY_FIXTURE_RETAINED="+name);
    }
}
