package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"dictalab.billing.enabled=true", "dictalab.billing.public-base-url=https://dictalab.example",
        "dictalab.billing.client-id=test-client", "dictalab.billing.api-key=test-api", "dictalab.billing.checksum-key=test-checksum"})
@AutoConfigureMockMvc
class BillingIntegrationTest {
    @Autowired BillingService billing;
    @Autowired PremiumAccessService access;
    @Autowired AiUsageBudget budget;
    @Autowired PracticeService practice;
    @Autowired ListeningComprehensionService listening;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PayOsClient payos;

    private AppUser learner() { return users.save(new AppUser(UUID.randomUUID()+"@billing.test","Learner","unused-test-hash",Instant.now())); }
    private long pending(AppUser user, int amount, String link) {
        var keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement=connection.prepareStatement("""
                    INSERT INTO payment_orders(user_id,amount_vnd,duration_days,status,payment_link_id,created_at,expires_at)
                    VALUES (?,?,30,'PENDING',?,?,?)
                    """,new String[]{"order_code"});
            statement.setLong(1,user.getId()); statement.setInt(2,amount); statement.setString(3,link);
            statement.setTimestamp(4,Timestamp.from(Instant.now())); statement.setTimestamp(5,Timestamp.from(Instant.now().plusSeconds(900)));
            return statement;
        },keys);
        return keys.getKey().longValue();
    }
    private JsonNode event(long code,int amount,String link,String reference) {
        return json.valueToTree(Map.of("code","00","success",true,"signature","valid-test-signature","data",
                Map.of("code","00","orderCode",code,"amount",amount,"currency","VND","paymentLinkId",link,"reference",reference)));
    }
    private void acceptSignature() { when(payos.validSignature(any(),anyString())).thenReturn(true); }

    @Test void signedPaymentIsIdempotentAndExtendsExistingExpiryAcrossConcurrentCallbacks() throws Exception {
        acceptSignature();
        var user=learner();
        Instant previous=Instant.now().plusSeconds(10*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",user.getId(),Timestamp.from(previous));
        long code=pending(user,49000,"concurrent"+UUID.randomUUID());
        var payload=event(code,49000,(String)db.queryForObject("SELECT payment_link_id FROM payment_orders WHERE order_code=?",String.class,code),UUID.randomUUID().toString());
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->billing.webhook(payload));
            var second=workers.submit(()->billing.webhook(payload));
            first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertThat(access.status(user.getId()).get("premiumUntil")).isEqualTo(previous.plusSeconds(30*86400));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_audit WHERE subject_id=? AND action='PREMIUM_PAYMENT'",Integer.class,user.getId())).isEqualTo(1);
        assertThat(billing.orders(user.getId()).get(0).get("status")).isEqualTo("PAID");
    }

    @Test void webhookHasSignatureProtectionWithoutBrowserCsrfAndDoesNotTrustReturnParameters() throws Exception {
        var user=learner(); long code=pending(user,49000,"link"+UUID.randomUUID());
        var principal=DictaLabPrincipal.from(user);
        mvc.perform(get("/account?orderCode="+code+"&status=PAID")).andExpect(status().isOk());
        assertThat(access.status(user.getId()).get("premium")).isEqualTo(false);
        mvc.perform(post("/api/billing/payos/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(event(code,49000,"wrong","ref")))).andExpect(status().isUnauthorized());
        acceptSignature();
        mvc.perform(post("/api/billing/payos/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(event(code,1,"wrong","ref")))).andExpect(status().isBadRequest());
        assertThat(access.status(user.getId()).get("premium")).isEqualTo(false);
        mvc.perform(post("/api/billing/orders").with(user(principal))).andExpect(status().isForbidden());
        mvc.perform(get("/api/billing/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/billing/orders/"+code+"/check").with(user(DictaLabPrincipal.from(learner()))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/billing/orders").with(user(DictaLabPrincipal.from(learner())))).andExpect(content().json("[]"));
        mvc.perform(post("/api/billing/payos/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(event(99999999,1000,"sample","sample")))).andExpect(status().isOk());
        mvc.perform(post("/api/billing/payos/webhook").contentType(MediaType.APPLICATION_JSON).content("bad-json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/billing/payos/webhook").contentType(MediaType.APPLICATION_JSON).content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test void accountDeletionRemovesAccessAndKeepsDetachedOrdersForLatePaymentReconciliation() throws Exception {
        var user=learner();
        access.takePractice(user.getId());
        db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",user.getId(),Timestamp.from(Instant.now().plusSeconds(86400)));
        String link="deleted"+UUID.randomUUID();
        long code=pending(user,49000,link);
        var admin=learner(); admin.promoteToAdmin(Instant.now()); users.saveAndFlush(admin);
        var ticket=mvc.perform(post("/api/support/tickets").with(user(DictaLabPrincipal.from(user))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("category","ACCOUNT_DELETE",
                        "subject","Delete account","description","Please delete my account and learning data"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/admin/support/tickets/"+json.readTree(ticket).path("id").asLong()+"/delete-account")
                .with(user(DictaLabPrincipal.from(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",user.getEmail())))).andExpect(status().isOk());
        assertThat(users.existsById(user.getId())).isFalse();
        for (String table: java.util.List.of("premium_accounts","premium_daily_usage"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,user.getId())).isZero();
        assertThat(db.queryForObject("SELECT user_id FROM payment_orders WHERE order_code=?",Long.class,code)).isNull();
        acceptSignature();
        var payload=event(code,49000,link,"late"+UUID.randomUUID());
        billing.webhook(payload); billing.webhook(payload);
        assertThat(db.queryForObject("SELECT status FROM payment_orders WHERE order_code=?",String.class,code)).isEqualTo("PAID");
        assertThat(users.existsById(user.getId())).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM premium_accounts WHERE user_id=?",Integer.class,user.getId())).isZero();
    }

    @Test void unknownCreateOutcomeIsKeptAndRecoveredWithoutRepeatingTheCreate() {
        var user=learner();
        when(payos.create(anyLong(),eq(49000),anyLong())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->billing.create(user.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->billing.create(user.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(payos,times(1)).create(anyLong(),eq(49000),anyLong());
        long code=((Number)billing.orders(user.getId()).get(0).get("orderCode")).longValue();
        when(payos.lookup(code)).thenReturn(json.valueToTree(Map.of("orderCode",code,"amount",49000,"amountPaid",49000,"status","PAID","id","recovered"+code)));
        assertThat(billing.check(user.getId(),code).get("status")).isEqualTo("PAID");
        assertThat(access.status(user.getId()).get("premium")).isEqualTo(true);
    }

    @Test void freePracticeIsAtomicAndPremiumAndAdminRulesAreEnforced() throws Exception {
        var user=learner();
        for(int index=0;index<3;index++) access.takePractice(user.getId());
        assertThatThrownBy(()->access.takePractice(user.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(access.status(user.getId()).get("lessonsUsed")).isEqualTo(3);
        assertThatThrownBy(()->budget.reserve(user.getId(),false)).isInstanceOf(AiUsageBudget.Rejected.class);
        db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",user.getId(),Timestamp.from(Instant.now().plusSeconds(86400)));
        for(int index=0;index<10;index++) { access.takePractice(user.getId()); budget.reserve(user.getId(),false); }
        assertThatThrownBy(()->budget.reserve(user.getId(),false)).isInstanceOf(AiUsageBudget.Rejected.class);
        budget.reserve(user.getId(),true);
        assertThat(access.status(user.getId()).get("aiUsed")).isEqualTo(10);
        var admin=learner(); db.update("UPDATE app_users SET role='ADMIN' WHERE id=?",admin.getId());
        for(int index=0;index<12;index++) { access.takePractice(admin.getId()); budget.reserve(admin.getId(),false); }
        assertThat(access.status(admin.getId()).get("premium")).isEqualTo(true);
        var rollback=learner();
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{access.takePractice(rollback.getId());throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(access.status(rollback.getId()).get("lessonsUsed")).isEqualTo(0);
    }

    @Test void aSuccessfulCheckoutIsReusedAndItsPriceSnapshotDoesNotTrustTheBrowser() throws Exception {
        var account=learner();
        when(payos.create(anyLong(),eq(49000),anyLong())).thenAnswer(invocation -> json.valueToTree(Map.of(
                "orderCode",invocation.getArgument(0,Long.class),"amount",49000,"currency","VND",
                "paymentLinkId","created"+invocation.getArgument(0),"checkoutUrl","https://pay.payos.vn/web/created"+invocation.getArgument(0))));
        var first=billing.create(account.getId());
        assertThat(billing.create(account.getId()).get("orderCode")).isEqualTo(first.get("orderCode"));
        verify(payos,times(1)).create(anyLong(),eq(49000),anyLong());
        mvc.perform(post("/api/billing/orders").with(user(DictaLabPrincipal.from(account))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amountVnd\":1,\"durationDays\":99999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountVnd").value(49000))
                .andExpect(jsonPath("$.durationDays").value(30));
    }

    @Test void practiceQuotaIsSharedAcrossModesButResumingDoesNotConsumeItAgain() {
        var account=learner();
        long lesson=db.queryForObject("SELECT MIN(id) FROM lessons WHERE active=TRUE AND content_origin<>'BRITISH_COUNCIL_PERSONAL'",Long.class);
        var request=new com.dictalab.dto.ApiDtos.StartSessionRequest(lesson);
        String id=practice.start(account.getId(),request).sessionId();
        assertThat(practice.start(account.getId(),request).sessionId()).isEqualTo(id);
        assertThat(access.status(account.getId()).get("lessonsUsed")).isEqualTo(1);
        long unit=db.queryForObject("SELECT MIN(u.id) FROM listening_units u JOIN lessons l ON l.id=u.lesson_id WHERE u.published=TRUE AND l.active=TRUE AND l.content_origin<>'BRITISH_COUNCIL_PERSONAL'",Long.class);
        listening.start(account.getId(),unit,"ONE_PASS",false);
        access.takePractice(account.getId());
        assertThatThrownBy(()->listening.start(account.getId(),unit,"ONE_PASS",true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(practice.start(account.getId(),request).sessionId()).isEqualTo(id);
        assertThat(access.status(account.getId()).get("lessonsUsed")).isEqualTo(3);
    }
}
