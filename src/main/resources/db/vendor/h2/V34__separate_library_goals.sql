-- The audited ELLLO grammar conversations and three daily-life AI lessons are communication content.
-- Remove exam-category aliases only; retain originals, audio, metadata and learning history.

DELETE FROM library_lessons
WHERE course_id IN (SELECT id FROM library_courses WHERE goal IN ('IELTS','TOEIC'))
  AND lesson_id IN (
    SELECT id FROM lessons WHERE slug IN (
      'elllo-sentences-a1-01-be-verbs-introductions',
      'elllo-sentences-a1-02-be-yes-no-questions',
      'elllo-sentences-a1-03-likes-food',
      'elllo-sentences-a1-04-verbs',
      'elllo-sentences-a1-05-verb-yes-no-questions',
      'elllo-sentences-a2-01-present-simple',
      'elllo-sentences-a2-02-present-simple-3rd-person',
      'elllo-sentences-a2-03-present-continuous',
      'elllo-sentences-a2-04-will',
      'elllo-sentences-a2-05-going-to',
      'elllo-sentences-b1-01-do-emphasis',
      'elllo-sentences-b1-02-causative-verbs',
      'elllo-sentences-b1-03-usedto',
      'elllo-sentences-b1-04-would-pasttense',
      'elllo-sentences-b1-05-should',
      'elllo-sentences-b2-01-passive-simple',
      'elllo-sentences-b2-02-past-passive',
      'elllo-sentences-b2-03-was-able-to',
      'elllo-sentences-b2-04-was-going-to',
      'elllo-sentences-b2-05-adverb-placement',
      'ai-b1-changing-travel-plans-20261002',
      'ai-b1-returning-an-order-20261002',
      'ai-b1-planning-a-team-event-20261002'
    )
  );

UPDATE library_courses c
SET ready=FALSE,
    coverage_note='Chưa có bài nghe riêng cho mục này. Đang bổ sung nội dung; các bài hội thoại hiện có thuộc Giao tiếp.',
    revision=revision+1
WHERE c.goal IN ('IELTS','TOEIC')
  AND NOT EXISTS (SELECT 1 FROM library_lessons x WHERE x.course_id=c.id);

UPDATE library_courses
SET coverage_note='Bài nghe hội thoại thuộc Giao tiếp, phân theo CEFR tham khảo. Chỉ luyện nghe và chép chính tả.',
    revision=revision+1
WHERE id IN ('communication-cefr-a1','communication-cefr-a2','communication-cefr-b1','communication-cefr-b2');
