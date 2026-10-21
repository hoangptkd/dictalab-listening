package com.dictalab.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/** Conservative admission reservations, not provider invoices. Unknown outcomes retain their reservation. */
@Service
public class AiUsageBudget {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final long globalLimit, userLimit, llmReservation, ttsReservation;
    private final int globalCalls, userCalls;
    private final boolean enabled;
    private PremiumAccessService premium;
    @Value("${dictalab.ai.platform.text-provider:shopaikey}")
    private String textProvider = "shopaikey";

    @org.springframework.beans.factory.annotation.Autowired
    void configurePremium(PremiumAccessService premium) { this.premium=premium; }

    public AiUsageBudget(JdbcTemplate db, PlatformTransactionManager manager,
                         @Value("${dictalab.ai.budget.enabled:true}") boolean enabled,
                         @Value("${dictalab.ai.budget.global-microusd:5000000}") long globalLimit,
                         @Value("${dictalab.ai.budget.user-microusd:2000000}") long userLimit,
                         @Value("${dictalab.ai.budget.llm-reservation-microusd:50000}") long llmReservation,
                         @Value("${dictalab.ai.budget.tts-reservation-microusd:50000}") long ttsReservation,
                         @Value("${dictalab.ai.budget.global-calls:100}") int globalCalls,
                         @Value("${dictalab.ai.budget.user-calls:40}") int userCalls) {
        if (globalLimit < 1 || userLimit < 1 || llmReservation < 1 || ttsReservation < 1
                || globalCalls < 1 || userCalls < 1) throw new IllegalArgumentException("Invalid AI admission budget");
        this.db=db; this.enabled=enabled; this.globalLimit=globalLimit; this.userLimit=userLimit;
        this.llmReservation=llmReservation; this.ttsReservation=ttsReservation;
        this.globalCalls=globalCalls; this.userCalls=userCalls;
        transaction=new TransactionTemplate(manager);
        // Reserve durably before provider I/O, even if the surrounding business transaction rolls back.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    public void reserve(Long userId, boolean tts) {
        reserve(userId, tts, !tts && textProvider.equals("ollama"));
    }

    public void reserveLocal(Long userId) {
        reserve(userId, false, true);
    }

    private void reserve(Long userId, boolean tts, boolean local) {
        transaction.executeWithoutResult(status -> {
            if (premium != null && userId != null) premium.takeAi(userId,tts);
            Boolean accepting=db.queryForObject("SELECT enabled FROM ai_budget_control WHERE id=1 FOR UPDATE",Boolean.class);
            if (!Boolean.TRUE.equals(accepting)) throw rejected("AI đang tạm dừng. Bài làm của bạn vẫn được lưu.");
            if (!enabled && !local) return; // Local always retains resource call limits, even without dollar accounting.
            Date day=Date.valueOf(LocalDate.now(ZoneOffset.UTC));
            long amount=local ? 0 : tts?ttsReservation:llmReservation;
            add(day,local ? "LOCAL_GLOBAL" : "GLOBAL",amount,globalLimit,globalCalls);
            if (userId!=null) add(day,(local ? "LOCAL_USER:" : "USER:")+userId,amount,userLimit,userCalls);
        });
    }

    private void add(Date day,String scope,long amount,long limit,int calls) {
        if (amount>limit) throw rejected("Đã đạt giới hạn AI trong ngày. Bạn vẫn có thể luyện dictation.");
        if (db.update("""
                UPDATE ai_daily_budget SET reserved_microusd=reserved_microusd+?,calls=calls+1
                WHERE budget_day=? AND scope=? AND reserved_microusd<=? AND calls<?
                """,amount,day,scope,limit-amount,calls)==1) return;
        if (db.queryForObject("SELECT COUNT(*) FROM ai_daily_budget WHERE budget_day=? AND scope=?",Integer.class,day,scope)>0)
            throw rejected("Đã đạt giới hạn AI trong ngày. Bạn vẫn có thể luyện dictation.");
        db.update("INSERT INTO ai_daily_budget(budget_day,scope,reserved_microusd,calls) VALUES (?,?,?,1)",day,scope,amount);
    }

    public Map<String,Object> status() {
        Date day=Date.valueOf(LocalDate.now(ZoneOffset.UTC));
        return Map.of("enabled",Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM ai_budget_control WHERE id=1",Boolean.class)),
                "accountingEnabled",enabled,"dayUtc",day.toString(),"globalLimitMicrousd",globalLimit,
                "userLimitMicrousd",userLimit,"globalCallLimit",globalCalls,"userCallLimit",userCalls,
                "usage",db.queryForList("SELECT scope,reserved_microusd,calls FROM ai_daily_budget WHERE budget_day=? ORDER BY scope",day));
    }

    public void setEnabled(long actor,boolean accepting) {
        transaction.executeWithoutResult(status -> {
            db.update("UPDATE ai_budget_control SET enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",accepting);
            db.update("INSERT INTO account_audit(actor_id,subject_id,action,detail) VALUES (?,?,?,?)",
                    actor,actor,"AI_EMERGENCY_SWITCH",Boolean.toString(accepting));
        });
    }

    /** Only server-authored budget messages may reach the learner without being generalized. */
    public static final class Rejected extends ProviderGuard.Unavailable {
        public Rejected(String message) { super(message,60,false); }
    }

    private Rejected rejected(String message) { return new Rejected(message); }
}
