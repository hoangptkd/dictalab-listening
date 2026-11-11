package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AiUsageBudgetIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired AppUserRepository users;
    long actor;

    @BeforeEach void setup() {
        db.update("DELETE FROM ai_daily_budget");
        db.update("UPDATE ai_budget_control SET enabled=TRUE WHERE id=1");
        actor=users.saveAndFlush(new AppUser("budget-"+UUID.randomUUID()+"@test.local","Budget","unused",Instant.now())).getId();
    }
    @org.junit.jupiter.api.AfterEach void resetEmergencySwitch() {
        db.update("UPDATE ai_budget_control SET enabled=TRUE WHERE id=1");
    }
    private AiUsageBudget budget(long global,long perUser,int calls) {
        return new AiUsageBudget(db,manager,true,global,perUser,10,20,calls,calls);
    }

    @Test void disabledBudgetAlsoRemovesLocalDailyCallCap(){
        var budget=new AiUsageBudget(db,manager,false,1,1,10,20,1,1);
        for(int i=0;i<101;i++){budget.reserveLocal(actor);budget.reserve(actor,false);}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_daily_budget",Integer.class)).isZero();
        budget.setEnabled(actor,false);
        assertThatThrownBy(()->budget.reserveLocal(actor)).isInstanceOf(AiUsageBudget.Rejected.class);
    }

    @Test void rejectedUserReservationRollsBackGlobalCounter() {
        var budget=budget(100,10,10);
        budget.reserve(actor,false);
        assertThatThrownBy(()->budget.reserve(actor,false)).isInstanceOf(ProviderGuard.Unavailable.class);
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='GLOBAL'",Long.class)).isEqualTo(10);
    }

    @Test void localQaSkipsDailyTextAndAudioBudgetsButKeepsOtherUsersAndEmergencyStop() {
        var budget=budget(20,20,1);
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active","local");
        budget.configurePremium(org.mockito.Mockito.mock(PremiumAccessService.class),new LocalQaAccount(environment,actor));
        for(int i=0;i<3;i++){budget.reserve(actor,false);budget.reserve(actor,true);budget.reserveLocal(actor);}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_daily_budget",Integer.class)).isZero();
        budget.reserve(actor+1,false);
        assertThatThrownBy(()->budget.reserve(actor+1,false)).isInstanceOf(AiUsageBudget.Rejected.class);
        budget.setEnabled(actor,false);
        assertThatThrownBy(()->budget.reserve(actor,true)).isInstanceOf(AiUsageBudget.Rejected.class);
    }

    @Test void localQaScenarioAdmissionKeepsTheDurableEnvelopeDespiteExhaustedDailyBudget() {
        var budget=budget(20,20,1);
        budget.reserve(actor,false); // Exhaust the call allowance before enabling the QA exception.
        budget.configurePremium(org.mockito.Mockito.mock(PremiumAccessService.class),
                new LocalQaAccount(new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active","local"),actor));
        String operation=UUID.randomUUID().toString();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            db.update("""
                    INSERT INTO scenario_operations(id,user_id,request_key,scope,input_hash,kind,resource_id,payload_json,deadline)
                    VALUES (?,?,?,?,?,'GENERATE',?,'{}',CURRENT_TIMESTAMP)
                    """,operation,actor,UUID.randomUUID().toString(),"qa-budget-test","0".repeat(64),UUID.randomUUID().toString());
            budget.reserveScenario(actor,operation,11,false,false);
            budget.reserveScenario(actor,operation,11,false,false);
        });
        assertThat(db.queryForObject("SELECT calls_reserved FROM scenario_operation_budget WHERE operation_id=?",Integer.class,operation)).isEqualTo(11);
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope='GLOBAL'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->budget.reserve(actor+1,false)).isInstanceOf(AiUsageBudget.Rejected.class);
        assertThatThrownBy(()->budget.reserveScenario(actor,UUID.randomUUID().toString(),12,false,false)).isInstanceOf(IllegalArgumentException.class);
        budget.setEnabled(actor,false);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status ->
                budget.reserveScenario(actor,UUID.randomUUID().toString(),11,false,false))).isInstanceOf(AiUsageBudget.Rejected.class);
    }

    @Test void elevenCallReservationIsIdempotentAndRejectedAdmissionRollsBackDailyCounters() {
        var budget=budget(1000,1000,11);
        String operation=UUID.randomUUID().toString();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            db.update("""
                    INSERT INTO scenario_operations(id,user_id,request_key,scope,input_hash,kind,resource_id,payload_json,deadline)
                    VALUES (?,?,?,?,?,'GENERATE',?,'{}',CURRENT_TIMESTAMP)
                    """,operation,actor,UUID.randomUUID().toString(),"budget-test","0".repeat(64),UUID.randomUUID().toString());
            budget.reserveScenario(actor,operation,11,false,false);
            budget.reserveScenario(actor,operation,11,false,false);
        });
        assertThat(db.queryForObject("SELECT calls_reserved FROM scenario_operation_budget WHERE operation_id=?",Integer.class,operation)).isEqualTo(11);
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope='GLOBAL'",Integer.class)).isEqualTo(11);
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='GLOBAL'",Long.class)).isEqualTo(110);

        String rejected=UUID.randomUUID().toString();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            db.update("""
                    INSERT INTO scenario_operations(id,user_id,request_key,scope,input_hash,kind,resource_id,payload_json,deadline)
                    VALUES (?,?,?,?,?,'GENERATE',?,'{}',CURRENT_TIMESTAMP)
                    """,rejected,actor,UUID.randomUUID().toString(),"budget-test","1".repeat(64),UUID.randomUUID().toString());
            budget.reserveScenario(actor,rejected,11,false,false);
        })).isInstanceOf(AiUsageBudget.Rejected.class);
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope='GLOBAL'",Integer.class)).isEqualTo(11);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operation_budget WHERE operation_id=?",Integer.class,rejected)).isZero();
    }

    @Test void localQaHasPaidPracticeAndAiAccessWithoutBecomingAdministrator() {
        var config=new com.dictalab.config.BillingProperties(true,49000,30,1,1,"https://billing.test","test","test","test");
        var premium=new PremiumAccessService(db,config,java.time.Clock.systemUTC());
        premium.configureLocalQa(new LocalQaAccount(new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active","local"),actor));
        for(int i=0;i<3;i++) {
            premium.takePractice(actor);
            premium.takeAi(actor,false);
            premium.takeAi(actor,true);
        }
        assertThat(premium.status(actor)).containsEntry("premium",true).containsEntry("administrator",false);
        assertThat(premium.aiAllowed(actor)).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM premium_daily_usage WHERE user_id=?",Integer.class,actor)).isZero();
        long other=users.saveAndFlush(new AppUser("non-qa-"+UUID.randomUUID()+"@test.local","Regular","unused",Instant.now())).getId();
        assertThat(premium.aiAllowed(other)).isFalse();
        assertThatThrownBy(()->premium.takeAi(other,false)).isInstanceOf(AiUsageBudget.Rejected.class);
        premium.takePractice(other);
        assertThatThrownBy(()->premium.takePractice(other)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void concurrentAdmissionsNeverExceedGlobalBudget() throws Exception {
        var budget=budget(50,100,100);
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(10);
        try {
            var futures=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<10;i++) futures.add(pool.submit(()->{
                start.await();
                try {budget.reserve(actor,false);return true;}
                catch(ProviderGuard.Unavailable rejected){return false;}
            }));
            start.countDown(); int accepted=0;
            for(var future:futures) if(future.get(15,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
            assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope='GLOBAL'",Integer.class)).isEqualTo(5);
        } finally {pool.shutdownNow();}
    }

    @Test void unknownProviderOutcomeAndBusinessRollbackDoNotRefundAdmission() {
        var budget=budget(100,100,10);
        new TransactionTemplate(manager).executeWithoutResult(status->{budget.reserve(actor,true);status.setRollbackOnly();});
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='GLOBAL'",Long.class)).isEqualTo(20);
    }

    @Test void emergencyStopIsDurableAuditedAndCannotBeDisabledByAccountingFlag() {
        var budget=budget(100,100,10);
        budget.setEnabled(actor,false);
        var replacement=new AiUsageBudget(db,manager,false,100,100,10,20,10,10);
        assertThatThrownBy(()->replacement.reserve(actor,false)).isInstanceOf(ProviderGuard.Unavailable.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_daily_budget",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM account_audit WHERE actor_id=? AND action='AI_EMERGENCY_SWITCH'",Integer.class,actor)).isEqualTo(1);
        budget.setEnabled(actor,true);
        budget.reserve(actor,false);
    }

    @Test void localCallsHaveTheirOwnLimitsAndNoCloudDollarReservation() {
        var budget = budget(100,100,1);
        budget.reserveLocal(actor);
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope='LOCAL_GLOBAL'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='LOCAL_GLOBAL'",Long.class)).isZero();
        assertThatThrownBy(() -> budget.reserveLocal(actor)).isInstanceOf(AiUsageBudget.Rejected.class);
        budget.reserve(actor,false);
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='GLOBAL'",Long.class)).isEqualTo(10);
        budget.setEnabled(actor,false);
        assertThatThrownBy(() -> budget.reserveLocal(actor)).isInstanceOf(AiUsageBudget.Rejected.class);
    }

    @Test void localRoutingAndShadowKeepDollarAndLearnerAllowancesSeparate() {
        var budget = budget(100,100,10);
        org.springframework.test.util.ReflectionTestUtils.setField(budget, "textProvider", "ollama");
        budget.reserve(actor,false);
        budget.reserve(null,false); // Shadow reserves global resources, without a learner debit.
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope='LOCAL_GLOBAL'",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope=?",Integer.class,"LOCAL_USER:"+actor)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='LOCAL_GLOBAL'",Long.class)).isZero();
        budget.reserve(actor,true); // Cartesia remains a paid provider in local text mode.
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='GLOBAL'",Long.class)).isEqualTo(20);
    }
}
