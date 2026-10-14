package com.dictalab.web;

import com.dictalab.domain.*;
import com.dictalab.dto.*;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.*;
import com.dictalab.room.RoomRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real commits + PostgreSQL lock witnesses. Not enabled against H2 or an arbitrary database. */
@EnabledIfSystemProperty(named="dictalab.race-tests",matches="true")
@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=30",
        "dictalab.listening.dispatch-delay-ms=600000","dictalab.email.dispatch-delay-ms=600000"})
class ConcurrencyMatrixTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired PracticeService practice;
    @Autowired AccountService registration;
    @Autowired AccountManagementService accounts;
    @Autowired AdminContentService content;
    @Autowired AdminContentController admin;
    @Autowired ListeningComprehensionService listening;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AppUserRepository users;
    @MockitoBean ShopAiKeyClient ai;
    final int repeats=Integer.getInteger("dictalab.race-repeats",2);
    String caseName;

    @BeforeEach void isolatedDatabase(TestInfo info) throws Exception {
        caseName=info.getTestMethod().orElseThrow().getName();
        try(var connection=db.getDataSource().getConnection()){
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(db.queryForObject("SELECT current_database()",String.class)).matches("dictalab_test(?:_[a-f0-9]{32})?");
        assertThat(repeats).isBetween(1,5);
    }
    private long user(){return users.saveAndFlush(new AppUser("matrix-"+UUID.randomUUID()+"@test.local","Race learner","unused",Instant.now())).getId();}
    private long lesson(){return db.queryForObject("SELECT id FROM lessons WHERE active AND content_origin<>'BRITISH_COUNCIL_PERSONAL' ORDER BY id LIMIT 1",Long.class);}

    private <T> List<T> race(int n,String lockSql,Object[] parameters,Callable<T> call) throws Exception {
        var pool=Executors.newFixedThreadPool(n);
        var ready=new CyclicBarrier(n);
        var futures=new ArrayList<Future<T>>();
        long began=System.nanoTime();
        try {
            Runnable launch=()->{for(int i=0;i<n;i++)futures.add(pool.submit(()->{ready.await(15,TimeUnit.SECONDS);return call.call();}));};
            if(lockSql==null)launch.run();
            else new TransactionTemplate(manager).execute(status->{
                db.queryForList(lockSql,parameters);launch.run();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                int blocked=0;
                do {
                    db.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class);
                    if(blocked>=n)break;
                    try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                }while(System.nanoTime()<deadline);
                assertThat(blocked).as("All contenders reached a real PostgreSQL lock").isGreaterThanOrEqualTo(n);
                return null; // Commit the held row; contenders now race at the actual contested resource.
            });
            var result=new ArrayList<T>();
            for(var future:futures)result.add(future.get(30,TimeUnit.SECONDS));
            Path output=Path.of(System.getProperty("dictalab.race-evidence","target/concurrency-matrix.csv"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output,Instant.now()+","+caseName+","+n+","+(lockSql==null?"barrier":"postgres-lock-witness")+","+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            return result;
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    private <T> List<T> locked(int n,String table,Object id,Callable<T> call)throws Exception{
        return race(n,"SELECT id FROM "+table+" WHERE id=? FOR UPDATE",new Object[]{id},call);
    }

    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void normalizedRegistrationAtTheReadInsertBoundary(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            String email="matrix-registration-"+UUID.randomUUID()+"@test.local";
            var boundary=new CyclicBarrier(n);
            doAnswer(invocation->{boolean exists=db.queryForObject("SELECT count(*)>0 FROM app_users WHERE email=?",Boolean.class,email);
                boundary.await(15,TimeUnit.SECONDS);return exists;}).when(users).existsByEmail(email);
            try{
                var results=race(n,null,null,()->{
                    try{registration.register(new AuthDtos.RegisterRequest("Race learner",email.toUpperCase(Locale.ROOT),"Matrix-password-2026"));return 1;}
                    catch(com.dictalab.exception.EmailAlreadyExistsException duplicate){return 0;}
                });
                assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
                assertThat(db.queryForObject("SELECT count(*) FROM app_users WHERE email=?",Integer.class,email)).isEqualTo(1);
            }finally{reset(users);} // Restore the spy's delegate; JPA repository methods are abstract.
        }
    }
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void startResumeHasExactlyOneCommittedAggregate(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            long owner=user(),lesson=lesson();
            var ids=locked(n,"app_users",owner,()->practice.start(owner,new ApiDtos.StartSessionRequest(lesson)).sessionId());
            assertThat(new HashSet<>(ids)).hasSize(1);
            assertThat(db.queryForObject("SELECT count(*) FROM practice_sessions WHERE user_id=? AND lesson_id=? AND status='IN_PROGRESS' AND room_id IS NULL",Integer.class,owner,lesson)).isEqualTo(1);
            assertThat(practice.start(owner,new ApiDtos.StartSessionRequest(lesson)).sessionId()).isEqualTo(ids.get(0)); // Lost response/resume.
        }
    }
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void checkHintCompletePreserveCountersAndRejectLateDuplicates(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            long owner=user();var started=practice.start(owner,new ApiDtos.StartSessionRequest(lesson()));
            String id=started.sessionId();long sentence=started.sentence().sentenceId();
            practice.replay(owner,id,sentence,new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
            int index=started.sentence().words().stream().filter(word->!word.prefilled()).findFirst().orElseThrow().index();
            locked(n,"practice_sessions",id,()->{practice.checkWord(owner,id,sentence,index,new ApiDtos.CheckWordRequest("wrong"));return 1;});
            assertThat(db.queryForObject("SELECT attempt_count FROM word_attempts w JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=? AND w.word_index=?",Integer.class,id,index)).isEqualTo(n);
            assertThat(db.queryForObject("SELECT count(*) FROM word_attempt_events e JOIN word_attempts w ON w.id=e.word_attempt_id JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=? AND w.word_index=?",Integer.class,id,index)).isEqualTo(n);
            locked(n,"practice_sessions",id,()->{practice.useHint(owner,id,sentence,new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE,null));return 1;});
            assertThat(db.queryForObject("SELECT reveal_count FROM practice_sessions WHERE id=?",Integer.class,id)).isEqualTo(1);
            practice.replay(owner,id,sentence,new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED));
            var finished=locked(n,"practice_sessions",id,()->{try{practice.completeSentence(owner,id,sentence,new ApiDtos.CompleteSentenceRequest(5));return 1;}
                catch(com.dictalab.exception.InvalidPracticeStateException expected){return 0;}});
            assertThat(finished.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT time_spent_seconds FROM sentence_attempts WHERE practice_session_id=? AND sentence_id=?",Integer.class,id,sentence)).isEqualTo(5);
            assertThatThrownBy(()->practice.current(user(),id)).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
        }
    }
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void accountTokenConsumeIsAtomicAndAuditedOnce(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            long owner=user();String secret=UUID.randomUUID().toString();
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
            db.update("INSERT INTO account_tokens(token_hash,user_id,purpose,created_at,expires_at) VALUES(?,?,'VERIFY_EMAIL',?,?)",hash,owner,Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(1800)));
            var results=locked(n,"app_users",owner,()->{try{accounts.consumeToken(secret,"VERIFY_EMAIL",null);return 1;}
                catch(ResponseStatusException expected){assertThat(expected.getStatusCode().value()).isEqualTo(400);return 0;}});
            assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT used_at IS NOT NULL FROM account_tokens WHERE token_hash=?",Boolean.class,hash)).isTrue();
            assertThat(db.queryForObject("SELECT count(*) FROM account_audit WHERE subject_id=? AND action='VERIFY_EMAIL'",Integer.class,owner)).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void adminStaleRevisionCommitsExactlyOneMutation(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            var actor=users.findById(user()).orElseThrow();actor.promoteToAdmin(Instant.now());users.saveAndFlush(actor);
            var created=content.createLesson(new AdminDtos.CreateLessonRequest("matrix-"+UUID.randomUUID(),"Original","Race fixture",CefrLevel.A1,"Fixture",5,500,null,null,null,null,null));
            var principal=DictaLabPrincipal.from(actor);
            String revision=content.lesson(created.id()).updatedAt().toString();
            var update=new AdminDtos.UpdateLessonRequest("Winner","Race fixture",CefrLevel.A1,"Fixture",5,500,null,null,null,null,null);
            var results=locked(n,"lessons",created.id(),()->{try{admin.updateLesson(created.id(),update,revision,principal);return 1;}
                catch(ResponseStatusException expected){assertThat(expected.getStatusCode().value()).isEqualTo(409);return 0;}});
            assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT title FROM lessons WHERE id=?",String.class,created.id())).isEqualTo("Winner");
            assertThat(db.queryForObject("SELECT count(*) FROM admin_events WHERE object_id=? AND action='UPDATE_METADATA'",Integer.class,created.id())).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void gradingClaimAndStaleWorkerFencing(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            long owner=user(),unit=db.queryForObject("SELECT id FROM listening_units WHERE published ORDER BY id LIMIT 1",Long.class);
            String id=(String)listening.start(owner,unit,"ONE_PASS",true).get("sessionId");
            db.update("UPDATE listening_passes SET grading_status='PENDING',recall_text='fixture' WHERE session_id=?",id);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            when(ai.completeJson(anyString(),anyString())).thenAnswer(invocation->{entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();
                var result=json.createObjectNode();var facts=result.putArray("facts");
                for(var fact:json.readTree(db.queryForObject("SELECT content_json FROM listening_units WHERE id=?",String.class,unit)).path("facts"))facts.addObject().put("factId",fact.path("id").asText()).put("status","CAPTURED");return result;});
            var pool=Executors.newSingleThreadExecutor();
            try{
                var worker=pool.submit(()->race(n,
                        "SELECT session_id FROM listening_passes WHERE session_id=? AND pass_number=1 FOR UPDATE",
                        new Object[]{id},()->{listening.gradeQueued(id,1,0,0);return 1;}));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                verify(ai,times(1)).completeJson(anyString(),anyString());
                db.update("UPDATE listening_passes SET grading_lease='replacement' WHERE session_id=?",id);
                release.countDown();assertThat(worker.get(20,TimeUnit.SECONDS)).hasSize(n);
                verify(ai,times(1)).completeJson(anyString(),anyString());
                assertThat(db.queryForObject("SELECT recall_score FROM listening_passes WHERE session_id=?",Double.class,id)).isNull();
                assertThat(db.queryForObject("SELECT grading_attempts FROM listening_passes WHERE session_id=?",Integer.class,id)).isEqualTo(1);
            }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();reset(ai);db.update("UPDATE listening_passes SET grading_status='FAILED' WHERE session_id=?",id);}
        }
    }
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void roomMembershipAndPlaybackAreSingleWinner(int n)throws Exception{
        for(int repeat=0;repeat<repeats;repeat++){
            var rooms=new RoomRegistry();long host=user();
            var created=race(n,null,null,()->rooms.createRoom(host,"Host"));
            assertThat(created.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            var room=rooms.findRoomForUser(host).orElseThrow();long guest=user();
            rooms.joinRoom(room.getRoomId(),guest,"Guest");rooms.approveGuest(room.getRoomId(),host);
            var shared=locked(n,"app_users",host,()->practice.startShared(host,room.getRoomId(),lesson()).sessionId());
            assertThat(new HashSet<>(shared)).hasSize(1);
            rooms.selectLesson(room.getRoomId(),host,lesson(),2);
            rooms.updatePlayback(room.getRoomId(),host,"audio-ready",1,0,1,true);
            rooms.updatePlayback(room.getRoomId(),guest,"audio-ready",1,0,1,true);
            var plays=race(n,null,null,()->rooms.updatePlayback(room.getRoomId(),host,"lesson-play",1,0,1,true));
            assertThat(plays.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            assertThat(rooms.playbackState(room.getRoomId()).sequence()).isEqualTo(1);
            assertThat(rooms.updatePlayback(room.getRoomId(),user(),"lesson-play",1,1,1,true)).isNull();
            rooms.leaveRoom(room.getRoomId(),host);
            assertThat(rooms.findRoomForUser(host)).isEmpty();assertThat(rooms.findRoomForUser(guest)).isEmpty();
        }
    }
}
