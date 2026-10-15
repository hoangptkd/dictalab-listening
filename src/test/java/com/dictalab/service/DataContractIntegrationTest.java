package com.dictalab.service;

import com.dictalab.domain.*;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

// Regression coverage for the data-contract audit; isolated fixtures, no external providers.
@SpringBootTest
class DataContractIntegrationTest {
 @Autowired PracticeService practice;
 @Autowired PracticeSubmissions submissions;
 @Autowired ListeningAssessmentService assessment;
 @Autowired ScoringService scoring;
 @Autowired RecommendationService recommendations;
 @Autowired AppUserRepository users;
 @Autowired LessonRepository lessons;
 @Autowired LessonSentenceRepository sentences;
 @Autowired LessonPreviewWordRepository previews;
 @Autowired JdbcTemplate db;
 @Autowired MediaStorageService media;
 @Autowired com.dictalab.web.CatalogController catalog;
 @Autowired tools.jackson.databind.ObjectMapper json;
 @Autowired HistoryService history;

 long user(){return users.save(new AppUser("audit-"+UUID.randomUUID()+"@test.local","Audit","unused",Instant.now())).getId();}
 Lesson lesson(String audio){
   var l=new Lesson("audit-"+UUID.randomUUID(),"Audit lesson","Audit",CefrLevel.B1,"Audit",1,9000,Instant.now());
   l.setPublished(true,Instant.now()); l=lessons.save(l);
   var s=new LessonSentence(l,1,"we listen carefully",1.0);s.attachAudio(audio);sentences.save(s);return l;
 }
 ApiDtos.PracticeStartResponse start(long user,Lesson lesson){return practice.start(user,new ApiDtos.StartSessionRequest(lesson.getId()));}
 void answer(long user,ApiDtos.PracticeStartResponse session){
   long sentence=session.sentence().sentenceId();
   practice.replay(user,session.sessionId(),sentence,new ApiDtos.ReplayRequest(ReplayEventType.STARTED,1.0));
   String[] words={"we","listen","carefully"};
   for(int i=0;i<words.length;i++) practice.checkWord(user,session.sessionId(),sentence,i,new ApiDtos.CheckWordRequest(words[i],0L));
 }
 @Test void recordedTranscriptIsHiddenBeforeListening(){
   var s=start(user(),lesson("/audio/audit-a.mp3"));
   assertThat(s.sentence().speechText()).isNull();
   assertThat(s.sentence().replayCount()).isZero();
   assertThat(s.sentence().words()).allSatisfy(w->assertThat(w.revealedText()).isNull());
 }
 @Test void sameAudioAcrossLessonIdsIsNotNewEvidence(){
   long u=user();String audio="/audio/"+UUID.randomUUID()+".mp3";
   var a=start(u,lesson(audio));answer(u,a);
   practice.completeSentence(u,a.sessionId(),a.sentence().sentenceId(),new ApiDtos.CompleteSentenceRequest(1));
   var b=start(u,lesson(audio));
   assertThat(db.queryForObject("SELECT first_exposure FROM practice_sessions WHERE id=?",Boolean.class,b.sessionId())).isFalse();
 }
 @Test void clientTimeIsBoundedByServerElapsedTime(){
   long u=user();var s=start(u,lesson("/audio/audit-time.mp3"));answer(u,s);
   var result=practice.completeSentence(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.CompleteSentenceRequest(7200));
   assertThat(db.queryForObject("SELECT total_practice_seconds FROM practice_sessions WHERE id=?",Integer.class,s.sessionId())).isBetween(0,5);
 }
 @Test void onlyPreviewWordsDoNotProduceAnAbilityEstimate(){
   long u=user();var l=lesson("/audio/audit-preview.mp3");int position=1;
   for(String word:new String[]{"we","listen","carefully"})previews.save(new LessonPreviewWord(l,position++,word,null,null));
   var s=start(u,l);answer(u,s);
   practice.completeSentence(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.CompleteSentenceRequest(1));
   var result=assessment.assess(u);
   assertThat(result.estimatedLevel()).isNull();
   assertThat(result.abilityScore()).isZero();
   assertThat(result.evidenceLessons()).isZero();
   assertThat(result.metrics().contentWordsObserved()+result.metrics().functionWordsObserved()).isZero();
 }
 @Test void recheckingCorrectWordDoesNotAddEventsAndRetryIsStable(){
   long u=user();var s=start(u,lesson("/audio/audit-receipts.mp3"));
   practice.replay(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.ReplayRequest(ReplayEventType.STARTED,1.0));
   var request=new ApiDtos.CheckWordRequest("we",0L);String action=s.sentence().sentenceId()+"/checks/0";
   for(int i=0;i<25;i++)submissions.execute(u,s.sessionId(),"audit-key-"+i,action,request,ApiDtos.SentenceState.class,
       ()->practice.checkWord(u,s.sessionId(),s.sentence().sentenceId(),0,request));
   assertThat(db.queryForObject("SELECT COUNT(*) FROM practice_submission_receipts WHERE session_id=?",Integer.class,s.sessionId())).isEqualTo(25);
   assertThat(db.queryForObject("SELECT COUNT(*) FROM word_attempt_events e JOIN word_attempts w ON w.id=e.word_attempt_id JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=?",Integer.class,s.sessionId())).isEqualTo(1);
   var retry=submissions.execute(u,s.sessionId(),"audit-key-0",action,request,ApiDtos.SentenceState.class,
       ()->{throw new AssertionError("Retry wrote twice");});
   assertThat(retry.speechText()).isNull();
 }
 @Test void sameAccuracyAndHintRateHaveSameScore(){
   var small=scoring.calculate(10,8,10,1,0,0,1,1);
   var large=scoring.calculate(100,80,100,10,0,0,10,10);
   assertThat(small.overallScore()).isEqualTo(large.overallScore());
 }
 @Test void recommendationsExcludePrivateLessons(){
   var l=new Lesson("audit-private-"+UUID.randomUUID(),"Private audit lesson","Private",CefrLevel.A1,"Audit",1,-9999,
       Lesson.PERSONAL_CONTENT_ORIGIN,Instant.now());
   l.setPublished(true,Instant.now());l=lessons.save(l);
   try {
     long u=user();var recommendation=recommendations.recommend(u);
     assertThat(recommendation.lessonId()).isNotEqualTo(l.getId());
     assertThat(recommendation.lessonTitle()).isNotEqualTo("Private audit lesson");
     start(u,lessons.findById(recommendation.lessonId()).orElseThrow());
   } finally {l.setPublished(false,Instant.now());lessons.save(l);}
 }
 @Test void unclassifiedWrongAnswerStaysUnclassified(){
   long u=user();var s=start(u,lesson("/audio/audit-category.mp3"));
   practice.replay(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.ReplayRequest(ReplayEventType.STARTED,1.0));
   practice.checkWord(u,s.sessionId(),s.sentence().sentenceId(),0,new ApiDtos.CheckWordRequest("wrong",0L));
   String[] words={"we","listen","carefully"};
   for(int i=0;i<3;i++)practice.checkWord(u,s.sessionId(),s.sentence().sentenceId(),i,new ApiDtos.CheckWordRequest(words[i],0L));
   practice.completeSentence(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.CompleteSentenceRequest(1));
   assertThat(db.queryForObject("SELECT known_missed_count FROM practice_sessions WHERE id=?",Integer.class,s.sessionId())).isZero();
   assertThat(db.queryForObject("SELECT COUNT(*) FROM word_attempts w JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=? AND w.learner_reported_error_type IS NOT NULL",Integer.class,s.sessionId())).isZero();
 }

 @Test void browserSpeechStillWorksButIsNotIndependentEvidence(){
   long u=user();var s=start(u,lesson(null));
   assertThat(s.sentence().speechText()).isEqualTo("we listen carefully");
   assertThat(db.queryForObject("SELECT first_exposure FROM practice_sessions WHERE id=?",Boolean.class,s.sessionId())).isFalse();
 }

 @Test void explicitClassificationRemainsDiscoverableAndIsCounted(){
   long u=user();var s=start(u,lesson("/audio/"+UUID.randomUUID()+".mp3"));
   practice.replay(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
   var state=practice.checkWord(u,s.sessionId(),s.sentence().sentenceId(),0,new ApiDtos.CheckWordRequest("wrong"));
   assertThat(state.words().get(0).classificationAvailable()).isTrue();
   assertThat(state.words().get(0).errorType()).isNull();
   practice.classifyWord(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.ClassifyWordRequest(0,LearnerErrorType.UNKNOWN));
   answer(u,s);
   var result=practice.completeSentence(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.CompleteSentenceRequest(1)).result();
   assertThat(result.unknownWords()).isEqualTo(1);
   assertThat(result.review().sentences().get(0).words().get(0).errorType()).isEqualTo(LearnerErrorType.UNKNOWN);
   assertThat(result.scoringVersion()).isEqualTo(2);
 }

 @Test void draftAudioRequiresVisibilityOrOwnedHistory(){
   long owner=user(),other=user();String filename=UUID.randomUUID()+".mp3";
   var l=lesson("/media/s3/"+filename);
   media.requireLessonAudioAccess(filename,other,false);
   start(owner,l);
   db.update("UPDATE lessons SET active=FALSE WHERE id=?",l.getId());
   org.assertj.core.api.Assertions.assertThatThrownBy(()->media.requireLessonAudioAccess(filename,other,false))
       .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
   media.requireLessonAudioAccess(filename,owner,false);
   media.requireLessonAudioAccess(filename,other,true);
   db.update("UPDATE lessons SET content_origin='BRITISH_COUNCIL_PERSONAL' WHERE id=?",l.getId());
   org.assertj.core.api.Assertions.assertThatThrownBy(()->media.requireLessonAudioAccess(filename,owner,false))
       .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }

 @Test void freePracticeExposureIsValidatedAndDeduplicated(){
   long u=user();String audio="/audio/"+UUID.randomUUID()+".mp3";var l=lesson(audio);
   var principal=com.dictalab.security.DictaLabPrincipal.from(users.findById(u).orElseThrow());
   var body=new com.dictalab.web.CatalogController.Preview(null,audio);
   catalog.preview(principal,body);catalog.preview(principal,body);
   assertThat(db.queryForObject("SELECT COUNT(*) FROM audio_exposure_events WHERE user_id=?",Integer.class,u)).isEqualTo(1);
   var s=start(u,l);
   assertThat(db.queryForObject("SELECT first_exposure FROM practice_sessions WHERE id=?",Boolean.class,s.sessionId())).isFalse();
   org.assertj.core.api.Assertions.assertThatThrownBy(()->catalog.preview(principal,new com.dictalab.web.CatalogController.Preview(null,"/audio/not-published.mp3")))
       .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }

 @Test void receiptLimitKeepsAcceptedRetriesAndRejectsOnlyNewKeys(){
   long u=user();var s=start(u,lesson("/audio/"+UUID.randomUUID()+".mp3"));
   var request=new ApiDtos.ReplayRequest(ReplayEventType.STARTED);String action=s.sentence().sentenceId()+"/replay";
   String hash=AiBackgroundJobs.hash(action+"\n"+json.writeValueAsString(request));
   var rows=new java.util.ArrayList<Object[]>();
   for(int i=0;i<500;i++)rows.add(new Object[]{s.sessionId(),"receipt-"+i,action,hash,json.writeValueAsString(s.sentence())});
   db.batchUpdate("INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",rows);
   var retry=submissions.execute(u,s.sessionId(),"receipt-0",action,request,ApiDtos.SentenceState.class,()->{throw new AssertionError("Retry wrote twice");});
   assertThat(retry.sentenceId()).isEqualTo(s.sentence().sentenceId());
   org.assertj.core.api.Assertions.assertThatThrownBy(()->submissions.execute(u,s.sessionId(),"receipt-new",action,request,ApiDtos.SentenceState.class,()->{throw new AssertionError("Exceeded receipt bound");}))
       .isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
 }

 @Test void attemptLimitStillAllowsHintsAndCompletion(){
   long u=user();var s=start(u,lesson("/audio/"+UUID.randomUUID()+".mp3"));
   long sentence=s.sentence().sentenceId();
   practice.replay(u,s.sessionId(),sentence,new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
   for(int i=0;i<50;i++)practice.checkWord(u,s.sessionId(),sentence,0,new ApiDtos.CheckWordRequest("wrong-"+i));
   org.assertj.core.api.Assertions.assertThatThrownBy(()->practice.checkWord(u,s.sessionId(),sentence,0,new ApiDtos.CheckWordRequest("wrong-next")))
       .isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
   practice.useHint(u,s.sessionId(),sentence,new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE,null));
   practice.replay(u,s.sessionId(),sentence,new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED));
   assertThat(practice.completeSentence(u,s.sessionId(),sentence,new ApiDtos.CompleteSentenceRequest(1)).lessonCompleted()).isTrue();
 }

 @Test void historicalScoresAreNotRecalculatedWhenReadingVersionOne(){
   long u=user();var s=start(u,lesson("/audio/"+UUID.randomUUID()+".mp3"));answer(u,s);
   practice.completeSentence(u,s.sessionId(),s.sentence().sentenceId(),new ApiDtos.CompleteSentenceRequest(1));
   db.update("UPDATE practice_sessions SET scoring_version=1,overall_score=42 WHERE id=?",s.sessionId());
   var result=history.detail(u,s.sessionId());
   assertThat(result.scoringVersion()).isEqualTo(1);
   assertThat(result.overallScore()).isEqualTo(42);
 }

 @Test void oldJsonReceiptCannotReExposeRecordedTranscript(){
   long u=user();var s=start(u,lesson("/audio/"+UUID.randomUUID()+".mp3"));
   var request=new ApiDtos.ReplayRequest(ReplayEventType.STARTED);String action=s.sentence().sentenceId()+"/replay";
   var oldState=(tools.jackson.databind.node.ObjectNode)json.readTree(json.writeValueAsString(s.sentence()));
   oldState.put("speechText","we listen carefully");
   String oldJson=json.writeValueAsString(oldState);
   assertThat(oldJson).contains("we listen carefully");
   db.update("INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",
       s.sessionId(),"legacy-receipt",action,AiBackgroundJobs.hash(action+"\n"+json.writeValueAsString(request)),oldJson);
   var result=submissions.execute(u,s.sessionId(),"legacy-receipt",action,request,ApiDtos.SentenceState.class,()->{throw new AssertionError("Legacy retry wrote twice");});
   assertThat(result.speechText()).isNull();
 }
}
