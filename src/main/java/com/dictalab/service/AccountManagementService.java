package com.dictalab.service;

import com.dictalab.config.AdminProperties;
import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AccountManagementService {
    private static final Logger log = LoggerFactory.getLogger(AccountManagementService.class);
    private final AppUserRepository users;
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final ObjectProvider<JavaMailSender> mail;
    private final String publicUrl;
    private final String mailFrom;
    private final String smtpHost;
    private final AdminProperties adminProperties;
    private final SecureRandom random = new SecureRandom();
    private final TransactionTemplate transaction;
    private final EmailOutbox outbox;

    public AccountManagementService(AppUserRepository users, JdbcTemplate db, PasswordEncoder passwords,
                                    ObjectProvider<JavaMailSender> mail,
                                    @Value("${dictalab.public-url:http://localhost:18081}") String publicUrl,
                                    @Value("${dictalab.mail-from:}") String mailFrom,
                                    @Value("${spring.mail.host:}") String smtpHost,
                                    AdminProperties adminProperties, PlatformTransactionManager manager, EmailOutbox outbox) {
        this.users = users;
        this.db = db;
        this.passwords = passwords;
        this.mail = mail;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.mailFrom = mailFrom;
        this.smtpHost = smtpHost;
        this.adminProperties = adminProperties;
        this.transaction = new TransactionTemplate(manager);
        this.outbox = outbox;
    }

    @Transactional
    public AppUser rename(long id, String name) {
        AppUser user = require(id);
        user.rename(name.strip(), Instant.now());
        return user;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void changePassword(long id, String oldPassword, String newPassword) {
        AppUser snapshot = require(id);
        if (!passwords.matches(oldPassword, snapshot.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng.");
        String encoded = passwords.encode(newPassword);
        transaction.executeWithoutResult(status -> {
            AppUser user = requireUnchanged(snapshot);
            user.changePassword(encoded, Instant.now());
            audit(id, id, "CHANGE_PASSWORD", null);
        });
    }

    @Transactional
    public void revokeSessions(long id) {
        require(id).revokeSessions(Instant.now());
        audit(id, id, "REVOKE_SESSIONS", null);
    }

    @Transactional
    public void requestReset(String email, String ip) {
        if (mail.getIfAvailable() == null || mailFrom.isBlank() || smtpHost.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chức năng email chưa được cấu hình.");
        users.findByEmail(normalize(email)).filter(AppUser::isEnabled).ifPresent(user -> {
            try { sendToken(user, "RESET_PASSWORD", null, ip); }
            catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 429
                        && exception.getStatusCode().value() != 503) throw exception;
            }
        });
    }

    @Transactional
    public void requestVerification(long id, String ip) {
        AppUser user = require(id);
        if (!user.isEmailVerified()) sendToken(user, "VERIFY_EMAIL", null, ip);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requestEmailChange(long id, String newEmail, String password, String ip) {
        AppUser snapshot = require(id);
        String target = normalize(newEmail);
        if (!passwords.matches(password, snapshot.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu không đúng.");
        transaction.executeWithoutResult(status -> {
            AppUser user = requireUnchanged(snapshot);
            if (target.equals(user.getEmail()) || users.existsByEmail(target))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng.");
            sendToken(user, "CHANGE_EMAIL", target, ip);
        });
    }

    private void sendToken(AppUser user, String purpose, String targetEmail, String ip) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null || mailFrom.isBlank() || smtpHost.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chức năng email chưa được cấu hình.");
        db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, user.getId());
        Integer count = db.queryForObject("""
                SELECT COUNT(*) FROM account_tokens WHERE user_id=? AND purpose=? AND created_at>?
                """, Integer.class, user.getId(), purpose, Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        if (count != null && count >= 3)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng thử lại sau một giờ.");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant now = Instant.now();
        String recipient = targetEmail == null ? user.getEmail() : targetEmail;
        String route = "RESET_PASSWORD".equals(purpose) ? "/reset-password"
                : "/verify-email?purpose=" + ("CHANGE_EMAIL".equals(purpose) ? "change" : "verify") + "&token=";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipient);
        boolean resetPassword = "RESET_PASSWORD".equals(purpose);
        message.setSubject(resetPassword ? "DictaLab · Đặt lại mật khẩu" : "DictaLab · Xác nhận tài khoản");
        message.setText((resetPassword ? "Mở liên kết dưới đây trong 30 phút, nhập mật khẩu mới có ít nhất 8 ký tự, gồm chữ cái và chữ số. Sau khi đổi, đăng nhập lại bằng mật khẩu mới.\n" : "Mở liên kết trong 30 phút: ") + publicUrl + route
                + ("RESET_PASSWORD".equals(purpose) ? "?token=" : "") + token
                + "\nNếu bạn không yêu cầu, hãy bỏ qua email này.");
        db.update("""
                INSERT INTO account_tokens(token_hash,user_id,purpose,target_email,created_at,expires_at,request_ip)
                VALUES (?,?,?,?,?,?,?)
                """, hash(token), user.getId(), purpose, targetEmail, Timestamp.from(now), Timestamp.from(now.plus(30, ChronoUnit.MINUTES)),
                ip == null ? null : ip.substring(0, Math.min(64, ip.length())));
        db.update("""
                INSERT INTO email_outbox(id,token_hash,recipient,subject,body,available_at)
                VALUES (?,?,?,?,?,?)
                """, java.util.UUID.randomUUID().toString(), hash(token), recipient,
                message.getSubject(), message.getText(), Timestamp.from(now));
        outbox.wakeAfterCommit();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void consumeToken(String token, String purpose, String newPassword) {
        // Close JDBC's synchronization-bound connection before BCrypt, including NOT_SUPPORTED scopes.
        List<Map<String, Object>> rows = transaction.execute(status -> db.queryForList("""
                SELECT user_id,target_email FROM account_tokens
                WHERE token_hash=? AND purpose=? AND used_at IS NULL AND expires_at>?
                """, hash(token), purpose, Timestamp.from(Instant.now())));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Liên kết đã hết hạn hoặc không hợp lệ.");
        Map<String, Object> row = rows.get(0);
        String encoded = "RESET_PASSWORD".equals(purpose) ? passwords.encode(newPassword) : null;
        transaction.executeWithoutResult(status -> consumeToken(token, purpose, encoded, row));
    }

    private void consumeToken(String token, String purpose, String encoded, Map<String, Object> row) {
        // Consistent user -> token lock order also serializes two different tokens for the same account.
        AppUser user = users.lockById(((Number)row.get("user_id")).longValue())
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài khoản."));
        if (db.update("""
                UPDATE account_tokens SET used_at=? WHERE token_hash=? AND purpose=?
                    AND used_at IS NULL AND expires_at>?
                """, Timestamp.from(Instant.now()), hash(token), purpose, Timestamp.from(Instant.now())) != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Liên kết đã được sử dụng.");
        if ("CHANGE_EMAIL".equals(purpose)) {
            String target = (String) row.get("target_email");
            if (users.existsByEmail(target)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng.");
            user.changeEmail(target, Instant.now());
        } else if ("VERIFY_EMAIL".equals(purpose)) {
            user.verifyEmail(Instant.now());
            if (adminProperties.contains(user.getEmail())) {
                user.promoteToAdmin(Instant.now());
                user.revokeSessions(Instant.now());
            }
        }
        else if ("RESET_PASSWORD".equals(purpose)) user.changePassword(encoded, Instant.now());
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        db.update("UPDATE account_tokens SET used_at=? WHERE user_id=? AND purpose=? AND used_at IS NULL",
                Timestamp.from(Instant.now()), user.getId(), purpose);
        audit(user.getId(), user.getId(), purpose, null);
    }

    @Transactional(readOnly = true)
    public Page<AppUser> search(String term, String role, Boolean enabled, int page) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String query = term == null ? "" : term.strip();
        if (query.length() > 160 || role != null && !List.of("USER", "ADMIN").contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return users.search(query, role, enabled,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional
    public void adminSendReset(long actor, long id, String ip) {
        AppUser target = require(id);
        sendToken(target, "RESET_PASSWORD", null, ip);
        audit(actor, id, "SEND_PASSWORD_RESET", null);
    }

    public List<Map<String, Object>> auditFor(long id) {
        require(id);
        return db.queryForList("""
                SELECT a.action,a.detail,a.created_at,actor.email AS actor_email
                FROM account_audit a LEFT JOIN app_users actor ON actor.id=a.actor_id
                WHERE a.subject_id=? ORDER BY a.id DESC LIMIT 100
                """, id);
    }
    public AppUser adminView(long id) { return require(id); }
    @Transactional
    public void adminRevokeSessions(long actor, long id, String reason) {
        if (actor == id) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dùng mục bảo vệ tài khoản để đăng xuất các phiên của chính bạn.");
        require(id).revokeSessions(Instant.now());
        audit(actor,id,"REVOKE_SESSIONS",reason);
    }

    @Transactional
    public AppUser setEnabled(long actor, long id, boolean enabled, String reason) {
        lockAdminPolicy();
        AppUser user = require(id);
        if (actor == id && !enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể tự khóa tài khoản.");
        if (!enabled && "ADMIN".equals(user.getRole()) && users.countByRoleAndEnabledTrue("ADMIN") <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cần giữ ít nhất một quản trị viên.");
        user.setEnabled(enabled, Instant.now());
        audit(actor, id, enabled ? "ENABLE_ACCOUNT" : "DISABLE_ACCOUNT", reason);
        return user;
    }

    @Transactional
    public AppUser setRole(long actor, long id, String role) {
        lockAdminPolicy();
        AppUser user = require(id);
        if (actor == id && !"ADMIN".equals(role)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể tự hạ quyền.");
        if ("ADMIN".equals(user.getRole()) && !"ADMIN".equals(role)
                && users.countByRoleAndEnabledTrue("ADMIN") <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cần giữ ít nhất một quản trị viên.");
        user.setRole(role, Instant.now());
        audit(actor, id, "CHANGE_ROLE", role);
        return user;
    }

    private AppUser require(long id) {
        boolean writing = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return (writing ? users.lockById(id) : users.findById(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản."));
    }

    private AppUser requireUnchanged(AppUser snapshot) {
        AppUser user = require(snapshot.getId());
        if (!user.isEnabled() || user.getAuthVersion() != snapshot.getAuthVersion()
                || !user.getPasswordHash().equals(snapshot.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tài khoản đã thay đổi. Vui lòng thử lại.");
        return user;
    }

    private void lockAdminPolicy() {
        // One durable policy row serializes changes across different admin accounts and JVMs.
        db.queryForObject("SELECT id FROM business_locks WHERE id='admin-policy' FOR UPDATE", String.class);
    }

    private void audit(long actor, long subject, String action, String detail) {
        db.update("INSERT INTO account_audit(actor_id,subject_id,action,detail) VALUES (?,?,?,?)",
                actor, subject, action, detail);
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
