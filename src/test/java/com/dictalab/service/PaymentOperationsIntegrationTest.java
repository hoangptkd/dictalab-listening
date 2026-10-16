package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.dictalab.service.PaymentData.*;

@SpringBootTest(properties={"dictalab.billing.enabled=true","dictalab.billing.public-base-url=https://dictalab.example",
    "dictalab.billing.client-id=test-client","dictalab.billing.api-key=test-api","dictalab.billing.checksum-key=test-checksum",
    "dictalab.payment-operations.refunds-enabled=true","dictalab.payment-operations.partial-refunds-enabled=true",
    "dictalab.payment-operations.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class PaymentOperationsIntegrationTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2030-01-02T01:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    @TestConfiguration static class Time { @Bean @Primary MutableClock testPaymentClock(){return new MutableClock();} }
    @Autowired MutableClock clock;
    @Autowired BillingService billing;
    @Autowired PaymentOperations operations;
    @Autowired PaymentWorker worker;
    @Autowired PaymentSecurity security;
    @Autowired PaymentRefunds refunds;
    @Autowired PaymentStatements statements;
    @Autowired PaymentReporting reporting;
    @Autowired PremiumAccessService access;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.dictalab.config.BillingProperties billingConfig;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoBean PayOsClient payos;
    @MockitoSpyBean PaymentLedger ledger;
    @BeforeEach void time(){clock.now=Instant.parse("2030-01-02T01:00:00Z");when(payos.validSignature(any(),anyString())).thenReturn(true);}
    private AppUser account(){return users.saveAndFlush(new AppUser(id()+"@payments.test","Finance test",passwords.encode("test-payment-password-1"),clock.instant()));}
    private AppUser operator(){var u=account();for(String p:PaymentSecurity.PERMISSIONS)db.update("INSERT INTO payment_permissions(user_id,permission) VALUES (?,?)",u.getId(),p);return u;}
    private long pending(AppUser u){
        when(payos.create(anyLong(),anyInt(),anyLong())).thenAnswer(i->json.valueToTree(Map.of("orderCode",i.getArgument(0,Long.class),"amount",49000,"currency","VND","paymentLinkId","link"+i.getArgument(0),"checkoutUrl","https://pay.payos.vn/web/link"+i.getArgument(0))));
        return ((Number)billing.create(u.getId()).get("orderCode")).longValue();
    }
    private String ref(long code){return "ref-"+code;}
    private JsonNode event(long code,long amount,String ref){return json.valueToTree(Map.of("code","00","success",true,"signature","test-valid","data",Map.of("code","00","orderCode",code,"amount",amount,"currency","VND","paymentLinkId","link"+code,"reference",ref,"transactionDateTime",clock.instant().toString(),"counterAccountBankId","970422","counterAccountNumber","123456789")));}
    private long paid(AppUser u){long code=pending(u);billing.webhook(event(code,49000,ref(code)));return code;}
    private long count(String table,long code){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE order_code=?",Long.class,code);}
    private JsonNode lookup(long code,String state,List<Map<String,Object>> receipts){return json.valueToTree(Map.of("orderCode",code,"id","link"+code,"amount",49000,"amountPaid",state.equals("PAID")?49000:0,"status",state,"transactions",receipts));}

    @Test void hundredDeliveriesAndConcurrentLookupHaveOneFinancialEffect() throws Exception {
        var u=account();long code=pending(u);var payload=event(code,49000,ref(code));
        var pool=Executors.newFixedThreadPool(8);var jobs=new ArrayList<Future<?>>();var timings=new ConcurrentLinkedQueue<Long>();
        long recoveryStarted=System.nanoTime();
        try{for(int i=0;i<100;i++)jobs.add(pool.submit(()->{long start=System.nanoTime();billing.webhook(payload);timings.add(System.nanoTime()-start);}));
            for(int i=0;i<5;i++)jobs.add(pool.submit(()->operations.lookup(code,lookup(code,"PAID",List.of(Map.of("reference",ref(code),"amount",49000))),"RACE_LOOKUP")));
            for(var job:jobs)job.get(45,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        operations.lookup(code,lookup(code,"PAID",List.of(Map.of("reference",ref(code),"amount",49000))),"concurrent-lookup");
        assertThat(count("payment_receipts",code)).isEqualTo(1);assertThat(count("premium_grants",code)).isEqualTo(1);
        assertThat(count("payment_allocations",code)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_deliveries d JOIN payment_events e ON e.id=d.event_id WHERE e.order_code=?",Long.class,code)).isEqualTo(100);
        assertThat(access.status(u.getId()).get("premiumUntil")).isEqualTo(clock.instant().plusSeconds(30L*86400));
        assertThat(count("payment_outbox",code)).isEqualTo(1);
        var sorted=timings.stream().sorted().toList();
        var evidence=java.nio.file.Path.of(System.getProperty("dictalab.build.directory","target"),"payment-concurrency.json");java.nio.file.Files.createDirectories(evidence.getParent());
        java.nio.file.Files.writeString(evidence,json.writeValueAsString(Map.of("fixture","synthetic signed-data mock; service calls, no HTTP latency claim","callbacks",100,"parallelism",8,"concurrentLookups",5,"receipts",1,"grants",1,"callbackServiceP95Ms",sorted.get(94)/1_000_000.0,"batchMs",(System.nanoTime()-recoveryStarted)/1_000_000.0)));
    }
    @Test void ackedInboxRecoversExpiredLeaseAfterRestartAndTransactionRollsBackPartialPosting(){
        var u=account();long code=pending(u);String e=operations.receive(event(code,49000,ref(code)));
        assertThat(count("premium_grants",code)).isZero();
        db.update("UPDATE payment_inbox SET status='PROCESSING',lease_id=?,locked_until=? WHERE event_id=?",id(),ts(clock.instant().minusSeconds(1)),e);
        doThrow(new IllegalStateException("simulated crash before grant")).when(ledger).allocate(anyString(),eq(code),anyLong(),any());
        operations.process(e);assertThat(count("payment_receipts",code)).isZero();assertThat(count("ledger_journals",code)).isZero();
        doCallRealMethod().when(ledger).allocate(anyString(),eq(code),anyLong(),any());clock.advance(120);operations.process(e);
        assertThat(count("premium_grants",code)).isEqualTo(1);assertThat(db.queryForObject("SELECT status FROM payment_inbox WHERE event_id=?",String.class,e)).isEqualTo("PROCESSED");
    }
    @Test void extraPartialOverpaidAndUnmatchedMoneyRemainVisibleWithoutExtraEntitlement(){
        var u=account();long code=paid(u);Instant until=(Instant)access.status(u.getId()).get("premiumUntil");
        billing.webhook(event(code,5000,"extra-"+code));billing.webhook(event(code,60000,"over-"+code));
        billing.webhook(event(8_000_000_000L+code,49000,"unknown-"+code));
        assertThat(count("payment_receipts",code)).isEqualTo(3);assertThat(count("premium_grants",code)).isEqualTo(1);
        assertThat(access.status(u.getId()).get("premiumUntil")).isEqualTo(until);
        assertThat(count("reconciliation_cases",code)).isGreaterThanOrEqualTo(2);
        assertThat(count("payment_receipts",8_000_000_000L+code)).isEqualTo(1);
    }
    @Test void duplicateReferenceWithChangedAmountIsQuarantinedAndCanceledDoesNotBecomeExpired(){
        var u=account();long code=paid(u);billing.webhook(event(code,49001,ref(code)));
        assertThat(count("payment_receipts",code)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reconciliation_cases WHERE order_code=? AND kind='REFERENCE_CONFLICT'",Long.class,code)).isEqualTo(1);
        long canceled=pending(u);db.update("UPDATE payment_orders SET status='CANCELLED',expires_at=? WHERE order_code=?",ts(clock.instant().minusSeconds(1)),canceled);
        assertThat(billing.orders(u.getId()).stream().filter(o->((Number)o.get("orderCode")).longValue()==canceled).findFirst().orElseThrow().get("status")).isEqualTo("CANCELLED");
    }
    @Test void idempotencyConflictAndUncertainCreationNeverRepeatProviderCreate(){
        var u=account();when(payos.create(anyLong(),anyInt(),anyLong())).thenThrow(PayOsClient.unavailable());
        String key=id();assertThatThrownBy(()->billing.create(u.getId(),key,"{\"plan\":1}")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->billing.create(u.getId(),key,"{\"plan\":2}")).hasMessageContaining("PAYMENT_IDEMPOTENCY_CONFLICT");
        clock.advance(3600);assertThatThrownBy(()->billing.create(u.getId(),id(),"{}")).hasMessageContaining("PAYMENT_CREATE_OUTCOME_UNKNOWN");
        verify(payos,times(1)).create(anyLong(),anyInt(),anyLong());
    }
    @Test void reconciliationFetchesPaidOrdersAndRestoresOnlyTheGrantProjection(){
        var u=account();long code=paid(u);Instant original=(Instant)access.status(u.getId()).get("premiumUntil");db.update("DELETE FROM premium_accounts WHERE user_id=?",u.getId());
        String job=worker.request(code,u.getId(),"Investigate missing projection");
        assertThat(worker.request(code,u.getId(),"Retry same request")).isEqualTo(job);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reconciliation_runs WHERE order_code=? AND status='QUEUED'",Long.class,code)).isEqualTo(1);
        when(payos.lookup(code)).thenReturn(lookup(code,"PAID",List.of(Map.of("reference",ref(code),"amount",49000))));
        worker.reconcile(code,"TEST");verify(payos).lookup(code);
        assertThat(db.queryForObject("SELECT status FROM reconciliation_runs WHERE id=?",String.class,job)).isEqualTo("SUCCESS");
        assertThat(access.status(u.getId()).get("premiumUntil")).isEqualTo(original);assertThat(count("premium_grants",code)).isEqualTo(1);
    }
    @Test void exhaustedFinalInboxLeaseCreatesOneCaseAndDoesNotGrant(){
        var u=account();long code=pending(u);String event=operations.receive(event(code,49000,ref(code)));
        db.update("UPDATE payment_inbox SET status='PROCESSING',attempts=8,lease_id=?,locked_until=? WHERE event_id=?",id(),ts(clock.instant().minusSeconds(1)),event);
        var config=new com.dictalab.config.PaymentOperationsProperties(true,true,true,false,false,false,"","","MANUAL_REVIEW_V1");
        var dispatcher=new PaymentWorker(db,operations,payos,billingConfig,config,clock,manager,metrics);
        dispatcher.tick();dispatcher.tick();
        assertThat(db.queryForObject("SELECT status FROM payment_inbox WHERE event_id=?",String.class,event)).isEqualTo("QUARANTINED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reconciliation_cases WHERE case_key=?",Long.class,"inbox:"+event)).isEqualTo(1);
        assertThat(count("premium_grants",code)).isZero();
    }
    @Test void missingWebhookRecoveryRequiresReferencesAndLatePaymentGetsFullDuration(){
        var u=account();long code=pending(u);db.update("UPDATE payment_orders SET status='EXPIRED' WHERE order_code=?",code);clock.advance(4000);
        when(payos.lookup(code)).thenReturn(lookup(code,"PAID",List.of()));worker.reconcile(code,"TEST");assertThat(count("premium_grants",code)).isZero();
        when(payos.lookup(code)).thenReturn(lookup(code,"PAID",List.of(Map.of("reference",ref(code),"amount",49000))));worker.reconcile(code,"TEST");
        assertThat(access.status(u.getId()).get("premiumUntil")).isEqualTo(clock.instant().plusSeconds(30L*86400));
    }
    @Test void legacyBackfillIsRepeatablePreservesExpiryAndSendsNoNewNotice(){
        var u=account();long code=pending(u);Instant end=clock.instant().plusSeconds(18L*86400);
        db.update("UPDATE payment_orders SET status='PAID',paid_at=?,payment_reference=? WHERE order_code=?",ts(clock.instant()),ref(code),code);
        db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",u.getId(),ts(end));operations.backfill();operations.backfill();
        assertThat(access.status(u.getId()).get("premiumUntil")).isEqualTo(end);assertThat(count("premium_grants",code)).isEqualTo(1);
        assertThat(count("payment_receipts",code)).isEqualTo(1);assertThat(count("payment_outbox",code)).isZero();
    }
    @Test void statementsArePreviewedDeduplicatedAcrossOverlappingFilesAndExcludePersonalTransactions(){
        var u=account();long code=paid(u);var actor=operator();String csv="reference,amountVnd,direction,date,scope\n"+ref(code)+",49000,IN,2030-01-02,DICTALAB\npersonal,99,IN,2030-01-02,PERSONAL\n";
        var p=statements.preview(actor.getId(),csv,"synthetic fixture");assertThat(db.queryForObject("SELECT bank_verified FROM payment_receipts WHERE order_code=?",Boolean.class,code)).isFalse();
        statements.confirm(actor.getId(),(String)p.get("id"),(String)p.get("file_hash"),"verified synthetic source");
        var p2=statements.preview(actor.getId(),csv+"personal2,10,IN,2030-01-02,PERSONAL\n","synthetic overlap");statements.confirm(actor.getId(),(String)p2.get("id"),(String)p2.get("file_hash"),"verified overlap");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ledger_journals WHERE order_code=? AND kind='BANK_MATCH'",Long.class,code)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_evidence WHERE reference='personal'",Long.class)).isZero();
        assertThat(statements.preview(actor.getId(),csv,"same file").get("id")).isEqualTo(p.get("id"));
    }
    @Test void permissionsAreIndependentFromContentAdminAndRevocationTakesEffectAndCsrfRemains() throws Exception {
        var u=account();u.promoteToAdmin(clock.instant());users.saveAndFlush(u);
        mvc.perform(get("/api/admin/payments/orders").with(user(DictaLabPrincipal.from(u)))).andExpect(status().isForbidden());
        db.update("INSERT INTO payment_permissions(user_id,permission) VALUES (?,'PAYMENT_VIEW')",u.getId());
        mvc.perform(get("/api/admin/payments/orders").with(user(DictaLabPrincipal.from(u)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/admin/payments/orders/100000/reconcile").with(user(DictaLabPrincipal.from(u))).contentType("application/json").content("{\"reason\":\"check\"}")).andExpect(status().isForbidden());
        db.update("DELETE FROM payment_permissions WHERE user_id=?",u.getId());mvc.perform(get("/api/admin/payments/orders").with(user(DictaLabPrincipal.from(u)))).andExpect(status().isForbidden());
        var op=operator();mvc.perform(get("/api/admin/users").with(user(DictaLabPrincipal.from(op)))).andExpect(status().isForbidden());
    }
    private byte[] decode(String input){int bits=0,buffer=0;var out=new java.io.ByteArrayOutputStream();for(char c:input.toCharArray()){buffer=(buffer<<5)|"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);bits+=5;if(bits>=8){bits-=8;out.write((buffer>>>bits)&255);}}return out.toByteArray();}
    private String token(AppUser actor,String binding,Map<Long,byte[]> secrets)throws Exception{
        byte[] secret=secrets.get(actor.getId());
        if(secret==null){var enrolled=security.beginTotp(actor.getId(),"test-payment-password-1");secret=decode((String)enrolled.get("secret"));secrets.put(actor.getId(),secret);
            security.enableTotp(actor.getId(),new TimeBasedOneTimePasswordGenerator().generateOneTimePasswordString(new SecretKeySpec(secret,"HmacSHA1"),clock.instant()));}
        clock.advance(31);return security.challenge(actor.getId(),binding,new TimeBasedOneTimePasswordGenerator().generateOneTimePasswordString(new SecretKeySpec(secret,"HmacSHA1"),clock.instant()));
    }
    @Test void stepUpIsBoundToVersionAndCannotBeReplayed() throws Exception {
        var actor=operator();var secrets=new HashMap<Long,byte[]>();String binding=hash("transaction1"),challenge=token(actor,binding,secrets);
        var transaction=new TransactionTemplate(manager);
        assertThatThrownBy(()->transaction.executeWithoutResult(s->security.consume(actor.getId(),challenge,hash("different")))).hasMessageContaining("PAYMENT_CHALLENGE_EXPIRED_OR_REPLAYED");
        transaction.executeWithoutResult(s->security.consume(actor.getId(),challenge,binding));
        assertThatThrownBy(()->transaction.executeWithoutResult(s->security.consume(actor.getId(),challenge,binding))).hasMessageContaining("PAYMENT_CHALLENGE_EXPIRED_OR_REPLAYED");
    }
    @Test void refundRequiresIndependentApprovalsReservesFundsAndOnlyConfirmsWithBankEvidence() throws Exception {
        var maker=operator();long code=paid(maker),next=paid(maker);var checker=operator();var secrets=new HashMap<Long,byte[]>();
        String csv="reference,amountVnd,direction,date,scope\n"+ref(code)+",49000,IN,2030-01-02,DICTALAB\nrefund-"+code+",49000,OUT,2030-01-02,DICTALAB\n";
        var imported=statements.preview(maker.getId(),csv,"synthetic bank evidence");statements.confirm(maker.getId(),(String)imported.get("id"),(String)imported.get("file_hash"),"manual bank fixture checked");
        var request=refunds.request(maker.getId(),code,49000,"synthetic full refund",hash("970422|123456789"),"MB ••••6789",id(),true);String refund=(String)request.get("id");
        assertThatThrownBy(()->refunds.approve(maker.getId(),refund,0,"unused","self approval")).hasMessageContaining("PAYMENT_REFUND_SELF_APPROVAL_FORBIDDEN");
        String approval=token(checker,(String)request.get("approveBinding"),secrets);var approved=refunds.approve(checker.getId(),refund,0,approval,"independent review");
        assertThatThrownBy(()->refunds.request(maker.getId(),code,1,"would exceed held funds",hash("970422|123456789"),"masked",id(),true)).hasMessageContaining("PAYMENT_REFUND_EXCEEDS_VERIFIED_FUNDS");
        String line=db.queryForObject("SELECT line_key FROM bank_evidence WHERE reference=?",String.class,"refund-"+code);
        String execution=token(maker,(String)approved.get("executeBinding"),secrets);
        var unknown=refunds.executionEvidence(maker.getId(),refund,1,line,hash("970422|123456789"),execution,"manual transfer evidence uploaded");
        assertThat(unknown.get("status")).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->refunds.executionEvidence(maker.getId(),refund,2,line,hash("970422|123456789"),execution,"never send again")).hasMessageContaining("PAYMENT_REFUND_APPROVAL_EXPIRED");
        String confirm=token(checker,(String)unknown.get("confirmBinding"),secrets);var done=refunds.confirm(checker.getId(),refund,2,confirm,"independently verified outgoing bank line");
        assertThat(done.get("status")).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM premium_adjustments WHERE refund_id=?",Long.class,refund)).isEqualTo(1);
        assertThat((Instant)access.status(maker.getId()).get("premiumUntil")).isBetween(clock.instant().plusSeconds(29L*86400),clock.instant().plusSeconds(31L*86400));
        assertThat(ledger.report(clock.instant()).get("journalImbalances")).isEqualTo(0L);
    }
    @Test void revenueRoundingClosesExactlyAndSnapshotsAreRepeatable(){
        var u=account();long code=paid(u);var actor=operator();clock.advance(31L*86400);
        var first=reporting.close(actor.getId(),LocalDate.of(2030,2,1),"synthetic period close",null);
        var second=reporting.close(actor.getId(),LocalDate.of(2030,2,1),"repeat close",null);
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(db.queryForObject("SELECT recognized_vnd FROM premium_grants WHERE order_code=?",Long.class,code)).isEqualTo(49000L);
        assertThat(ledger.report(clock.instant()).get("journalImbalances")).isEqualTo(0L);
    }
    @Test void postgresRejectsDirectUnbalancedPostingAndMutationOfPostedEvidence() throws Exception {
        try(var connection=db.getDataSource().getConnection()){Assumptions.assumeTrue(connection.getMetaData().getDatabaseProductName().contains("PostgreSQL"));}
        var user=account();long code=paid(user);String journal=id();
        db.update("INSERT INTO ledger_journals(id,event_key,kind,currency,status,effective_at,recorded_at) VALUES (?,?,'TEST','VND','DRAFT',?,?)",journal,"test:"+journal,ts(clock.instant()),ts(clock.instant()));
        db.update("INSERT INTO ledger_entries(journal_id,account_code,debit_vnd,credit_vnd) VALUES (?,'BANK_SCOPED',1,0)",journal);
        assertThatThrownBy(()->db.update("UPDATE ledger_journals SET status='POSTED' WHERE id=?",journal)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        String posted=db.queryForObject("SELECT id FROM ledger_journals WHERE order_code=? AND status='POSTED' LIMIT 1",String.class,code);
        assertThatThrownBy(()->db.update("UPDATE ledger_entries SET debit_vnd=2 WHERE journal_id=? AND debit_vnd>0",posted)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("DELETE FROM ledger_journals WHERE id=?",posted)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE payment_events SET payload='{}' WHERE order_code=?",code)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private void bankVerified(AppUser actor,long code){
        var imported=statements.preview(actor.getId(),"reference,amountVnd,direction,date,scope\n"+ref(code)+",49000,IN,2030-01-02,DICTALAB\n","synthetic independent bank fixture");
        statements.confirm(actor.getId(),(String)imported.get("id"),(String)imported.get("file_hash"),"verified synthetic source");
    }
    private String fixtureChallenge(AppUser actor,String binding){
        String id=id();db.update("INSERT INTO payment_challenges(id,actor_id,binding_hash,expires_at) VALUES (?,?,?,?)",id,actor.getId(),binding,ts(clock.instant().plusSeconds(120)));return id;
    }
    @Test void hundredConcurrentRefundApprovalsCannotOverReserveAndCloseCannotConsumeReservedFunds() throws Exception {
        var maker=operator();var checker=operator();long code=paid(maker);bankVerified(maker,code);
        var requests=new ArrayList<Map<String,Object>>();
        for(int i=0;i<100;i++)requests.add(refunds.request(maker.getId(),code,49000,"concurrent reservation fixture",hash("970422|123456789"),"MB ••••6789",id(),true));
        var challenges=new HashMap<String,String>();for(var r:requests)challenges.put((String)r.get("id"),fixtureChallenge(checker,(String)r.get("approveBinding")));
        var pool=Executors.newFixedThreadPool(8);var accepted=new java.util.concurrent.atomic.AtomicInteger();var futures=new ArrayList<Future<?>>();
        try{for(var r:requests)futures.add(pool.submit(()->{
            try{refunds.approve(checker.getId(),(String)r.get("id"),0,challenges.get(r.get("id")),"concurrent independent approval");accepted.incrementAndGet();}
            catch(org.springframework.web.server.ResponseStatusException expected){assertThat(expected.getReason()).isEqualTo("PAYMENT_REFUND_EXCEEDS_VERIFIED_FUNDS");}
        }));for(var future:futures)future.get(60,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(accepted).hasValue(1);
        clock.advance(32L*86400);reporting.close(checker.getId(),LocalDate.of(2030,2,2),"reserved funds cannot be recognized",null);
        assertThat(db.queryForObject("SELECT SUM(e.credit_vnd-e.debit_vnd) FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE j.order_code=? AND e.account_code='DEFERRED_SERVICE'",Long.class,code)).isZero();
    }
    @Test void cancellationReleasesOnlyUnexecutedReservationAndUnknownCanAttachEvidence() throws Exception {
        var maker=operator();var checker=operator();long code=paid(maker);bankVerified(maker,code);var secrets=new HashMap<Long,byte[]>();
        var r=refunds.request(maker.getId(),code,49000,"cancel before execution",hash("970422|123456789"),"MB ••••6789",id(),true);String id=(String)r.get("id");
        r=refunds.approve(checker.getId(),id,0,token(checker,(String)r.get("approveBinding"),secrets),"reviewed");
        r=refunds.cancelApproval(checker.getId(),id,1,token(checker,(String)r.get("cancelBinding"),secrets),"no execution performed");
        assertThat(r.get("status")).isEqualTo("CANCELLED");assertThat(refunds.preview(maker.getId(),code,49000,true).get("refundableVnd")).isEqualTo(49000L);
        r=refunds.request(maker.getId(),code,49000,"unknown execution fixture",hash("970422|123456789"),"MB ••••6789",id(),true);id=(String)r.get("id");
        r=refunds.approve(checker.getId(),id,0,token(checker,(String)r.get("approveBinding"),secrets),"reviewed again");
        r=refunds.executionEvidence(maker.getId(),id,1,null,hash("970422|123456789"),token(maker,(String)r.get("executeBinding"),secrets),"bank outcome pending");
        String refundId=id;
        assertThatThrownBy(()->refunds.cancelApproval(checker.getId(),refundId,2,"unused","cannot release unknown")).hasMessageContaining("PAYMENT_REFUND_STATE_INVALID");
        var p=statements.preview(maker.getId(),"reference,amountVnd,direction,date,scope\nunknown-out-"+code+",49000,OUT,2030-01-02,DICTALAB\n","later authoritative bank fixture");statements.confirm(maker.getId(),(String)p.get("id"),(String)p.get("file_hash"),"verified later statement");
        String line=db.queryForObject("SELECT line_key FROM bank_evidence WHERE reference=?",String.class,"unknown-out-"+code);
        r=refunds.executionEvidence(maker.getId(),id,2,line,hash("970422|123456789"),token(maker,(String)r.get("executeBinding"),secrets),"attach only; no new transfer");
        assertThat(r.get("status")).isEqualTo("UNKNOWN");assertThat(r.get("evidence_line_key")).isEqualTo(line);
        r=refunds.confirm(checker.getId(),id,3,token(checker,(String)r.get("confirmBinding"),secrets),"independent later evidence");assertThat(r.get("status")).isEqualTo("SUCCEEDED");
    }
    @Test void moneyAndCsvBoundariesRejectFractionsOverflowAndFormulaInjection() throws Exception {
        for(String bad:List.of("0","-1","1.5","1000000000001","9223372036854775808"))assertThatThrownBy(()->money(json.readTree(bad))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(PaymentStatements.csvCell("=HYPERLINK(\"bad\")")).startsWith("\"'");
        assertThatThrownBy(()->PaymentStatements.parse("a,\"unterminated")).hasMessageContaining("PAYMENT_CSV_INVALID");
        var u=account();long code=paid(u);
        mvc.perform(post("/api/billing/orders/"+code+"/refund-requests").with(user(DictaLabPrincipal.from(u))).with(csrf()).header("Idempotency-Key",id()).contentType("application/json")
                .content("{\"amountVnd\":1.5,\"reason\":\"fraction\",\"recipientFingerprint\":\""+hash("test")+"\",\"recipientMask\":\"masked\"}")).andExpect(status().isBadRequest());
    }
    @Test void reviewedOverpaymentAllocatesOnlyPriceAndRetainsSurplusLiability() throws Exception {
        var maker=operator();long code=pending(maker);billing.webhook(event(code,60000,ref(code)));
        var p=statements.preview(maker.getId(),"reference,amountVnd,direction,date,scope\n"+ref(code)+",60000,IN,2030-01-02,DICTALAB\n","synthetic overpayment");statements.confirm(maker.getId(),(String)p.get("id"),(String)p.get("file_hash"),"verified overpayment");
        String receipt=db.queryForObject("SELECT id FROM payment_receipts WHERE order_code=?",String.class,code);
        long version=db.queryForObject("SELECT edit_version FROM payment_orders WHERE order_code=?",Long.class,code);
        var result=operations.allocateReviewed(maker.getId(),code,receipt,version,"verified intended order",fixtureChallenge(maker,hash("ALLOCATE|"+receipt+"|"+code+"|"+version+"|49000")),security);
        assertThat(result.get("surplusVnd")).isEqualTo(11000L);assertThat(count("premium_grants",code)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT amount_vnd FROM payment_allocations WHERE order_code=?",Long.class,code)).isEqualTo(49000L);
    }
    @Test void accountDeletionDetachesEntitlementButKeepsFinancialMakerAndEvidence() throws Exception {
        var maker=operator();long code=paid(maker);
        var r=refunds.request(maker.getId(),code,49000,"deleted owner fixture",hash("970422|123456789"),"MB ••••6789",id(),true);
        var admin=account();admin.promoteToAdmin(clock.instant());users.saveAndFlush(admin);
        var ticket=mvc.perform(post("/api/support/tickets").with(user(DictaLabPrincipal.from(maker))).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("category","ACCOUNT_DELETE","subject","Delete fixture account","description","Delete synthetic learning data"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/admin/support/tickets/"+json.readTree(ticket).path("id").asLong()+"/delete-account").with(user(DictaLabPrincipal.from(admin))).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email",maker.getEmail())))).andExpect(status().isOk());
        assertThat(one(db,"SELECT user_id FROM payment_orders WHERE order_code=?",code).get("user_id")).isNull();
        assertThat(one(db,"SELECT user_id FROM premium_grants WHERE order_code=?",code).get("user_id")).isNull();
        assertThat(one(db,"SELECT requested_by FROM refund_requests WHERE id=?",r.get("id")).get("requested_by")).isEqualTo(maker.getId());
        assertThat(count("payment_receipts",code)).isEqualTo(1);assertThat(security.permissions(maker.getId())).isEmpty();
    }
    @Test void dailyCloseReopeningRequiresAnotherActorAndCreatesANewImmutableVersion() throws Exception {
        var maker=operator();var checker=operator();clock.advance(86400);
        var first=reporting.close(maker.getId(),LocalDate.of(2030,1,2),"day boundary fixture",null);
        String reopen=reporting.requestReopen(maker.getId(),(String)first.get("id"),"late evidence correction");
        assertThatThrownBy(()->reporting.approveReopen(maker.getId(),reopen,"unused","self review")).hasMessageContaining("PAYMENT_CLOSE_INDEPENDENT_CHECKER_REQUIRED");
        String binding=hash("REOPEN|"+reopen+"|"+first.get("id")+"|late evidence correction");
        reporting.approveReopen(checker.getId(),reopen,fixtureChallenge(checker,binding),"independent review");
        var second=reporting.close(checker.getId(),LocalDate.of(2030,1,2),"new close version",reopen);
        assertThat(number(second,"version")).isEqualTo(number(first,"version")+1);
        assertThat(one(db,"SELECT digest FROM payment_daily_closes WHERE id=?",first.get("id")).get("digest")).isEqualTo(first.get("digest"));
        assertThatThrownBy(()->reporting.close(checker.getId(),LocalDate.of(2030,1,2),"cannot reuse old approval",reopen)).hasMessageContaining("PAYMENT_RESOURCE_NOT_FOUND");
    }
}
