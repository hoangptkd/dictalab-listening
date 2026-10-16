package com.dictalab.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Shared boundary rules, not a provider-independent payment framework. */
public final class PaymentData {
    private PaymentData() {}
    public static final long MAX_MONEY=1_000_000_000_000L;
    public static String id() { return UUID.randomUUID().toString(); }
    public static Timestamp ts(Instant value) { return Timestamp.from(value); }
    public static Instant at(Object value) { return PremiumAccessService.instant(value); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static ResponseStatusException error(HttpStatus status,String code) { return new ResponseStatusException(status,code); }
    public static long money(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_AMOUNT_INVALID");
        return money(value.asLong());
    }
    public static long money(long amount) {
        if (amount<1 || amount>MAX_MONEY) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_AMOUNT_INVALID");
        return amount;
    }
    public static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public static Map<String,Object> lower(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>(); row.forEach((k,v)->result.put(k.toLowerCase(Locale.ROOT),v)); return result;
    }
    public static List<Map<String,Object>> rows(JdbcTemplate db,String sql,Object... args) {
        return db.queryForList(sql,args).stream().map(PaymentData::lower).toList();
    }
    public static Map<String,Object> one(JdbcTemplate db,String sql,Object... args) {
        var rows=rows(db,sql,args); if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"PAYMENT_RESOURCE_NOT_FOUND"); return rows.get(0);
    }
    public static String text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || value.indexOf('\0')>=0)
            throw error(HttpStatus.BAD_REQUEST,"PAYMENT_FIELD_INVALID"); return value.strip();
    }
}
