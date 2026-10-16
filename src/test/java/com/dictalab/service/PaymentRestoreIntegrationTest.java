package com.dictalab.service;

import com.dictalab.config.*;
import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.support.ReliabilityContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.dictalab.service.PaymentData.*;

@EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
@SpringBootTest(properties={"dictalab.billing.enabled=true","dictalab.billing.public-base-url=https://dictalab.example",
    "dictalab.billing.client-id=restore-client","dictalab.billing.api-key=restore-api","dictalab.billing.checksum-key=restore-checksum"})
class PaymentRestoreIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired BillingService billing;
    @Autowired PaymentOperations operations;
    @Autowired AppUserRepository users;
    @Autowired ObjectMapper json;
    @Autowired BillingProperties billingConfig;
    @Autowired PaymentOperationsProperties config;
    @MockitoBean PayOsClient provider;
    private long pending(long user){
        return db.queryForObject("INSERT INTO payment_orders(user_id,amount_vnd,duration_days,status,payment_link_id,created_at,expires_at) VALUES (?,49000,30,'PENDING',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '15 minutes') RETURNING order_code",Long.class,user,id());
    }
    private JsonNode event(long code){
        String link=db.queryForObject("SELECT payment_link_id FROM payment_orders WHERE order_code=?",String.class,code);
        return json.valueToTree(Map.of("code","00","success",true,"signature","test","data",Map.of("code","00","currency","VND","orderCode",code,"amount",49000,"paymentLinkId",link,"reference","restore-ref-"+code)));
    }
    @Test void actualBackupRestoreRecoversMissingProviderEventAndExpiredInboxWithoutDuplicateEntitlement() throws Exception {
        when(provider.validSignature(any(),anyString())).thenReturn(true);
        var user=users.saveAndFlush(new AppUser(id()+"@restore-payments.test","Restore fixture","unused",Instant.now()));
        long paid=pending(user.getId()),missing=pending(user.getId()),leased=pending(user.getId());
        billing.webhook(event(paid));String job=operations.receive(event(leased));
        db.update("UPDATE payment_inbox SET status='PROCESSING',lease_id=?,locked_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE event_id=?",id(),job);
        var pg=ReliabilityContainers.disposablePostgres();String suffix=id().replace("-",""),name="payment_restore_"+suffix,dump="/tmp/"+name+".dump";
        Instant before=at(one(db,"SELECT premium_until FROM premium_accounts WHERE user_id=?",user.getId()).get("premium_until"));
        boolean created=false;
        try {
            assertThat(pg.execInContainer("pg_dump","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-Fc","-f",dump).getExitCode()).isZero();
            billing.webhook(event(missing));
            db.execute("CREATE DATABASE "+name);created=true;
            assertThat(pg.execInContainer("pg_restore","-U",pg.getUsername(),"-d",name,"--exit-on-error",dump).getExitCode()).isZero();
            var data=new DriverManagerDataSource(pg.getJdbcUrl().replace("/"+pg.getDatabaseName(),"/"+name),pg.getUsername(),pg.getPassword());
            var restored=new JdbcTemplate(data);var clock=Clock.systemUTC();var ledger=new PaymentLedger(restored,clock);
            var recovered=new PaymentOperations(restored,json,billingConfig,config,ledger,clock,new DataSourceTransactionManager(data),new SimpleMeterRegistry());
            assertThat(restored.queryForObject("SELECT COUNT(*) FROM payment_receipts WHERE order_code=?",Long.class,missing)).isZero();
            recovered.process(job);
            var link=one(restored,"SELECT payment_link_id FROM payment_orders WHERE order_code=?",missing).get("payment_link_id");
            var lookup=json.valueToTree(Map.of("orderCode",missing,"amount",49000,"id",link,"status","PAID","amountPaid",49000,"transactions",List.of(Map.of("reference","restore-ref-"+missing,"amount",49000))));
            recovered.lookup(missing,lookup,"RESTORE_GAP_RECONCILIATION");recovered.lookup(missing,lookup,"RESTORE_REPEAT");
            for(long code:new long[]{paid,missing,leased}) {
                assertThat(restored.queryForObject("SELECT COUNT(*) FROM payment_receipts WHERE order_code=?",Long.class,code)).isEqualTo(1);
                assertThat(restored.queryForObject("SELECT COUNT(*) FROM premium_grants WHERE order_code=?",Long.class,code)).isEqualTo(1);
            }
            assertThat(at(one(restored,"SELECT premium_until FROM premium_accounts WHERE user_id=?",user.getId()).get("premium_until"))).isEqualTo(before.plusSeconds(60L*86400));
            assertThat(ledger.report(clock.instant()).get("journalImbalances")).isEqualTo(0L);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM premium_grants WHERE order_code=?",Long.class,leased)).isZero();
            var folder=Path.of(System.getProperty("dictalab.build.directory","target"),"payment-restore");Files.createDirectories(folder);
            Files.writeString(folder.resolve("result.json"),"{\"pgDumpRestore\":true,\"missingProviderEventRecovered\":true,\"expiredInboxRecovered\":true,\"duplicateGrants\":0,\"journalImbalances\":0,\"sourceDatabaseUnchangedByRecovery\":true}");
        } finally {
            if(created)db.execute("DROP DATABASE "+name);
            pg.execInContainer("rm","-f",dump);
        }
    }
}
