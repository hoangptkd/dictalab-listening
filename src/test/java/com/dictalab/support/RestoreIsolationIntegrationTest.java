package com.dictalab.support;

import com.dictalab.DictaLabApplication;
import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.JwtTokenService;
import com.dictalab.service.AdminContentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Actual pg_dump/restore + real app/Redis restart. External media/providers stay disabled. */
@EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
@SpringBootTest
class RestoreIsolationIntegrationTest {
    private static final Path evidence=Path.of(System.getProperty("dictalab.build.directory","target"),"restore-isolation");
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokenService tokens;
    @Autowired com.dictalab.security.RedisTokenStore tokenStore;
    @Autowired AdminContentService admin;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.context.ApplicationContext context;

    @BeforeAll static void compileShippedChecker() throws Exception {
        Files.createDirectories(evidence);
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Reliability CI must run on a JDK").isNotNull();
        assertThat(compiler.run(null,null,null,"--release","17","-d",evidence.toString(),"scripts/load/RedisIsolationCheck.java")).isZero();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    }

    private record Check(int exit,String output) {}
    private Check check(String url,String production,String mode) throws Exception {
        var log=evidence.resolve("checker-"+UUID.randomUUID()+".log");
        var builder=new ProcessBuilder(javaExecutable(),"-cp",evidence.toString(),"RedisIsolationCheck",mode)
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("RESTORE_REDIS_URL",url);
        builder.environment().put("REDIS_URL",production);
        for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"))builder.environment().remove(key);
        var child=builder.start();
        try {
            assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();
            return new Check(child.exitValue(),Files.readString(log));
        } finally {if(child.isAlive())child.destroyForcibly();}
    }

    private String sharedRedis(int database) {
        return "redis://"+System.getProperty("TEST_REDIS_HOST")+":"+System.getProperty("TEST_REDIS_PORT")+"/"+database;
    }

    @Test void emptyRedisPreflightIsReadOnly() throws Exception {
        var result=check(sharedRedis(12),"","--restore");
        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("RESTORE_REDIS_EMPTY=TRUE");
    }

    @Test void dirtyRedisIsRejectedWithoutDeletingAnyKey() throws Exception {
        var standalone=new org.springframework.data.redis.connection.RedisStandaloneConfiguration(System.getProperty("TEST_REDIS_HOST"),Integer.parseInt(System.getProperty("TEST_REDIS_PORT")));
        standalone.setDatabase(12);
        var factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(standalone);
        factory.afterPropertiesSet();factory.start();
        var redis=new org.springframework.data.redis.core.StringRedisTemplate(factory);
        String marker="restore-test-"+UUID.randomUUID();
        try {
            redis.opsForValue().set(marker,"must-survive",Duration.ofMinutes(5));
            var result=check(sharedRedis(12),"","--restore");
            assertThat(result.exit()).isEqualTo(1);
            assertThat(result.output()).contains("RESTORE_REDIS_CHECK_FAILED=");
            assertThat(redis.opsForValue().get(marker)).isEqualTo("must-survive");
        } finally {redis.delete(marker);factory.destroy();}
    }

    @Test void knownProductionEndpointIsRejectedEvenWithAnotherLogicalDatabase() throws Exception {
        var result=check(sharedRedis(12),sharedRedis(0),"--restore");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("IllegalArgumentException").doesNotContain("EMPTY=TRUE");
    }

    @Test void invalidOrUnencryptedRemoteEndpointNeverLeaksCredentials() throws Exception {
        for(String url:List.of("redis://fixture:do-not-log@example.invalid/0","rediss://fixture:do-not-log@example.invalid/0?unsafe=1")) {
            var result=check(url,"","--restore");
            assertThat(result.exit()).isEqualTo(1);
            assertThat(result.output()).contains("IllegalArgumentException").doesNotContain("do-not-log","example.invalid");
        }
        assertThat(check("","","--self-test").exit()).isZero();
    }

    @Test void passwordOnlyAndAclCredentialsPreserveLiteralOrEncodedPlus() throws Exception {
        try(var redis=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379)
                .withCommand("redis-server","--requirepass","fixture+password")) {
            redis.start();
            String endpoint=redis.getHost()+":"+redis.getMappedPort(6379)+"/0";
            for(String credentials:List.of(":fixture+password","default:fixture%2Bpassword")) {
                var result=check("redis://"+credentials+"@"+endpoint,sharedRedis(0),"--restore");
                assertThat(result.exit()).as("Authenticated empty restore endpoint").isZero();
                assertThat(result.output()).contains("RESTORE_REDIS_EMPTY=TRUE").doesNotContain("fixture");
            }
        }
    }

    @Test @Timeout(300)
    void restoredDatabaseAndRestartedRedisCannotResurrectOldAccessOrRefresh() throws Exception {
        assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class))
                .as("Normal application profiles must retain background scheduling").isNotEmpty();
        var postgres=ReliabilityContainers.disposablePostgres();
        assertThat(db.queryForObject("SELECT current_database()",String.class)).isEqualTo("dictalab_test");
        String suffix=UUID.randomUUID().toString().replace("-","");
        String restoredName="dictalab_restore_"+suffix;
        assertThat(restoredName).matches("dictalab_restore_[a-f0-9]{32}");
        String dump="/tmp/"+restoredName+".dump";
        var folder=evidence.resolve(suffix);Files.createDirectories(folder);
        var user=users.saveAndFlush(new AppUser("restore-"+suffix+"@test.local","Restore learner",passwords.encode("listen123"),Instant.now()));
        var issued=new MockHttpServletResponse();tokens.issue(user,issued);
        String oldAccess=issued.getCookie(JwtTokenService.ACCESS_COOKIE).getValue();
        String oldRefresh=issued.getCookie(JwtTokenService.REFRESH_COOKIE).getValue();
        String oldSid=tokens.decode(oldAccess).getClaimAsString("sid");
        var sourceSession=tokenStore.session(oldSid);
        var lesson=admin.copyLesson(1L);
        db.update("UPDATE lessons SET active=TRUE WHERE id=?",lesson.id());
        String audio="/media/s3/"+UUID.randomUUID()+".mp3";
        db.update("UPDATE lesson_sentences SET audio_url=? WHERE id=?",audio,lesson.sentences().get(0).id());
        db.update("INSERT INTO s3_media_lifecycle(audio_url,storage_identity,status) VALUES (?,'fixture-bucket/dictalab/audio/','LIVE')",audio);
        String practice=UUID.randomUUID().toString(),job=UUID.randomUUID().toString();
        db.update("INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at) VALUES (?,?,?,?,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",practice,"restore-fixture",user.getId(),lesson.id());
        db.update("INSERT INTO ai_background_jobs(id,job_key,kind,practice_id,status,attempts,lease_id,locked_until) VALUES (?,?,'COACH',?,'RUNNING',1,?,CURRENT_TIMESTAMP-INTERVAL '1' SECOND)",job,"coach/"+practice,practice,UUID.randomUUID().toString());
        boolean created=false;Process child=null;
        try(var redis=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379).withCommand("redis-server","--save","","--appendonly","no")) {
            redis.start();
            assertThat(postgres.execInContainer("pg_dump","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"-Fc","-f",dump).getExitCode()).isZero();
            db.update("UPDATE app_users SET auth_version=auth_version+1 WHERE id=?",user.getId());
            assertThatThrownBy(()->tokens.authentication(tokens.decode(oldAccess))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            db.execute("CREATE DATABASE "+restoredName);created=true;
            assertThat(postgres.execInContainer("pg_restore","-U",postgres.getUsername(),"-d",restoredName,"--exit-on-error",dump).getExitCode()).isZero();
            String restoredUrl=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+restoredName);
            var restored=new JdbcTemplate(new DriverManagerDataSource(restoredUrl,postgres.getUsername(),postgres.getPassword()));
            assertThat(restored.queryForObject("SELECT auth_version FROM app_users WHERE id=?",Long.class,user.getId())).isZero();
            assertThat(restored.queryForObject("SELECT audio_url FROM lesson_sentences WHERE id=?",String.class,lesson.sentences().get(0).id())).isEqualTo(audio);
            String signing=java.util.Base64.getEncoder().encodeToString(new byte[64]); // Isolated test-only key; never production.
            String redisUrl="redis://"+redis.getHost()+":"+redis.getMappedPort(6379)+"/0";
            assertThat(check(redisUrl,sharedRedis(0),"--restore").exit()).isZero();
            String configuration=configuration(restoredUrl,postgres.getUsername(),postgres.getPassword(),redisUrl,signing,folder);
            var log=folder.resolve("restored-app.log");child=startApp(configuration,log);
            String base=awaitApp(child,log,restoredName);
            var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
            assertThat(request(browser,base,"/api/auth/me","GET",null,null,JwtTokenService.ACCESS_COOKIE+"="+oldAccess).statusCode()).isEqualTo(401);
            var csrf=json.readTree(request(browser,base,"/api/auth/csrf","GET",null,null,null).body());
            assertThat(request(browser,base,"/api/auth/refresh","POST","",csrf,JwtTokenService.REFRESH_COOKIE+"="+oldRefresh).statusCode()).isEqualTo(401);
            login(browser,base,user.getEmail());
            assertThat(request(browser,base,"/api/auth/me","GET",null,null,null).statusCode()).isEqualTo(200);
            var headers=json.readTree(request(browser,base,"/api/auth/csrf","GET",null,null,null).body());
            var started=request(browser,base,"/api/v1/practice-sessions","POST","{\"lessonId\":"+lesson.id()+"}",headers,null);
            assertThat(started.statusCode()).as("Practice response: %s",started.body()).isEqualTo(200);
            assertThat(json.readTree(started.body()).path("sessionId").asText()).isNotBlank();
            stopApp(child);child=null;
            var docker=org.testcontainers.DockerClientFactory.instance().client();
            docker.killContainerCmd(redis.getContainerId()).withSignal("KILL").exec();
            docker.startContainerCmd(redis.getContainerId()).exec();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(()->redis.execInContainer("redis-cli","PING").getStdout().contains("PONG"));
            var bindings=docker.inspectContainerCmd(redis.getContainerId()).exec().getNetworkSettings().getPorts();
            int port=Integer.parseInt(bindings.getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0].getHostPortSpec());
            redisUrl="redis://"+redis.getHost()+":"+port+"/0";
            assertThat(check(redisUrl,sharedRedis(0),"--restore").exit()).isZero();
            configuration=configuration(restoredUrl,postgres.getUsername(),postgres.getPassword(),redisUrl,signing,folder);
            log=folder.resolve("after-redis-loss.log");child=startApp(configuration,log);
            base=awaitApp(child,log,restoredName);
            assertThat(request(browser,base,"/api/auth/me","GET",null,null,null).statusCode()).isEqualTo(401);
            csrf=json.readTree(request(browser,base,"/api/auth/csrf","GET",null,null,null).body());
            assertThat(request(browser,base,"/api/auth/refresh","POST","",csrf,null).statusCode()).isEqualTo(401);
            login(browser,base,user.getEmail());
            assertThat(request(browser,base,"/api/auth/me","GET",null,null,null).statusCode()).isEqualTo(200);
            assertThat(restored.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,job)).isEqualTo("RUNNING");
            assertThat(restored.queryForObject("SELECT status FROM s3_media_lifecycle WHERE audio_url=?",String.class,audio)).isEqualTo("LIVE");
            assertThat(db.queryForObject("SELECT auth_version FROM app_users WHERE id=?",Long.class,user.getId())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM practice_sessions WHERE user_id=?",Integer.class,user.getId())).isEqualTo(1);
            assertThat(tokenStore.session(oldSid)).as("Restored app must not consume or revoke the source refresh family").isEqualTo(sourceSession);
            Files.writeString(folder.resolve("result.txt"),"PG_DUMP_RESTORE=OK\nOLD_ACCESS_REFRESH_REJECTED=TRUE\nREDIS_SIGKILL_RESTART=OK\nNEW_LOGIN_PRACTICE=OK\nSOURCE_DB_UNCHANGED_BY_SMOKE=TRUE\nREMOTE_JOBS_DISABLED=TRUE\n");
        } finally {
            if(child!=null)stopApp(child);
            if(created)db.execute("DROP DATABASE "+restoredName);
            tokenStore.revoke(oldSid);
            db.update("DELETE FROM practice_sessions WHERE id=?",practice);
            db.update("DELETE FROM app_users WHERE id=?",user.getId());
            db.update("DELETE FROM lessons WHERE id=?",lesson.id());
            db.update("DELETE FROM s3_media_lifecycle WHERE audio_url=?",audio);
        }
    }

    @Test @Timeout(60)
    void s3RecoveryGateRejectsLegacyReferencesAndActiveCatalogWithoutMutatingThem() throws Exception {
        var postgres=ReliabilityContainers.disposablePostgres();
        assertThat(db.queryForObject("SELECT current_database()",String.class)).isEqualTo("dictalab_test");
        String database="dictalab_restore_gate_"+UUID.randomUUID().toString().replace("-","");
        assertThat(database).matches("dictalab_restore_gate_[a-f0-9]{32}");
        var matcher=java.util.regex.Pattern.compile("(?s)function Get-S3RecoveryGateSql \\{.*?@'\\R(.*?)\\R'@")
                .matcher(Files.readString(Path.of("scripts/restore-drill-windows-vps.ps1")));
        assertThat(matcher.find()).as("Shipped S3 read-only gate SQL").isTrue();
        String sql=matcher.group(1);
        db.execute("CREATE DATABASE "+database);
        try {
            var fixture=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl().replace("/dictalab_test","/"+database),
                    postgres.getUsername(),postgres.getPassword()));
            assertThat(fixture.queryForObject("SELECT current_database()",String.class)).isEqualTo(database);
            fixture.execute("CREATE TABLE lesson_sentences(audio_url text); CREATE TABLE listening_units(audio_url text)");
            fixture.update("INSERT INTO lesson_sentences VALUES ('/audio/local-fixture.mp3')");
            assertThat(s3Gate(database,sql).exit()).as("Pre-V27 local-only database").isZero();
            String audio="/media/s3/"+UUID.randomUUID()+".mp3";
            for(String table:List.of("lesson_sentences","listening_units")) {
                for(String url:List.of(audio,"/media/s3/malformed-reference")) {
                    fixture.update("INSERT INTO "+table+" VALUES (?)",url);
                    var rejected=s3Gate(database,sql);
                    assertThat(rejected.exit()).as("Legacy/malformed S3 reference in %s",table).isNotZero();
                    assertThat(rejected.output()).doesNotContain("RESTORE_S3_DEPENDENCIES_NONE",url);
                    assertThat(fixture.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE audio_url=?",Integer.class,url)).isEqualTo(1);
                    fixture.update("DELETE FROM "+table+" WHERE audio_url=?",url);
                }
            }
            fixture.execute(Files.readString(Path.of("src/main/resources/db/migration/V27__s3_media_lifecycle.sql")));
            for(String status:List.of("STAGED","LIVE","DELETING")) {
                fixture.update("INSERT INTO s3_media_lifecycle(audio_url,storage_identity,status) VALUES (?,'fixture/only/',?)",audio,status);
                assertThat(s3Gate(database,sql).exit()).as("Unverified catalog state %s",status).isNotZero();
                assertThat(fixture.queryForObject("SELECT status FROM s3_media_lifecycle WHERE audio_url=?",String.class,audio)).isEqualTo(status);
                assertThat(fixture.queryForObject("SELECT attempts FROM s3_media_lifecycle WHERE audio_url=?",Integer.class,audio)).isZero();
                fixture.update("DELETE FROM s3_media_lifecycle WHERE audio_url=?",audio);
            }
            fixture.update("INSERT INTO s3_media_lifecycle(audio_url,storage_identity,status) VALUES (?,'fixture/only/','DELETED')",audio);
            var tombstoneOnly=s3Gate(database,sql);
            assertThat(tombstoneOnly.exit()).isZero();
            assertThat(tombstoneOnly.output()).contains("RESTORE_S3_DEPENDENCIES_NONE");
            fixture.update("INSERT INTO listening_units VALUES (?)",audio);
            assertThat(s3Gate(database,sql).exit()).as("A tombstone cannot hide a current reference").isNotZero();
            assertThat(fixture.queryForObject("SELECT status FROM s3_media_lifecycle WHERE audio_url=?",String.class,audio)).isEqualTo("DELETED");
            fixture.execute("DROP TABLE listening_units");
            assertThat(s3Gate(database,sql).exit()).as("Missing required table must fail closed").isNotZero();
        } finally {db.execute("DROP DATABASE "+database);}
    }

    private Check s3Gate(String database,String sql) throws Exception {
        var result=ReliabilityContainers.disposablePostgres().execInContainer("psql","-X","-q","-U","dictalab_test",
                "-d",database,"-v","ON_ERROR_STOP=1","-tAc",sql);
        Files.writeString(evidence.resolve("s3-gate-"+UUID.randomUUID()+".log"),
                "exit="+result.getExitCode()+"\n"+result.getStdout()+result.getStderr());
        System.out.println("S3_RECOVERY_GATE_FIXTURE exit="+result.getExitCode());
        return new Check(result.getExitCode(),result.getStdout()+result.getStderr());
    }

    private HttpResponse<String> request(HttpClient client,String base,String path,String method,String body,tools.jackson.databind.JsonNode csrf,String cookie) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(csrf!=null)builder.header(csrf.path("headerName").asText(),csrf.path("token").asText());
        if(cookie!=null)builder.header("Cookie",cookie);
        if(body!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void login(HttpClient browser,String base,String email) throws Exception {
        var csrf=json.readTree(request(browser,base,"/api/auth/csrf","GET",null,null,null).body());
        assertThat(request(browser,base,"/api/auth/login","POST","{\"email\":\""+email+"\",\"password\":\"listen123\"}",csrf,null).statusCode()).isEqualTo(200);
    }
    private String configuration(String url,String user,String password,String redis,String signing,Path folder) throws Exception {
        String source=Path.of("scripts/restore-drill-windows-vps.ps1").toAbsolutePath().toString().replace("'","''");
        String command="$errors=$null;$tokens=$null;$ast=[Management.Automation.Language.Parser]::ParseFile('"+source+"',[ref]$tokens,[ref]$errors);if($errors.Count){throw 'Parse failed'};$function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'New-RestoreSmokeConfiguration'},$true);Invoke-Expression $function.Extent.Text;New-RestoreSmokeConfiguration -DatabaseUrl '"+url+"' -DatabaseUser '"+user+"' -DatabasePassword '"+password+"' -RedisUrl '"+redis+"' -SigningKey '"+signing+"' -MediaRoot '"+folder.resolve("media").toAbsolutePath()+"' -Port 0 -ManagementPort 0";
        var shell=new ProcessBuilder(System.getProperty("os.name").startsWith("Windows")?"powershell.exe":"pwsh","-NoProfile","-NonInteractive","-Command",command).start();
        try {assertThat(shell.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(shell.exitValue()).isZero();return new String(shell.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip().replace("\uFEFF","");}
        finally {if(shell.isAlive())shell.destroyForcibly();}
    }
    private Process startApp(String configuration,Path log) throws Exception {
        String mainClasses=Path.of(DictaLabApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var builder=new ProcessBuilder(javaExecutable(),"-Xmx256m","-cp",mainClasses+java.io.File.pathSeparator+System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),RestoredApp.class.getName(),
                "--logging.file.name="+log.toAbsolutePath()+".json",
                "--dictalab.auth.previous-signing-key=","--dictalab.mail-from=","--dictalab.media.s3.enabled=false","--dictalab.media.s3.cleanup-enabled=false","--dictalab.ai.queue.enabled=false","--dictalab.listening.queue.enabled=false")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"))builder.environment().remove(key);
        builder.environment().put("SPRING_APPLICATION_JSON",configuration);
        builder.environment().put("SPRING_DATASOURCE_URL",System.getProperty("spring.datasource.url"));
        builder.environment().put("SPRING_DATASOURCE_HIKARI_JDBC_URL",System.getProperty("spring.datasource.url"));
        builder.environment().put("SPRING_DATA_REDIS_URL",sharedRedis(0));
        builder.environment().put("JWT_PREVIOUS_SIGNING_KEY","dGVzdC1vbmx5LWRpY3RhbGFiLXNpZ25pbmcta2V5LTMyaGFyYWN0ZXJz");
        builder.environment().put("JWT_SIGNING_KEY","dGVzdC1vbmx5LWRpY3RhbGFiLXNpZ25pbmcta2V5LTMyaGFyYWN0ZXJz");
        builder.environment().put("DICTALAB_MEDIA_S3_ENABLED","true");
        builder.environment().put("DICTALAB_AI_QUEUE_ENABLED","true");
        return builder.start();
    }
    private String awaitApp(Process child,Path log,String database) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(120)).until(()->!child.isAlive() || Files.readString(log).contains("RESTORE_SMOKE_PORT="));
        assertThat(child.isAlive()).as("Restore child startup failed; see %s",log).isTrue();
        try {
            String output=Files.readString(log);assertThat(output).contains("RESTORE_SMOKE_DB="+database,"RESTORE_REMOTE_DISABLED=TRUE");
            var port=java.util.regex.Pattern.compile("RESTORE_SMOKE_PORT=(\\d+)").matcher(output);assertThat(port.find()).isTrue();
            return "http://127.0.0.1:"+port.group(1);
        } catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    private static void stopApp(Process child) throws Exception {
        if(child.isAlive()){child.destroy();if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
    }
    public static class RestoredApp {
        public static void main(String[] args) {
            var context=SpringApplication.run(DictaLabApplication.class,args);
            var env=context.getEnvironment();
            for(String property:List.of("dictalab.media.s3.enabled","dictalab.media.s3.cleanup-enabled","dictalab.ai.queue.enabled","dictalab.listening.queue.enabled"))
                if(!"false".equals(env.getProperty(property)))throw new IllegalStateException("Remote isolation failed");
            if(!"".equals(env.getProperty("dictalab.auth.previous-signing-key")) || !"".equals(env.getProperty("dictalab.mail-from")))throw new IllegalStateException("Key/mail isolation failed");
            if(!context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).isEmpty())
                throw new IllegalStateException("Restore must not run background schedulers");
            System.out.println("RESTORE_SMOKE_DB="+context.getBean(JdbcTemplate.class).queryForObject("SELECT current_database()",String.class));
            System.out.println("RESTORE_REMOTE_DISABLED=TRUE");
            System.out.println("RESTORE_SMOKE_PORT="+((WebServerApplicationContext)context).getWebServer().getPort());
        }
    }
}
