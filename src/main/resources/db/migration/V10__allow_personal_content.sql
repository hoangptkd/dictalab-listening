ALTER TABLE lessons DROP CONSTRAINT ck_lesson_content_origin;

ALTER TABLE lessons
    ADD CONSTRAINT ck_lesson_content_origin
        CHECK (content_origin IN ('BUNDLED', 'ADMIN_UPLOAD', 'BRITISH_COUNCIL_PERSONAL'));
