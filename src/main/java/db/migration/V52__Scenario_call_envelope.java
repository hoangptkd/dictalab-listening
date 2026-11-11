package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Widen the bounded reservation; retain historical rows and all other constraints. */
public class V52__Scenario_call_envelope extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 2026111101; }

    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        String product=connection.getMetaData().getDatabaseProductName();
        if (!product.equals("PostgreSQL") && !product.equals("H2"))
            throw new IllegalStateException("Unsupported scenario budget database");
        String previous;
        // V49 used an unnamed check; H2 and PostgreSQL assign different names.
        try (var query=connection.prepareStatement("""
                SELECT tc.constraint_name FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog=tc.constraint_catalog
                 AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
                WHERE tc.table_schema=? AND LOWER(tc.table_name)='scenario_operation_budget'
                  AND LOWER(cc.check_clause) LIKE '%calls_reserved%'
                  AND LOWER(cc.check_clause) NOT LIKE '%is not null%'
                """)) {
            query.setString(1,connection.getSchema());
            try (var rows=query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Missing scenario call constraint");
                previous=rows.getString(1);
                if (rows.next()) throw new IllegalStateException("Ambiguous scenario call constraints");
            }
        }
        try (var sql=connection.createStatement()) {
            if (product.equals("PostgreSQL")) {
                sql.execute("SET LOCAL lock_timeout='5s'");
                sql.execute("SET LOCAL statement_timeout='30s'");
            }
            sql.execute("ALTER TABLE scenario_operation_budget ADD CONSTRAINT ck_scenario_calls_envelope CHECK (calls_reserved BETWEEN 1 AND 11)");
            sql.execute("ALTER TABLE scenario_operation_budget DROP CONSTRAINT \""+previous.replace("\"","\"\"")+"\"");
        }
    }
}
