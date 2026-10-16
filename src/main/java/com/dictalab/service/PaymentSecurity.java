package com.dictalab.service;

import com.dictalab.config.PaymentOperationsProperties;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

@Service
public class PaymentSecurity {
    public static final Set<String> PERMISSIONS=Set.of("PAYMENT_VIEW","PAYMENT_RECONCILE","REFUND_REQUEST","REFUND_APPROVE","REFUND_EXECUTE","PAYMENT_AUDIT","PAYMENT_MANAGE");
    private final JdbcTemplate db;
    private final PaymentOperationsProperties config;
    private final Clock clock;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final PaymentOperations operations;
    private final Environment environment;
    private final SecureRandom random=new SecureRandom();
    private final TimeBasedOneTimePasswordGenerator totp=new TimeBasedOneTimePasswordGenerator();
    public PaymentSecurity(JdbcTemplate db,PaymentOperationsProperties config,Clock paymentClock,PasswordEncoder passwords,
            PlatformTransactionManager manager,PaymentOperations operations,Environment environment) {
        this.db=db;this.config=config;this.clock=paymentClock;this.passwords=passwords;this.operations=operations;this.environment=environment;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public Set<String> permissions(long actor) {
        return new TreeSet<>(db.queryForList("SELECT p.permission FROM payment_permissions p JOIN app_users u ON u.id=p.user_id WHERE u.id=? AND u.enabled=TRUE",String.class,actor));
    }
    public void require(long actor,String permission) {
        if(!permissions(actor).contains(permission)) throw error(HttpStatus.FORBIDDEN,"PAYMENT_PERMISSION_REQUIRED");
    }
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if(config.bootstrapEmail().isBlank() || Arrays.asList(environment.getActiveProfiles()).contains("restore-smoke")) return;
        tx.executeWithoutResult(s->{
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            if(!rows(db,"SELECT id FROM payment_bootstrap WHERE id=1").isEmpty())return;
            var account=rows(db,"SELECT id FROM app_users WHERE LOWER(email)=LOWER(?) AND enabled=TRUE AND role='ADMIN'",config.bootstrapEmail());
            if(account.size()!=1) return;
            long actor=number(account.get(0),"id");
            for(String p:PERMISSIONS) if(!permissions(actor).contains(p)) db.update("INSERT INTO payment_permissions(user_id,permission) VALUES (?,?)",actor,p);
            db.update("INSERT INTO payment_bootstrap(id,created_at) VALUES (1,?)",ts(clock.instant()));
            operations.audit(actor,"FINANCE_BOOTSTRAP",Long.toString(actor),"Explicit protected runtime owner configuration; no ADMIN inheritance");
        });
    }
    private void password(long actor,String supplied) {
        var user=one(db,"SELECT password_hash,enabled FROM app_users WHERE id=?",actor);
        if(!Boolean.TRUE.equals(user.get("enabled")) || supplied==null || supplied.length()>72 || !passwords.matches(supplied,(String)user.get("password_hash")))
            throw error(HttpStatus.UNAUTHORIZED,"PAYMENT_REAUTHENTICATION_REQUIRED");
    }
    public Map<String,Object> beginTotp(long actor,String password) {
        require(actor,"PAYMENT_VIEW");password(actor,password);
        if(config.encryptionKey().isBlank()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_STEP_UP_NOT_CONFIGURED");
        byte[] secret=new byte[20];random.nextBytes(secret);String base32=base32(secret);
        tx.executeWithoutResult(s->{
            one(db,"SELECT id FROM app_users WHERE id=? FOR UPDATE",actor);
            var existing=rows(db,"SELECT enabled FROM payment_totp WHERE user_id=?",actor);
            if(!existing.isEmpty() && Boolean.TRUE.equals(existing.get(0).get("enabled"))) throw error(HttpStatus.CONFLICT,"PAYMENT_TOTP_ALREADY_ENROLLED");
            db.update("DELETE FROM payment_totp WHERE user_id=?",actor);
            db.update("INSERT INTO payment_totp(user_id,secret_cipher,created_at) VALUES (?,?,?)",actor,encrypt(secret),ts(clock.instant()));
            operations.audit(actor,"TOTP_ENROLLMENT_STARTED",Long.toString(actor),"Password reauthentication completed");
        });
        // Returned once over authenticated no-store response; never logged or saved in plaintext.
        return Map.of("secret",base32,"uri","otpauth://totp/DictaLab:finance-"+actor+"?secret="+base32+"&issuer=DictaLab&digits=6&period=30");
    }
    public void enableTotp(long actor,String otp) {
        require(actor,"PAYMENT_VIEW");verifyOtp(actor,otp,false);
        db.update("UPDATE payment_totp SET enabled=TRUE WHERE user_id=?",actor);
        operations.audit(actor,"TOTP_ENABLED",Long.toString(actor),"Enrollment code verified");
    }
    public String challenge(long actor,String binding,String otp) {
        if(permissions(actor).isEmpty()) throw error(HttpStatus.FORBIDDEN,"PAYMENT_PERMISSION_REQUIRED");
        if(binding==null || !binding.matches("[a-f0-9]{64}")) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CHALLENGE_BINDING_INVALID");
        verifyOtp(actor,otp,true);
        String challenge=id();db.update("INSERT INTO payment_challenges(id,actor_id,binding_hash,expires_at) VALUES (?,?,?,?)",challenge,actor,binding,ts(clock.instant().plusSeconds(120)));
        return challenge;
    }
    private void verifyOtp(long actor,String otp,boolean enabled) {
        boolean accepted=Boolean.TRUE.equals(tx.execute(s->{
            var users=rows(db,"SELECT * FROM payment_totp WHERE user_id=? FOR UPDATE",actor);
            if(users.isEmpty() || enabled && !Boolean.TRUE.equals(users.get(0).get("enabled"))) return false;
            var row=users.get(0);
            if(row.get("blocked_until")!=null && at(row.get("blocked_until")).isAfter(clock.instant()))return false;
            long step=clock.instant().getEpochSecond()/30,last=number(row,"last_step"),matched=-1;
            if(otp!=null && otp.matches("[0-9]{6}")) {
                var key=new SecretKeySpec(decrypt((String)row.get("secret_cipher")),"HmacSHA1");
                try {
                    for(long candidate=step-1;candidate<=step+1;candidate++) if(candidate>last && MessageDigest.isEqual(
                            totp.generateOneTimePasswordString(key,Instant.ofEpochSecond(candidate*30)).getBytes(StandardCharsets.US_ASCII),otp.getBytes(StandardCharsets.US_ASCII))) matched=candidate;
                } catch(java.security.InvalidKeyException invalid) { throw new IllegalStateException("Invalid protected TOTP key"); }
            }
            if(matched<0) {
                int count=((Number)row.get("attempts")).intValue()+1;
                db.update("UPDATE payment_totp SET attempts=?,blocked_until=? WHERE user_id=?",count,count>=5?ts(clock.instant().plusSeconds(300)):null,actor);return false;
            }
            db.update("UPDATE payment_totp SET attempts=0,blocked_until=NULL,last_step=? WHERE user_id=?",matched,actor);return true;
        }));
        if(!accepted) throw error(HttpStatus.UNAUTHORIZED,"PAYMENT_STEP_UP_FAILED");
    }
    /** Must share the command transaction: a failed command does not consume the approval challenge. */
    public void consume(long actor,String challenge,String binding) {
        if(db.update("UPDATE payment_challenges SET used_at=? WHERE id=? AND actor_id=? AND binding_hash=? AND used_at IS NULL AND expires_at>?",
                ts(clock.instant()),challenge,actor,binding,ts(clock.instant()))!=1) throw error(HttpStatus.FORBIDDEN,"PAYMENT_CHALLENGE_EXPIRED_OR_REPLAYED");
    }
    public void setPermissions(long actor,long target,Set<String> permissions,String challenge,String reason) {
        require(actor,"PAYMENT_MANAGE");text(reason,1000);
        if(!PERMISSIONS.containsAll(permissions) || actor==target) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_PERMISSION_ASSIGNMENT_INVALID");
        tx.executeWithoutResult(s->{
            one(db,"SELECT id FROM app_users WHERE id=? AND enabled=TRUE FOR UPDATE",target);
            consume(actor,challenge,hash("PERMISSIONS|"+target+"|"+String.join(",",new TreeSet<>(permissions))));
            db.update("DELETE FROM payment_permissions WHERE user_id=?",target);
            for(String permission:permissions)db.update("INSERT INTO payment_permissions(user_id,permission) VALUES (?,?)",target,permission);
            operations.audit(actor,"PERMISSIONS_CHANGED",Long.toString(target),reason);
        });
    }
    private String encrypt(byte[] secret) {
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(config.encryptionKey()),"AES"),new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(secret);byte[] result=new byte[12+encrypted.length];System.arraycopy(nonce,0,result,0,12);System.arraycopy(encrypted,0,result,12,encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch(GeneralSecurityException failure) { throw new IllegalStateException("Payment evidence encryption unavailable"); }
    }
    private byte[] decrypt(String input) {
        try {
            byte[] bytes=Base64.getDecoder().decode(input);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(config.encryptionKey()),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));
            return cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length));
        } catch(GeneralSecurityException failure) { throw new IllegalStateException("Payment evidence decryption unavailable"); }
    }
    private String base32(byte[] bytes) {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";StringBuilder result=new StringBuilder();int buffer=0,bits=0;
        for(byte value:bytes) { buffer=(buffer<<8)|(value&255);bits+=8;while(bits>=5){bits-=5;result.append(alphabet.charAt((buffer>>>bits)&31));} }
        if(bits>0)result.append(alphabet.charAt((buffer<<(5-bits))&31));return result.toString();
    }
}
