package com.dictalab.service;

import com.dictalab.config.BillingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Service
public class PremiumAccessService {
    private final JdbcTemplate db;
    private final BillingProperties config;
    private final java.time.Clock clock;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public PremiumAccessService(JdbcTemplate db, BillingProperties config, java.time.Clock paymentClock) { this.db=db; this.config=config; this.clock=paymentClock; }

    public Map<String,Object> status(long userId) {
        var result = new HashMap<String,Object>();
        var account = db.queryForList("SELECT premium_until FROM premium_accounts WHERE user_id=?", userId);
        Instant until = account.isEmpty() ? null : instant(account.get(0).get("premium_until"));
        boolean admin = "ADMIN".equals(db.queryForObject("SELECT role FROM app_users WHERE id=?", String.class, userId));
        result.put("enabled", config.enabled());
        result.put("premium", admin || until != null && until.isAfter(clock.instant()));
        result.put("administrator", admin);
        result.put("premiumUntil", until);
        result.put("priceVnd", config.priceVnd());
        result.put("durationDays", config.durationDays());
        result.put("freeDailyLessons", config.freeDailyLessons());
        result.put("premiumDailyAi", config.premiumDailyAi());
        var usage = db.queryForList("SELECT practice_count,ai_count FROM premium_daily_usage WHERE user_id=? AND usage_day=?", userId, day());
        result.put("lessonsUsed", usage.isEmpty() ? 0 : usage.get(0).get("practice_count"));
        result.put("aiUsed", usage.isEmpty() ? 0 : usage.get(0).get("ai_count"));
        return result;
    }

    @Transactional
    public void takePractice(long userId) {
        if (!config.enabled()) return;
        lockUser(userId);
        if (premium(userId)) return;
        Date today = day();
        ensureUsage(userId, today);
        if (db.update("UPDATE premium_daily_usage SET practice_count=practice_count+1 WHERE user_id=? AND usage_day=? AND practice_count<?",
                userId, today, config.freeDailyLessons()) != 1)
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Bạn đã dùng hết " + config.freeDailyLessons() + " bài miễn phí hôm nay. Nâng cấp Premium tại Tài khoản hoặc quay lại ngày mai.");
    }

    public boolean aiAllowed(long userId) {
        return !config.enabled() || premium(userId);
    }

    public void requireAi(long userId) {
        if (!aiAllowed(userId)) throw new AiUsageBudget.Rejected(
                HttpStatus.PAYMENT_REQUIRED, "Tính năng AI dành cho Premium. Bạn có thể nâng cấp tại Tài khoản.");
    }

    // Called inside the durable AI budget transaction, before provider I/O.
    public void takeAi(long userId, boolean tts) {
        if (!config.enabled()) return;
        String role = lockUser(userId);
        if ("ADMIN".equals(role)) return;
        requireAi(userId);
        if (tts) return;
        Date today = day();
        ensureUsage(userId, today);
        if (db.update("UPDATE premium_daily_usage SET ai_count=ai_count+1 WHERE user_id=? AND usage_day=? AND ai_count<?",
                userId, today, config.premiumDailyAi()) != 1)
            throw new AiUsageBudget.Rejected(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã dùng hết lượt AI hôm nay. Bạn vẫn có thể luyện bài và xem báo cáo đã tạo.");
    }

    String lockUser(long userId) {
        return db.queryForObject("SELECT role FROM app_users WHERE id=? FOR UPDATE", String.class, userId);
    }

    private boolean premium(long userId) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT CASE WHEN role='ADMIN' OR EXISTS (
                    SELECT 1 FROM premium_accounts WHERE user_id=? AND premium_until>?) THEN TRUE ELSE FALSE END
                FROM app_users WHERE id=?
                """, Boolean.class, userId, java.sql.Timestamp.from(clock.instant()), userId));
    }
    private void ensureUsage(long userId, Date today) {
        if (db.queryForObject("SELECT COUNT(*) FROM premium_daily_usage WHERE user_id=? AND usage_day=?", Integer.class, userId, today) == 0)
            db.update("INSERT INTO premium_daily_usage(user_id,usage_day) VALUES (?,?)", userId, today);
    }
    private Date day() { return Date.valueOf(LocalDate.now(clock.withZone(ZONE))); }
    static Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime at) return at.toInstant();
        return ((java.sql.Timestamp)value).toInstant();
    }
}
