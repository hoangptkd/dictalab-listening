package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Bounded recovery reservations; existing usage history remains unchanged. */
public class V54__Scenario_recovery_call_envelope extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2026111601; }

    @Override public void migrate(Context context) throws Exception {
        String product=context.getConnection().getMetaData().getDatabaseProductName();
        if (!product.equals("PostgreSQL") && !product.equals("H2"))
            throw new IllegalStateException("Unsupported scenario budget database");
        try (var sql=context.getConnection().createStatement()) {
            if (product.equals("PostgreSQL")) {
                sql.execute("SET LOCAL lock_timeout='5s'");
                sql.execute("SET LOCAL statement_timeout='30s'");
            }
            sql.execute("ALTER TABLE scenario_operation_budget ADD CONSTRAINT ck_scenario_recovery_calls CHECK (calls_reserved BETWEEN 1 AND 17)");
            sql.execute("ALTER TABLE scenario_operation_budget DROP CONSTRAINT ck_scenario_calls_envelope");
        }
    }
}
