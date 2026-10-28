package com.dictalab.vocabulary;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.dictalab.vocabulary.ReviewScheduler.*;

class VocabularyAlgorithmsTest {
    final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    final Sm2Scheduler scheduler=new Sm2Scheduler();
    State fresh() { return new State("NEW",Sm2Scheduler.VERSION,0,2.5,0,0,now); }
    @Test void schedulesConsecutiveRecallAndRelearningFromActualReviewTime() {
        var first=scheduler.next(fresh(),Grade.GOOD,now);
        var second=scheduler.next(first,Grade.GOOD,first.dueAt());
        var third=scheduler.next(second,Grade.GOOD,second.dueAt());
        assertThat(List.of(first.intervalDays(),second.intervalDays(),third.intervalDays())).containsExactly(1,6,15);
        Instant late=third.dueAt().plusSeconds(864000);
        var failed=scheduler.next(third,Grade.AGAIN,late);
        assertThat(failed.state()).isEqualTo("RELEARNING");
        assertThat(failed.dueAt()).isEqualTo(late.plusSeconds(600));
        assertThat(failed.repetitions()).isZero();
        assertThat(failed.lapses()).isEqualTo(1);
        assertThat(scheduler.next(failed,Grade.GOOD,failed.dueAt()).intervalDays()).isEqualTo(1);
    }
    @Test void boundsEaseAndLongIntervalsAndRejectsUnknownAlgorithms() {
        State state=fresh();
        for(int i=0;i<100;i++) state=scheduler.next(state,Grade.AGAIN,now);
        assertThat(state.ease()).isEqualTo(1.3);
        for(int i=0;i<100;i++) state=scheduler.next(state,Grade.EASY,now);
        assertThat(state.intervalDays()).isEqualTo(365);
        assertThatThrownBy(()->scheduler.next(new State("NEW","future",0,2.5,0,0,now),Grade.GOOD,now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void evaluatorPreservesNegationAccentsAndMultiWordExpressions() {
        var evaluator=new RuleAnswerEvaluator();
        assertThat(evaluator.evaluate("  LOOK   AFTER ",List.of("look after"),true)).isEqualTo("CORRECT");
        assertThat(evaluator.evaluate("don’t",List.of("don't"),true)).isEqualTo("CORRECT");
        assertThat(evaluator.evaluate("do",List.of("don't"),true)).isEqualTo("INCORRECT");
        assertThat(evaluator.evaluate("khong",List.of("không"),false)).isEqualTo("UNDETERMINED");
    }
    @Test void csvHandlesBomEscapedQuotesCommasAndMultilineCells() {
        String csv="\uFEFF"+String.join(",",VocabularyCsv.HEADER)+"\r\n\"hello, friend\",\"xin chào \"\"bạn\"\"\",noun,\"First\nsecond\",Ví dụ,tag\r\n";
        assertThat(VocabularyCsv.parse(csv)).containsExactly(List.of("hello, friend","xin chào \"bạn\"","noun","First\nsecond","Ví dụ","tag"));
        assertThatThrownBy(()->VocabularyCsv.parse(String.join(",",VocabularyCsv.HEADER)+"\n\"unclosed")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->VocabularyCsv.parse(String.join(",",VocabularyCsv.HEADER)+"\n\"x\"bad,y,,,,")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(VocabularyCsv.row(List.of("=SUM(A1)","  @cmd","tiếng Việt"))).contains("'=SUM(A1)","'  @cmd","tiếng Việt");
    }
}
