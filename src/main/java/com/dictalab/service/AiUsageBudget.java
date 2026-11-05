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
    @Value("${dictalab.ai.platform.text-provider:gemini}")
    private String textProvider = "gemini";

    @org.springframework.beans.factory.annotation.Autowired
    void configurePremium(PremiumAccessService premium) { this.premium=premium; }

    public boolean aiAllowed(long userId) { return premium == null || premium.aiAllowed(userId); }

    public void requireAccess(long userId) { if (premium != null) premium.requireAi(userId); }

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
        transaction.executeWithoutResult(status -> admit(userId,tts,local));
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void reserveDemo(Long userId, boolean local) { admit(userId,false,local); }

    /** An operation and its full bounded call envelope commit atomically with this admission. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void reserveScenario(long userId, String operationId, int calls, boolean tts, boolean local) {
        if (calls < 1 || calls > 7 || tts && calls != 1) throw new IllegalArgumentException("Invalid scenario envelope");
        if (db.queryForObject("SELECT COUNT(*) FROM scenario_operation_budget WHERE operation_id=?", Integer.class, operationId) > 0) return;
        if (premium != null) premium.takeAi(userId, tts);
        Boolean accepting = db.queryForObject("SELECT enabled FROM ai_budget_control WHERE id=1 FOR UPDATE", Boolean.class);
        if (!Boolean.TRUE.equals(accepting)) throw new Rejected("AI đang tạm dừng. Bài làm của bạn vẫn được lưu.");
        if (enabled) {
            Date day = Date.valueOf(LocalDate.now(ZoneOffset.UTC));
            long amount = local && !tts ? 0 : Math.multiplyExact(tts ? ttsReservation : llmReservation, calls);
            String prefix = local && !tts ? "LOCAL_" : "";
            add(day, prefix + "GLOBAL", amount, globalLimit, globalCalls, calls);
            add(day, prefix + "USER:" + userId, amount, userLimit, userCalls, calls);
        }
        db.update("INSERT INTO scenario_operation_budget(operation_id,user_id,calls_reserved,local_provider,tts) VALUES (?,?,?,?,?)",
                operationId, userId, calls, local, tts);
    }

    private void admit(Long userId, boolean tts, boolean local) {
            if (premium != null && userId != null) premium.takeAi(userId,tts);
            Boolean accepting=db.queryForObject("SELECT enabled FROM ai_budget_control WHERE id=1 FOR UPDATE",Boolean.class);
            if (!Boolean.TRUE.equals(accepting)) throw new Rejected("AI đang tạm dừng. Bài làm của bạn vẫn được lưu.");
            if (!enabled) return;
            Date day=Date.valueOf(LocalDate.now(ZoneOffset.UTC));
            long amount=local ? 0 : tts?ttsReservation:llmReservation;
            add(day,local ? "LOCAL_GLOBAL" : "GLOBAL",amount,globalLimit,globalCalls);
            if (userId!=null) add(day,(local ? "LOCAL_USER:" : "USER:")+userId,amount,userLimit,userCalls);
    }

    private void add(Date day,String scope,long amount,long limit,int calls) {
        add(day,scope,amount,limit,calls,1);
    }

    private void add(Date day,String scope,long amount,long limit,int calls,int count) {
        if (amount>limit) throw rejected("Đã đạt giới hạn AI trong ngày. Bạn vẫn có thể luyện dictation.");
        if (db.update("""
                UPDATE ai_daily_budget SET reserved_microusd=reserved_microusd+?,calls=calls+?
                WHERE budget_day=? AND scope=? AND reserved_microusd<=? AND calls<=?
                """,amount,count,day,scope,limit-amount,calls-count)==1) return;
        if (count > calls) throw rejected("Đã đạt giới hạn AI trong ngày. Bạn vẫn có thể luyện dictation.");
        if (db.queryForObject("SELECT COUNT(*) FROM ai_daily_budget WHERE budget_day=? AND scope=?",Integer.class,day,scope)>0)
            throw rejected("Đã đạt giới hạn AI trong ngày. Bạn vẫn có thể luyện dictation.");
        db.update("INSERT INTO ai_daily_budget(budget_day,scope,reserved_microusd,calls) VALUES (?,?,?,?)",day,scope,amount,count);
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
        private final org.springframework.http.HttpStatus status;
        public Rejected(String message) { this(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, message); }
        public Rejected(org.springframework.http.HttpStatus status, String message) {
            super(message,60,false);
            this.status=status;
        }
        public org.springframework.http.HttpStatus status() { return status; }
    }

    private Rejected rejected(String message) {
        return new Rejected(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
