package com.dictalab.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DB commits are real. SMTP and process death are fault-injected, not a real mail-server certification. */
@SpringBootTest(properties={"spring.mail.host=test.invalid","dictalab.mail-from=test@dictalab.local",
        "dictalab.email.dispatch-delay-ms=600000","management.health.mail.enabled=false"})
class EmailOutboxRecoveryTest {
    @Autowired JdbcTemplate db;
    @MockitoBean JavaMailSender mail;
    @MockitoBean GeminiClient ai;
    private String token;
    private String id;

    private void pending() {
        token=UUID.randomUUID().toString(); id=UUID.randomUUID().toString();
        long user=db.queryForObject("SELECT MIN(id) FROM app_users",Long.class);
        db.update("INSERT INTO account_tokens(token_hash,user_id,purpose,created_at,expires_at) VALUES(?,?,'VERIFY_EMAIL',?,?)",
                token,user,Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(1800)));
        db.update("INSERT INTO email_outbox(id,token_hash,recipient,subject,body,available_at) VALUES(?,?,'recovery@test.local','test','private-link',?)",
                id,token,Timestamp.from(Instant.now().minusSeconds(1)));
    }

    private EmailOutbox worker(JavaMailSender sender) {
        var beans=new StaticListableBeanFactory(); beans.addBean("sender",sender);
        return new EmailOutbox(db,beans.getBeanProvider(JavaMailSender.class),"test@dictalab.local");
    }
    private String status(){return db.queryForObject("SELECT status FROM email_outbox WHERE id=?",String.class,id);}
    private void expire(){db.update("UPDATE email_outbox SET locked_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);}
    @AfterEach void cleanup(){if(token!=null) db.update("DELETE FROM account_tokens WHERE token_hash=?",token);}

    @Test void liveClaimPreventsSecondDispatcherFromSending() throws Exception {
        pending();var firstSender=mock(JavaMailSender.class);var secondSender=mock(JavaMailSender.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return null;})
                .when(firstSender).send(any(SimpleMailMessage.class));
        var pool=Executors.newSingleThreadExecutor();
        try{
            var running=pool.submit(()->worker(firstSender).deliver());
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            worker(secondSender).deliver();
            verify(secondSender,never()).send(any(SimpleMailMessage.class));
            release.countDown();running.get(5,TimeUnit.SECONDS);
            assertThat(status()).isEqualTo("DELIVERED");
            assertThat(db.queryForObject("SELECT attempts FROM email_outbox WHERE id=?",Integer.class,id)).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void crashAfterSmtpAcceptanceLeavesDurableClaimAndReplacementReclaimsIt() {
        pending();var crashed=mock(JavaMailSender.class);
        doThrow(new AssertionError("simulated process death after SMTP accept")).when(crashed).send(any(SimpleMailMessage.class));
        assertThatThrownBy(()->worker(crashed).deliver()).isInstanceOf(AssertionError.class);
        assertThat(status()).isEqualTo("RUNNING");
        var restarted=mock(JavaMailSender.class);
        worker(restarted).deliver();verify(restarted,never()).send(any(SimpleMailMessage.class));
        expire();worker(restarted).deliver();worker(restarted).deliver();
        verify(restarted,times(1)).send(any(SimpleMailMessage.class));
        assertThat(status()).isEqualTo("DELIVERED");
        assertThat(db.queryForObject("SELECT attempts FROM email_outbox WHERE id=?",Integer.class,id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT body FROM email_outbox WHERE id=?",String.class,id)).isEmpty();
    }

    @Test void expiredWorkerMustNotCommitEvenWithoutReplacement() {
        pending();var sender=mock(JavaMailSender.class);
        doAnswer(call->{expire();return null;}).when(sender).send(any(SimpleMailMessage.class));
        worker(sender).deliver();
        assertThat(status()).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT body FROM email_outbox WHERE id=?",String.class,id)).isEqualTo("private-link");
    }

    @Test void staleFailureCannotOverwriteReplacementDelivery() {
        pending();var stale=mock(JavaMailSender.class);var replacement=mock(JavaMailSender.class);
        doAnswer(call->{expire();worker(replacement).deliver();throw new MailSendException("private-provider-detail");})
                .when(stale).send(any(SimpleMailMessage.class));
        worker(stale).deliver();
        assertThat(status()).isEqualTo("DELIVERED");
        assertThat(db.queryForObject("SELECT last_error FROM email_outbox WHERE id=?",String.class,id)).isNull();
    }

    @Test void authenticationFailureIsTerminalNotRetried() {
        pending();var sender=mock(JavaMailSender.class);
        doThrow(new MailAuthenticationException("private-password")).when(sender).send(any(SimpleMailMessage.class));
        worker(sender).deliver();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT body FROM email_outbox WHERE id=?",String.class,id)).isEmpty();
        assertThat(db.queryForObject("SELECT last_error FROM email_outbox WHERE id=?",String.class,id)).doesNotContain("private-password");
    }

    @Test void transientFailureHasBoundedBackoffAndNoImmediateRetry() {
        pending();var sender=mock(JavaMailSender.class);
        doThrow(new MailSendException("private-detail")).when(sender).send(any(SimpleMailMessage.class));
        Instant before=Instant.now();worker(sender).deliver();worker(sender).deliver();
        verify(sender,times(1)).send(any(SimpleMailMessage.class));
        assertThat(status()).isEqualTo("PENDING");
        Instant next=db.queryForObject("SELECT available_at FROM email_outbox WHERE id=?",Timestamp.class,id).toInstant();
        assertThat(next).isBetween(before.plusSeconds(15),Instant.now().plusSeconds(30));
    }

    @Test void expiredTokenIsNotSentBetweenPeriodicCleanupSweeps() {
        pending(); var sender = mock(JavaMailSender.class); var worker = worker(sender);
        db.update("UPDATE email_outbox SET available_at=? WHERE id=?", Timestamp.from(Instant.now().plusSeconds(60)), id);
        worker.dispatch();
        db.update("UPDATE account_tokens SET created_at=?,expires_at=? WHERE token_hash=?",
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(1)), token);
        db.update("UPDATE email_outbox SET available_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), id);
        worker.wakeAfterCommit(); worker.dispatch();
        verify(sender, never()).send(any(SimpleMailMessage.class));
        worker.deliver();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT body FROM email_outbox WHERE id=?", String.class, id)).isEmpty();
    }

    @Test void exhaustedCrashIsTerminalAndConsumedTokenNeverSends() {
        pending();var sender=mock(JavaMailSender.class);
        db.update("UPDATE email_outbox SET status='RUNNING',attempts=5,lease_id='dead' WHERE id=?",id);
        expire();worker(sender).deliver();
        assertThat(status()).isEqualTo("FAILED");
        db.update("UPDATE email_outbox SET status='PENDING',attempts=0,body='private-link' WHERE id=?",id);
        db.update("UPDATE account_tokens SET used_at=? WHERE token_hash=?",Timestamp.from(Instant.now()),token);
        worker(sender).deliver();verify(sender,never()).send(any(SimpleMailMessage.class));
        assertThat(status()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT body FROM email_outbox WHERE id=?",String.class,id)).isEmpty();
    }

    @Test @org.junit.jupiter.api.Timeout(60)
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="dictalab.race-tests",matches="true")
    void realChildProcessDeathAndRestartPreserveTheCommittedLease() throws Exception {
        String url;
        try(var connection=db.getDataSource().getConnection()){
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            url=connection.getMetaData().getURL();
        }
        assertThat(db.queryForObject("SELECT current_database()",String.class)).matches("dictalab_test(?:_[a-f0-9]{32})?");
        pending();db.update("UPDATE email_outbox SET subject=? WHERE id=?",id,id);
        var folder=java.nio.file.Path.of(System.getProperty("dictalab.build.directory","target"),"worker-recovery-"+id);
        java.nio.file.Files.createDirectories(folder);
        Process child=null;
        try{
            var log=folder.resolve("crashed.log");child=processWorker(url,"crash",log);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline && child.isAlive() &&
                    (!java.nio.file.Files.exists(log) || !java.nio.file.Files.readString(log).contains("SIMULATED_SMTP_ACCEPTED")))Thread.sleep(30);
            assertThat(java.nio.file.Files.readString(log)).contains("SIMULATED_SMTP_ACCEPTED");
            assertThat(status()).isEqualTo("RUNNING");
            child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();
            child=processWorker(url,"restart",folder.resolve("before-expiry.log"));
            assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
            assertThat(status()).isEqualTo("RUNNING");
            assertThat(db.queryForObject("SELECT attempts FROM email_outbox WHERE id=?",Integer.class,id)).isEqualTo(1);
            expire(); // Accelerate the lease clock, not a replay bypass. Production expiry remains 90 seconds.
            child=processWorker(url,"restart",folder.resolve("after-expiry.log"));
            assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isZero();
            assertThat(status()).isEqualTo("DELIVERED");
            assertThat(db.queryForObject("SELECT attempts FROM email_outbox WHERE id=?",Integer.class,id)).isEqualTo(2);
            assertThat(java.nio.file.Files.readString(folder.resolve("before-expiry.log"))).doesNotContain("SIMULATED_SMTP_ACCEPTED");
            assertThat(java.nio.file.Files.readString(folder.resolve("after-expiry.log")).split("SIMULATED_SMTP_ACCEPTED",-1)).hasSize(2);
            assertThat(db.queryForObject("SELECT body FROM email_outbox WHERE id=?",String.class,id)).isEmpty();
        }finally{if(child!=null && child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
    }

    private Process processWorker(String url,String mode,java.nio.file.Path log)throws Exception{
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",executable).toString();
        var process=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                ProcessWorker.class.getName(),mode,id).redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().put("RECOVERY_DB_URL",url);
        process.environment().put("RECOVERY_DB_USER",db.queryForObject("SELECT current_user",String.class));
        process.environment().put("RECOVERY_DB_PASSWORD",System.getProperty("spring.datasource.password",""));
        return process.start();
    }

    public static class ProcessWorker {
        public static void main(String[] args)throws Exception{
            var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(System.getenv("RECOVERY_DB_URL"),
                    System.getenv("RECOVERY_DB_USER"),System.getenv("RECOVERY_DB_PASSWORD"));
            var db=new JdbcTemplate(source);
            if(!db.queryForObject("SELECT current_database()",String.class).matches("dictalab_test(?:_[a-f0-9]{32})?"))
                throw new IllegalStateException("Isolated PostgreSQL required");
            var sender=new org.springframework.mail.javamail.JavaMailSenderImpl(){
                @Override public void send(SimpleMailMessage message){
                    if(!args[1].equals(message.getSubject()))return;
                    System.out.println("SIMULATED_SMTP_ACCEPTED");System.out.flush();
                    if(args[0].equals("crash")){
                        try{new CountDownLatch(1).await(45,TimeUnit.SECONDS);}
                        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    }
                }
            };
            var beans=new StaticListableBeanFactory();beans.addBean("sender",sender);
            new EmailOutbox(db,beans.getBeanProvider(JavaMailSender.class),"test@dictalab.local").deliver();
        }
    }
}
