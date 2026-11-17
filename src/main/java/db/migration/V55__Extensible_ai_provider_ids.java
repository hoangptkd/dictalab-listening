package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Forward-only: retain accounts, ciphertext, foreign keys and all quota constraints. */
public class V55__Extensible_ai_provider_ids extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2026111701; }
    @Override public void migrate(Context context) throws Exception {
        var db = context.getConnection();
        String product = db.getMetaData().getDatabaseProductName();
        boolean postgres = product.equals("PostgreSQL");
        if (!postgres && !product.equals("H2")) throw new IllegalStateException("Unsupported registry database");
        String query = postgres
            ? "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='ai_provider_pools'::regclass AND contype='c'"
            : "SELECT c.constraint_name,c.check_clause FROM information_schema.check_constraints c JOIN information_schema.table_constraints t ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name WHERE lower(t.table_name)='ai_provider_pools' AND t.constraint_type='CHECK'";
        var names = new java.util.ArrayList<String>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                String definition = rows.getString(2).toLowerCase(java.util.Locale.ROOT);
                if (definition.contains("platform") && definition.contains("'gemini'")
                        && definition.contains("'deepseek'") && definition.contains("'openai_compatible'")) names.add(rows.getString(1));
            }
        }
        if (names.size() != 1) throw new IllegalStateException("Expected exactly one legacy provider ID constraint");
        String identifier = names.get(0).replace("\"", "\"\"");
        try (var statement = db.createStatement()) {
            if (postgres) {
                statement.execute("SET LOCAL lock_timeout='5s'");
                statement.execute("SET LOCAL statement_timeout='30s'");
            }
            statement.execute("ALTER TABLE ai_provider_pools ADD CONSTRAINT ck_ai_provider_id CHECK ("
                + (postgres ? "platform ~ '^[a-z][a-z0-9_]{0,31}$'" : "REGEXP_LIKE(platform, '^[a-z][a-z0-9_]{0,31}$')") + ")");
            statement.execute("ALTER TABLE ai_provider_pools DROP CONSTRAINT \"" + identifier + "\"");
        }
    }
}
