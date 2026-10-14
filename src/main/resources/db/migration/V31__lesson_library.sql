-- Additive library structure. Original lessons, sessions and results are unchanged.
CREATE TABLE library_courses (
    id VARCHAR(80) PRIMARY KEY,
    goal VARCHAR(20) NOT NULL CHECK (goal IN ('IELTS','TOEIC','COMMUNICATION')),
    title VARCHAR(180) NOT NULL,
    target_label VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    criteria VARCHAR(3000) NOT NULL,
    coverage_note VARCHAR(1000) NOT NULL,
    position_index INTEGER NOT NULL CHECK (position_index > 0),
    published BOOLEAN NOT NULL DEFAULT FALSE,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE library_groups (
    id VARCHAR(100) PRIMARY KEY,
    course_id VARCHAR(80) NOT NULL REFERENCES library_courses(id),
    title VARCHAR(180) NOT NULL,
    position_index INTEGER NOT NULL CHECK (position_index > 0),
    UNIQUE (course_id, id),
    UNIQUE (course_id, position_index)
);
CREATE TABLE library_lesson_metadata (
    lesson_id BIGINT PRIMARY KEY REFERENCES lessons(id) ON DELETE CASCADE,
    topic VARCHAR(180) NOT NULL,
    focus VARCHAR(180) NOT NULL,
    source_label VARCHAR(180) NOT NULL,
    accent VARCHAR(120),
    speaker_count INTEGER CHECK (speaker_count > 0),
    audit_notes VARCHAR(3000) NOT NULL,
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    lesson_updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE library_lessons (
    course_id VARCHAR(80) NOT NULL,
    group_id VARCHAR(100) NOT NULL,
    lesson_id BIGINT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    position_index INTEGER NOT NULL CHECK (position_index > 0),
    difficulty INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    purpose VARCHAR(1000) NOT NULL,
    PRIMARY KEY (course_id, lesson_id),
    FOREIGN KEY (course_id, group_id) REFERENCES library_groups(course_id, id),
    UNIQUE (group_id, position_index)
);
CREATE INDEX idx_library_lessons_lesson ON library_lessons(lesson_id);
