package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL partial indexes preserve shared practice and retired content history. */
public class V24__Postgres_uniqueness extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2026100301; }

    @Override public void migrate(Context context) throws Exception {
        if (!context.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try (var sql = context.getConnection().createStatement()) {
            sql.execute("SET LOCAL lock_timeout='5s'");
            sql.execute("SET LOCAL statement_timeout='30s'");
            sql.execute("CREATE UNIQUE INDEX uk_practice_active_solo ON practice_sessions(user_id,lesson_id) WHERE status='IN_PROGRESS' AND room_id IS NULL");
            sql.execute("CREATE UNIQUE INDEX uk_practice_shared_room ON practice_sessions(user_id,room_id) WHERE room_id IS NOT NULL");
            sql.execute("CREATE UNIQUE INDEX uk_listening_active_draft ON listening_units(lesson_id,audio_url) WHERE published=FALSE AND retired=FALSE");
            sql.execute("CREATE UNIQUE INDEX uk_listening_published ON listening_units(lesson_id,audio_url) WHERE published=TRUE AND retired=FALSE");
        }
    }
}
