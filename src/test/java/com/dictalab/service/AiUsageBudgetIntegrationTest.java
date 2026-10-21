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

    @Test void rejectedUserReservationRollsBackGlobalCounter() {
        var budget=budget(100,10,10);
        budget.reserve(actor,false);
        assertThatThrownBy(()->budget.reserve(actor,false)).isInstanceOf(ProviderGuard.Unavailable.class);
        assertThat(db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope='GLOBAL'",Long.class)).isEqualTo(10);
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
