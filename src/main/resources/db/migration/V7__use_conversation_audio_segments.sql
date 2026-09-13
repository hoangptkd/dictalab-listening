-- Reuse one ELLLO conversation recording for multiple sentence exercises.
-- Sentence boundaries come from static/audio/elllo/segments.json.

ALTER TABLE lesson_sentences
    ADD COLUMN audio_start_seconds DOUBLE PRECISION;

ALTER TABLE lesson_sentences
    ADD COLUMN audio_end_seconds DOUBLE PRECISION;

ALTER TABLE lesson_sentences
    ADD CONSTRAINT ck_sentence_audio_segment CHECK (
        (audio_start_seconds IS NULL AND audio_end_seconds IS NULL)
        OR (audio_start_seconds >= 0 AND audio_end_seconds > audio_start_seconds)
    );

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 2.86,
    audio_end_seconds = 3.455
WHERE lesson_id = 29 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 3.455,
    audio_end_seconds = 5.331
WHERE lesson_id = 29 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 5.331,
    audio_end_seconds = 7.072
WHERE lesson_id = 29 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 7.072,
    audio_end_seconds = 9.365
WHERE lesson_id = 29 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 9.365,
    audio_end_seconds = 10.822
WHERE lesson_id = 29 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 10.822,
    audio_end_seconds = 12.626
WHERE lesson_id = 29 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 12.626,
    audio_end_seconds = 13.804
WHERE lesson_id = 29 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 13.804,
    audio_end_seconds = 15.027
WHERE lesson_id = 29 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 15.027,
    audio_end_seconds = 17.259
WHERE lesson_id = 29 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 17.259,
    audio_end_seconds = 20
WHERE lesson_id = 29 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 20,
    audio_end_seconds = 20.935
WHERE lesson_id = 29 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 20.935,
    audio_end_seconds = 22.483
WHERE lesson_id = 29 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 22.483,
    audio_end_seconds = 24.549
WHERE lesson_id = 29 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 24.549,
    audio_end_seconds = 26.318
WHERE lesson_id = 29 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 26.318,
    audio_end_seconds = 28.464
WHERE lesson_id = 29 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 28.464,
    audio_end_seconds = 31.02
WHERE lesson_id = 29 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-1-A1-01-1-Be-Verbs-First-Day.mp3',
    audio_start_seconds = 31.02,
    audio_end_seconds = 32.68
WHERE lesson_id = 29 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 2.68,
    audio_end_seconds = 5.675
WHERE lesson_id = 29 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 5.675,
    audio_end_seconds = 7.548
WHERE lesson_id = 29 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 7.548,
    audio_end_seconds = 9.727
WHERE lesson_id = 29 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 9.727,
    audio_end_seconds = 11.642
WHERE lesson_id = 29 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 11.642,
    audio_end_seconds = 13.514
WHERE lesson_id = 29 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 13.514,
    audio_end_seconds = 15.967
WHERE lesson_id = 29 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 15.967,
    audio_end_seconds = 17.697
WHERE lesson_id = 29 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 17.697,
    audio_end_seconds = 21.174
WHERE lesson_id = 29 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 21.174,
    audio_end_seconds = 23.7
WHERE lesson_id = 29 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 23.7,
    audio_end_seconds = 25.536
WHERE lesson_id = 29 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 25.536,
    audio_end_seconds = 28.455
WHERE lesson_id = 29 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 28.455,
    audio_end_seconds = 31.38
WHERE lesson_id = 29 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-2-A1-01-2-Be-Verbs-Teachers.mp3',
    audio_start_seconds = 31.38,
    audio_end_seconds = 34.2
WHERE lesson_id = 29 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 2.98,
    audio_end_seconds = 5.174
WHERE lesson_id = 29 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 5.174,
    audio_end_seconds = 6.705
WHERE lesson_id = 29 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 6.705,
    audio_end_seconds = 8.31
WHERE lesson_id = 29 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 8.31,
    audio_end_seconds = 10.985
WHERE lesson_id = 29 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 10.985,
    audio_end_seconds = 12.943
WHERE lesson_id = 29 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 12.943,
    audio_end_seconds = 15.68
WHERE lesson_id = 29 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 15.68,
    audio_end_seconds = 18.626
WHERE lesson_id = 29 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 18.626,
    audio_end_seconds = 19.912
WHERE lesson_id = 29 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 19.912,
    audio_end_seconds = 21.685
WHERE lesson_id = 29 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 21.685,
    audio_end_seconds = 26.666
WHERE lesson_id = 29 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 26.666,
    audio_end_seconds = 27.97
WHERE lesson_id = 29 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 27.97,
    audio_end_seconds = 30.028
WHERE lesson_id = 29 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 30.028,
    audio_end_seconds = 34.65
WHERE lesson_id = 29 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 34.65,
    audio_end_seconds = 36.056
WHERE lesson_id = 29 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-3-A1-01-3-Be-Verbs-Classes.mp3',
    audio_start_seconds = 36.056,
    audio_end_seconds = 37.46
WHERE lesson_id = 29 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 3.62,
    audio_end_seconds = 6.541
WHERE lesson_id = 29 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 6.541,
    audio_end_seconds = 8.79
WHERE lesson_id = 29 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 8.79,
    audio_end_seconds = 11.826
WHERE lesson_id = 29 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 11.826,
    audio_end_seconds = 14.264
WHERE lesson_id = 29 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 14.264,
    audio_end_seconds = 17.162
WHERE lesson_id = 29 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 17.162,
    audio_end_seconds = 19.537
WHERE lesson_id = 29 AND position_index = 51;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 19.537,
    audio_end_seconds = 25.916
WHERE lesson_id = 29 AND position_index = 52;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 25.916,
    audio_end_seconds = 27.06
WHERE lesson_id = 29 AND position_index = 53;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 27.06,
    audio_end_seconds = 29.695
WHERE lesson_id = 29 AND position_index = 54;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 29.695,
    audio_end_seconds = 30.846
WHERE lesson_id = 29 AND position_index = 55;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 30.846,
    audio_end_seconds = 32.578
WHERE lesson_id = 29 AND position_index = 56;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 32.578,
    audio_end_seconds = 34.914
WHERE lesson_id = 29 AND position_index = 57;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-01-Be-Verbs-Introductions/conversation-4-A1-01-4-Be-Verbs-Lab.mp3',
    audio_start_seconds = 34.914,
    audio_end_seconds = 37
WHERE lesson_id = 29 AND position_index = 58;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 3.12,
    audio_end_seconds = 4.44
WHERE lesson_id = 30 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 4.44,
    audio_end_seconds = 7.9
WHERE lesson_id = 30 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 7.9,
    audio_end_seconds = 10.32
WHERE lesson_id = 30 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 10.32,
    audio_end_seconds = 13.02
WHERE lesson_id = 30 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 13.02,
    audio_end_seconds = 15.32
WHERE lesson_id = 30 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 15.32,
    audio_end_seconds = 17.24
WHERE lesson_id = 30 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 17.24,
    audio_end_seconds = 20.12
WHERE lesson_id = 30 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 20.12,
    audio_end_seconds = 21.16
WHERE lesson_id = 30 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 21.16,
    audio_end_seconds = 23.926
WHERE lesson_id = 30 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-1-A1-02-1-Be-Yes-No-Questions-Hot.mp3',
    audio_start_seconds = 23.926,
    audio_end_seconds = 26.6
WHERE lesson_id = 30 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 0,
    audio_end_seconds = 5.584
WHERE lesson_id = 30 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 5.584,
    audio_end_seconds = 8.335
WHERE lesson_id = 30 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 8.335,
    audio_end_seconds = 9.865
WHERE lesson_id = 30 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 9.865,
    audio_end_seconds = 12.933
WHERE lesson_id = 30 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 12.933,
    audio_end_seconds = 14.75
WHERE lesson_id = 30 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 14.75,
    audio_end_seconds = 16.516
WHERE lesson_id = 30 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 16.516,
    audio_end_seconds = 18.56
WHERE lesson_id = 30 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 18.56,
    audio_end_seconds = 19.97
WHERE lesson_id = 30 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-2-A1-02-2-Be-Yes-No-Questions-House.mp3',
    audio_start_seconds = 19.97,
    audio_end_seconds = 21.4
WHERE lesson_id = 30 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 2.7,
    audio_end_seconds = 4.86
WHERE lesson_id = 30 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 4.86,
    audio_end_seconds = 6.4
WHERE lesson_id = 30 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 6.4,
    audio_end_seconds = 8.9
WHERE lesson_id = 30 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 8.9,
    audio_end_seconds = 10.38
WHERE lesson_id = 30 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 10.38,
    audio_end_seconds = 13.72
WHERE lesson_id = 30 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 13.72,
    audio_end_seconds = 15.66
WHERE lesson_id = 30 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 15.66,
    audio_end_seconds = 19.12
WHERE lesson_id = 30 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 19.12,
    audio_end_seconds = 23
WHERE lesson_id = 30 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 23,
    audio_end_seconds = 24.44
WHERE lesson_id = 30 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-3-A1-02-3-Be-Yes-No-Questions-Clothes.mp3',
    audio_start_seconds = 24.44,
    audio_end_seconds = 26.14
WHERE lesson_id = 30 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 3.28,
    audio_end_seconds = 5.06
WHERE lesson_id = 30 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 5.06,
    audio_end_seconds = 8.7
WHERE lesson_id = 30 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 8.7,
    audio_end_seconds = 10.44
WHERE lesson_id = 30 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 10.44,
    audio_end_seconds = 13.8
WHERE lesson_id = 30 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 13.8,
    audio_end_seconds = 16.2
WHERE lesson_id = 30 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 16.2,
    audio_end_seconds = 19.52
WHERE lesson_id = 30 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 19.52,
    audio_end_seconds = 23.053
WHERE lesson_id = 30 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 23.053,
    audio_end_seconds = 24.559
WHERE lesson_id = 30 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 24.559,
    audio_end_seconds = 27.237
WHERE lesson_id = 30 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 27.237,
    audio_end_seconds = 28.272
WHERE lesson_id = 30 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-02-Be-Yes-No-Questions/conversation-4-A1-02-4-Be-Yes-No-Questions-Hungry.mp3',
    audio_start_seconds = 28.272,
    audio_end_seconds = 29.72
WHERE lesson_id = 30 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 3.6,
    audio_end_seconds = 6.152
WHERE lesson_id = 31 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 6.152,
    audio_end_seconds = 10.56
WHERE lesson_id = 31 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 10.56,
    audio_end_seconds = 13.855
WHERE lesson_id = 31 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 13.855,
    audio_end_seconds = 17.282
WHERE lesson_id = 31 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 17.282,
    audio_end_seconds = 20.48
WHERE lesson_id = 31 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 20.48,
    audio_end_seconds = 22.855
WHERE lesson_id = 31 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-1-A1-03-1-Likes-Food.mp3',
    audio_start_seconds = 22.855,
    audio_end_seconds = 25.34
WHERE lesson_id = 31 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 2.94,
    audio_end_seconds = 5.316
WHERE lesson_id = 31 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 5.316,
    audio_end_seconds = 9.35
WHERE lesson_id = 31 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 9.35,
    audio_end_seconds = 13.455
WHERE lesson_id = 31 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 13.455,
    audio_end_seconds = 14.527
WHERE lesson_id = 31 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 14.527,
    audio_end_seconds = 16.473
WHERE lesson_id = 31 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 16.473,
    audio_end_seconds = 19.673
WHERE lesson_id = 31 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-2-A1-03-2-Likes-Drinks.mp3',
    audio_start_seconds = 19.673,
    audio_end_seconds = 23.16
WHERE lesson_id = 31 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 2.3,
    audio_end_seconds = 5.64
WHERE lesson_id = 31 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 5.64,
    audio_end_seconds = 8.64
WHERE lesson_id = 31 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 8.64,
    audio_end_seconds = 9.94
WHERE lesson_id = 31 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 9.94,
    audio_end_seconds = 12.84
WHERE lesson_id = 31 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 12.84,
    audio_end_seconds = 15.36
WHERE lesson_id = 31 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 15.36,
    audio_end_seconds = 18.28
WHERE lesson_id = 31 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 18.28,
    audio_end_seconds = 19.28
WHERE lesson_id = 31 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 19.28,
    audio_end_seconds = 22.68
WHERE lesson_id = 31 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 22.68,
    audio_end_seconds = 25.859
WHERE lesson_id = 31 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 25.859,
    audio_end_seconds = 30.004
WHERE lesson_id = 31 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-3-A1-03-3-Likes-Desserts.mp3',
    audio_start_seconds = 30.004,
    audio_end_seconds = 31.24
WHERE lesson_id = 31 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 3.18,
    audio_end_seconds = 5.395
WHERE lesson_id = 31 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 5.395,
    audio_end_seconds = 8.063
WHERE lesson_id = 31 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 8.063,
    audio_end_seconds = 9.321
WHERE lesson_id = 31 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 9.321,
    audio_end_seconds = 11.382
WHERE lesson_id = 31 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 11.382,
    audio_end_seconds = 14.764
WHERE lesson_id = 31 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 14.764,
    audio_end_seconds = 16.147
WHERE lesson_id = 31 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 16.147,
    audio_end_seconds = 19.83
WHERE lesson_id = 31 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 19.83,
    audio_end_seconds = 22.799
WHERE lesson_id = 31 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 22.799,
    audio_end_seconds = 24.442
WHERE lesson_id = 31 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 24.442,
    audio_end_seconds = 27.072
WHERE lesson_id = 31 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-03-Likes-Food/conversation-4-A1-03-4-Likes-Snacks.mp3',
    audio_start_seconds = 27.072,
    audio_end_seconds = 28.74
WHERE lesson_id = 31 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 2.7,
    audio_end_seconds = 5.24
WHERE lesson_id = 32 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 5.24,
    audio_end_seconds = 7.6
WHERE lesson_id = 32 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 7.6,
    audio_end_seconds = 8.98
WHERE lesson_id = 32 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 8.98,
    audio_end_seconds = 13
WHERE lesson_id = 32 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 13,
    audio_end_seconds = 14.64
WHERE lesson_id = 32 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 14.64,
    audio_end_seconds = 16.88
WHERE lesson_id = 32 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 16.88,
    audio_end_seconds = 18.813
WHERE lesson_id = 32 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 18.813,
    audio_end_seconds = 19.96
WHERE lesson_id = 32 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 19.96,
    audio_end_seconds = 21.637
WHERE lesson_id = 32 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 21.637,
    audio_end_seconds = 23.049
WHERE lesson_id = 32 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-1-A1-04-1-Verbs-Morning.mp3',
    audio_start_seconds = 23.049,
    audio_end_seconds = 24.88
WHERE lesson_id = 32 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 4.24,
    audio_end_seconds = 6.513
WHERE lesson_id = 32 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 6.513,
    audio_end_seconds = 8.875
WHERE lesson_id = 32 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 8.875,
    audio_end_seconds = 10.8
WHERE lesson_id = 32 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 10.8,
    audio_end_seconds = 12.48
WHERE lesson_id = 32 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 12.48,
    audio_end_seconds = 14.684
WHERE lesson_id = 32 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 14.684,
    audio_end_seconds = 16.54
WHERE lesson_id = 32 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 16.54,
    audio_end_seconds = 19.098
WHERE lesson_id = 32 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 19.098,
    audio_end_seconds = 20.412
WHERE lesson_id = 32 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 20.412,
    audio_end_seconds = 23.235
WHERE lesson_id = 32 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 23.235,
    audio_end_seconds = 25.976
WHERE lesson_id = 32 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-2-A1-04-2-Verbs-Day.mp3',
    audio_start_seconds = 25.976,
    audio_end_seconds = 27.22
WHERE lesson_id = 32 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 2.94,
    audio_end_seconds = 5.119
WHERE lesson_id = 32 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 5.119,
    audio_end_seconds = 7.45
WHERE lesson_id = 32 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 7.45,
    audio_end_seconds = 8.827
WHERE lesson_id = 32 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 8.827,
    audio_end_seconds = 11.067
WHERE lesson_id = 32 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 11.067,
    audio_end_seconds = 13.237
WHERE lesson_id = 32 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 13.237,
    audio_end_seconds = 17.133
WHERE lesson_id = 32 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 17.133,
    audio_end_seconds = 20.297
WHERE lesson_id = 32 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 20.297,
    audio_end_seconds = 22.424
WHERE lesson_id = 32 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-3-A1-04-3-Verbs-Lunch.mp3',
    audio_start_seconds = 22.424,
    audio_end_seconds = 23.62
WHERE lesson_id = 32 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 3.24,
    audio_end_seconds = 5.324
WHERE lesson_id = 32 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 5.324,
    audio_end_seconds = 7.902
WHERE lesson_id = 32 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 7.902,
    audio_end_seconds = 9.828
WHERE lesson_id = 32 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 9.828,
    audio_end_seconds = 12.306
WHERE lesson_id = 32 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 12.306,
    audio_end_seconds = 14.178
WHERE lesson_id = 32 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 14.178,
    audio_end_seconds = 16.037
WHERE lesson_id = 32 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 16.037,
    audio_end_seconds = 17.231
WHERE lesson_id = 32 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 17.231,
    audio_end_seconds = 20.227
WHERE lesson_id = 32 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 20.227,
    audio_end_seconds = 23.297
WHERE lesson_id = 32 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 23.297,
    audio_end_seconds = 25.01
WHERE lesson_id = 32 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-04-Verbs/conversation-4-A1-04-4-Verbs-Weekend.mp3',
    audio_start_seconds = 25.01,
    audio_end_seconds = 26.36
WHERE lesson_id = 32 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 3.48,
    audio_end_seconds = 5.078
WHERE lesson_id = 33 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 5.078,
    audio_end_seconds = 6.84
WHERE lesson_id = 33 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 6.84,
    audio_end_seconds = 9.38
WHERE lesson_id = 33 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 9.38,
    audio_end_seconds = 11.673
WHERE lesson_id = 33 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 11.673,
    audio_end_seconds = 15.385
WHERE lesson_id = 33 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 15.385,
    audio_end_seconds = 18.42
WHERE lesson_id = 33 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 18.42,
    audio_end_seconds = 23.073
WHERE lesson_id = 33 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 23.073,
    audio_end_seconds = 26.12
WHERE lesson_id = 33 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-1-A1-05-1-Verb-Yes-No-Questions-Sports.mp3',
    audio_start_seconds = 26.12,
    audio_end_seconds = 29.1
WHERE lesson_id = 33 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 3.74,
    audio_end_seconds = 5.552
WHERE lesson_id = 33 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 5.552,
    audio_end_seconds = 8.807
WHERE lesson_id = 33 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 8.807,
    audio_end_seconds = 10.339
WHERE lesson_id = 33 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 10.339,
    audio_end_seconds = 11.5
WHERE lesson_id = 33 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 11.5,
    audio_end_seconds = 15.732
WHERE lesson_id = 33 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 15.732,
    audio_end_seconds = 17.128
WHERE lesson_id = 33 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 17.128,
    audio_end_seconds = 19.43
WHERE lesson_id = 33 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 19.43,
    audio_end_seconds = 25.026
WHERE lesson_id = 33 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-2-A1-05-2-Verb-Yes-No-Questions-Pets.mp3',
    audio_start_seconds = 25.026,
    audio_end_seconds = 30.02
WHERE lesson_id = 33 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 3.66,
    audio_end_seconds = 6.34
WHERE lesson_id = 33 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 6.34,
    audio_end_seconds = 9.66
WHERE lesson_id = 33 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 9.66,
    audio_end_seconds = 11.02
WHERE lesson_id = 33 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 11.02,
    audio_end_seconds = 15.44
WHERE lesson_id = 33 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 15.44,
    audio_end_seconds = 18.74
WHERE lesson_id = 33 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 18.74,
    audio_end_seconds = 21.88
WHERE lesson_id = 33 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 21.88,
    audio_end_seconds = 26.08
WHERE lesson_id = 33 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 26.08,
    audio_end_seconds = 29.224
WHERE lesson_id = 33 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 29.224,
    audio_end_seconds = 30.513
WHERE lesson_id = 33 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-3-A1-05-3-Verb-Yes-No-Questions-Cooking.mp3',
    audio_start_seconds = 30.513,
    audio_end_seconds = 32.36
WHERE lesson_id = 33 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 2.46,
    audio_end_seconds = 5.75
WHERE lesson_id = 33 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 5.75,
    audio_end_seconds = 9.945
WHERE lesson_id = 33 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 9.945,
    audio_end_seconds = 13.056
WHERE lesson_id = 33 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 13.056,
    audio_end_seconds = 15.513
WHERE lesson_id = 33 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 15.513,
    audio_end_seconds = 17.42
WHERE lesson_id = 33 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 17.42,
    audio_end_seconds = 21.69
WHERE lesson_id = 33 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 21.69,
    audio_end_seconds = 24.505
WHERE lesson_id = 33 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A1/A1-05-Verb-Yes-No-Questions/conversation-4-A1-05-4-Verb-Yes-No-Questions-Housing.mp3',
    audio_start_seconds = 24.505,
    audio_end_seconds = 28.74
WHERE lesson_id = 33 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 3.24,
    audio_end_seconds = 4.817
WHERE lesson_id = 34 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 4.817,
    audio_end_seconds = 7.122
WHERE lesson_id = 34 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 7.122,
    audio_end_seconds = 10.151
WHERE lesson_id = 34 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 10.151,
    audio_end_seconds = 12.413
WHERE lesson_id = 34 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 12.413,
    audio_end_seconds = 14.87
WHERE lesson_id = 34 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 14.87,
    audio_end_seconds = 17.387
WHERE lesson_id = 34 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 17.387,
    audio_end_seconds = 19.422
WHERE lesson_id = 34 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 19.422,
    audio_end_seconds = 22.771
WHERE lesson_id = 34 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 22.771,
    audio_end_seconds = 24.068
WHERE lesson_id = 34 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 24.068,
    audio_end_seconds = 26.206
WHERE lesson_id = 34 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 26.206,
    audio_end_seconds = 28.109
WHERE lesson_id = 34 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 28.109,
    audio_end_seconds = 30.371
WHERE lesson_id = 34 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-1-A2-01-1-Present-Simple-Job.mp3',
    audio_start_seconds = 30.371,
    audio_end_seconds = 31.8
WHERE lesson_id = 34 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 3.1,
    audio_end_seconds = 5.568
WHERE lesson_id = 34 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 5.568,
    audio_end_seconds = 9.562
WHERE lesson_id = 34 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 9.562,
    audio_end_seconds = 11.885
WHERE lesson_id = 34 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 11.885,
    audio_end_seconds = 13.93
WHERE lesson_id = 34 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 13.93,
    audio_end_seconds = 17.967
WHERE lesson_id = 34 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 17.967,
    audio_end_seconds = 22.448
WHERE lesson_id = 34 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 22.448,
    audio_end_seconds = 25.29
WHERE lesson_id = 34 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 25.29,
    audio_end_seconds = 27.38
WHERE lesson_id = 34 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 27.38,
    audio_end_seconds = 30.152
WHERE lesson_id = 34 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 30.152,
    audio_end_seconds = 33.737
WHERE lesson_id = 34 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 33.737,
    audio_end_seconds = 37.072
WHERE lesson_id = 34 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 37.072,
    audio_end_seconds = 43.309
WHERE lesson_id = 34 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 43.309,
    audio_end_seconds = 45.753
WHERE lesson_id = 34 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 45.753,
    audio_end_seconds = 48.672
WHERE lesson_id = 34 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 48.672,
    audio_end_seconds = 49.721
WHERE lesson_id = 34 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 49.721,
    audio_end_seconds = 52.605
WHERE lesson_id = 34 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-2-A2-01-2-Present-Simple-Gym.mp3',
    audio_start_seconds = 52.605,
    audio_end_seconds = 54.56
WHERE lesson_id = 34 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 4.24,
    audio_end_seconds = 6.406
WHERE lesson_id = 34 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 6.406,
    audio_end_seconds = 8.755
WHERE lesson_id = 34 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 8.755,
    audio_end_seconds = 11.553
WHERE lesson_id = 34 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 11.553,
    audio_end_seconds = 13.531
WHERE lesson_id = 34 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 13.531,
    audio_end_seconds = 16.336
WHERE lesson_id = 34 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 16.336,
    audio_end_seconds = 19.36
WHERE lesson_id = 34 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 19.36,
    audio_end_seconds = 20.629
WHERE lesson_id = 34 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 20.629,
    audio_end_seconds = 24.586
WHERE lesson_id = 34 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 24.586,
    audio_end_seconds = 26.484
WHERE lesson_id = 34 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 26.484,
    audio_end_seconds = 27.72
WHERE lesson_id = 34 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 27.72,
    audio_end_seconds = 30.968
WHERE lesson_id = 34 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 30.968,
    audio_end_seconds = 33.593
WHERE lesson_id = 34 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-3-A2-01-3-Present-Simple-Parents.mp3',
    audio_start_seconds = 33.593,
    audio_end_seconds = 36.06
WHERE lesson_id = 34 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 2.7,
    audio_end_seconds = 4.7
WHERE lesson_id = 34 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 4.7,
    audio_end_seconds = 7.04
WHERE lesson_id = 34 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 7.04,
    audio_end_seconds = 10.3
WHERE lesson_id = 34 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 10.3,
    audio_end_seconds = 14
WHERE lesson_id = 34 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 14,
    audio_end_seconds = 16.2
WHERE lesson_id = 34 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 16.2,
    audio_end_seconds = 17.26
WHERE lesson_id = 34 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 17.26,
    audio_end_seconds = 21.02
WHERE lesson_id = 34 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 21.02,
    audio_end_seconds = 23.3
WHERE lesson_id = 34 AND position_index = 51;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 23.3,
    audio_end_seconds = 27.02
WHERE lesson_id = 34 AND position_index = 52;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 27.02,
    audio_end_seconds = 29.808
WHERE lesson_id = 34 AND position_index = 53;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 29.808,
    audio_end_seconds = 34.493
WHERE lesson_id = 34 AND position_index = 54;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 34.493,
    audio_end_seconds = 36.316
WHERE lesson_id = 34 AND position_index = 55;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 36.316,
    audio_end_seconds = 41.772
WHERE lesson_id = 34 AND position_index = 56;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 41.772,
    audio_end_seconds = 43.781
WHERE lesson_id = 34 AND position_index = 57;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 43.781,
    audio_end_seconds = 47.8
WHERE lesson_id = 34 AND position_index = 58;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 47.8,
    audio_end_seconds = 48.952
WHERE lesson_id = 34 AND position_index = 59;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-01-Present-Simple/conversation-4-A2-01-4-Present-Simple-House.mp3',
    audio_start_seconds = 48.952,
    audio_end_seconds = 50.7
WHERE lesson_id = 34 AND position_index = 60;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 3.64,
    audio_end_seconds = 5.969
WHERE lesson_id = 35 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 5.969,
    audio_end_seconds = 8.459
WHERE lesson_id = 35 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 8.459,
    audio_end_seconds = 11.148
WHERE lesson_id = 35 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 11.148,
    audio_end_seconds = 13.123
WHERE lesson_id = 35 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 13.123,
    audio_end_seconds = 16.563
WHERE lesson_id = 35 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 16.563,
    audio_end_seconds = 18.921
WHERE lesson_id = 35 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 18.921,
    audio_end_seconds = 20.907
WHERE lesson_id = 35 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 20.907,
    audio_end_seconds = 22.75
WHERE lesson_id = 35 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-1-A2-02-1-Present-Simple-3rd-Person-Dad.mp3',
    audio_start_seconds = 22.75,
    audio_end_seconds = 25.66
WHERE lesson_id = 35 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 3.5,
    audio_end_seconds = 5.609
WHERE lesson_id = 35 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 5.609,
    audio_end_seconds = 8.27
WHERE lesson_id = 35 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 8.27,
    audio_end_seconds = 9.26
WHERE lesson_id = 35 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 9.26,
    audio_end_seconds = 11.259
WHERE lesson_id = 35 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 11.259,
    audio_end_seconds = 15.359
WHERE lesson_id = 35 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 15.359,
    audio_end_seconds = 19.204
WHERE lesson_id = 35 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 19.204,
    audio_end_seconds = 24.55
WHERE lesson_id = 35 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 24.55,
    audio_end_seconds = 28.04
WHERE lesson_id = 35 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 28.04,
    audio_end_seconds = 33.244
WHERE lesson_id = 35 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 33.244,
    audio_end_seconds = 35.913
WHERE lesson_id = 35 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-2-A2-02-2-Present-Simple-3rd-Person-Sister.mp3',
    audio_start_seconds = 35.913,
    audio_end_seconds = 43
WHERE lesson_id = 35 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 3.28,
    audio_end_seconds = 6.84
WHERE lesson_id = 35 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 6.84,
    audio_end_seconds = 9.66
WHERE lesson_id = 35 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 9.66,
    audio_end_seconds = 10.84
WHERE lesson_id = 35 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 10.84,
    audio_end_seconds = 13.04
WHERE lesson_id = 35 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 13.04,
    audio_end_seconds = 16.64
WHERE lesson_id = 35 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 16.64,
    audio_end_seconds = 19.76
WHERE lesson_id = 35 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 19.76,
    audio_end_seconds = 21.02
WHERE lesson_id = 35 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 21.02,
    audio_end_seconds = 25.6
WHERE lesson_id = 35 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 25.6,
    audio_end_seconds = 28.16
WHERE lesson_id = 35 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 28.16,
    audio_end_seconds = 31.764
WHERE lesson_id = 35 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 31.764,
    audio_end_seconds = 34.068
WHERE lesson_id = 35 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 34.068,
    audio_end_seconds = 37.262
WHERE lesson_id = 35 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 37.262,
    audio_end_seconds = 38.904
WHERE lesson_id = 35 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-3-A2-02-3-Present-Simple-3rd-Person-Mother.mp3',
    audio_start_seconds = 38.904,
    audio_end_seconds = 43.22
WHERE lesson_id = 35 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 3.56,
    audio_end_seconds = 6.56
WHERE lesson_id = 35 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 6.56,
    audio_end_seconds = 10.04
WHERE lesson_id = 35 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 10.04,
    audio_end_seconds = 13.22
WHERE lesson_id = 35 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 13.22,
    audio_end_seconds = 15.34
WHERE lesson_id = 35 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 15.34,
    audio_end_seconds = 19.16
WHERE lesson_id = 35 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 19.16,
    audio_end_seconds = 20.26
WHERE lesson_id = 35 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 20.26,
    audio_end_seconds = 23.08
WHERE lesson_id = 35 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 23.08,
    audio_end_seconds = 25.82
WHERE lesson_id = 35 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 25.82,
    audio_end_seconds = 32.365
WHERE lesson_id = 35 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 32.365,
    audio_end_seconds = 34.41
WHERE lesson_id = 35 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 34.41,
    audio_end_seconds = 40.197
WHERE lesson_id = 35 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 40.197,
    audio_end_seconds = 43.262
WHERE lesson_id = 35 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 43.262,
    audio_end_seconds = 45.31
WHERE lesson_id = 35 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 45.31,
    audio_end_seconds = 50.837
WHERE lesson_id = 35 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 50.837,
    audio_end_seconds = 51.874
WHERE lesson_id = 35 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-02-Present-Simple-3rd-Person/conversation-4-A2-02-4-Present-Simple-3rd-Person-Language.mp3',
    audio_start_seconds = 51.874,
    audio_end_seconds = 53.82
WHERE lesson_id = 35 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 3.24,
    audio_end_seconds = 4.747
WHERE lesson_id = 36 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 4.747,
    audio_end_seconds = 7.235
WHERE lesson_id = 36 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 7.235,
    audio_end_seconds = 9.756
WHERE lesson_id = 36 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 9.756,
    audio_end_seconds = 12.442
WHERE lesson_id = 36 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 12.442,
    audio_end_seconds = 15.374
WHERE lesson_id = 36 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 15.374,
    audio_end_seconds = 17.009
WHERE lesson_id = 36 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 17.009,
    audio_end_seconds = 19.427
WHERE lesson_id = 36 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 19.427,
    audio_end_seconds = 22.384
WHERE lesson_id = 36 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 22.384,
    audio_end_seconds = 26.19
WHERE lesson_id = 36 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-1-A2-03-1-Present-Continuous-Report.mp3',
    audio_start_seconds = 26.19,
    audio_end_seconds = 28.3
WHERE lesson_id = 36 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 3.22,
    audio_end_seconds = 4.916
WHERE lesson_id = 36 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 4.916,
    audio_end_seconds = 7.125
WHERE lesson_id = 36 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 7.125,
    audio_end_seconds = 9.182
WHERE lesson_id = 36 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 9.182,
    audio_end_seconds = 11.12
WHERE lesson_id = 36 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 11.12,
    audio_end_seconds = 13.211
WHERE lesson_id = 36 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 13.211,
    audio_end_seconds = 15.635
WHERE lesson_id = 36 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 15.635,
    audio_end_seconds = 17.933
WHERE lesson_id = 36 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 17.933,
    audio_end_seconds = 20.538
WHERE lesson_id = 36 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 20.538,
    audio_end_seconds = 24.224
WHERE lesson_id = 36 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 24.224,
    audio_end_seconds = 27.4
WHERE lesson_id = 36 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 27.4,
    audio_end_seconds = 30.348
WHERE lesson_id = 36 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 30.348,
    audio_end_seconds = 33.026
WHERE lesson_id = 36 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-2-A2-03-2-Present-Continuous-Family.mp3',
    audio_start_seconds = 33.026,
    audio_end_seconds = 36.7
WHERE lesson_id = 36 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 2.52,
    audio_end_seconds = 5.36
WHERE lesson_id = 36 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 5.36,
    audio_end_seconds = 7.46
WHERE lesson_id = 36 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 7.46,
    audio_end_seconds = 9.74
WHERE lesson_id = 36 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 9.74,
    audio_end_seconds = 12.06
WHERE lesson_id = 36 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 12.06,
    audio_end_seconds = 12.86
WHERE lesson_id = 36 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 12.86,
    audio_end_seconds = 14.58
WHERE lesson_id = 36 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 14.58,
    audio_end_seconds = 18.3
WHERE lesson_id = 36 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 18.3,
    audio_end_seconds = 22.32
WHERE lesson_id = 36 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 22.32,
    audio_end_seconds = 24.06
WHERE lesson_id = 36 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 24.06,
    audio_end_seconds = 26.53
WHERE lesson_id = 36 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 26.53,
    audio_end_seconds = 28.293
WHERE lesson_id = 36 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 28.293,
    audio_end_seconds = 29.433
WHERE lesson_id = 36 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 29.433,
    audio_end_seconds = 34.106
WHERE lesson_id = 36 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 34.106,
    audio_end_seconds = 36.543
WHERE lesson_id = 36 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 36.543,
    audio_end_seconds = 37.638
WHERE lesson_id = 36 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 37.638,
    audio_end_seconds = 39.048
WHERE lesson_id = 36 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-3-A2-03-3-Present-Continuous-Encounter.mp3',
    audio_start_seconds = 39.048,
    audio_end_seconds = 40.06
WHERE lesson_id = 36 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 3.62,
    audio_end_seconds = 5.838
WHERE lesson_id = 36 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 5.838,
    audio_end_seconds = 9.864
WHERE lesson_id = 36 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 9.864,
    audio_end_seconds = 11.403
WHERE lesson_id = 36 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 11.403,
    audio_end_seconds = 12.836
WHERE lesson_id = 36 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 12.836,
    audio_end_seconds = 14.906
WHERE lesson_id = 36 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 14.906,
    audio_end_seconds = 17.299
WHERE lesson_id = 36 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 17.299,
    audio_end_seconds = 19.131
WHERE lesson_id = 36 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 19.131,
    audio_end_seconds = 20.853
WHERE lesson_id = 36 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 20.853,
    audio_end_seconds = 24.2
WHERE lesson_id = 36 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 24.2,
    audio_end_seconds = 26.4
WHERE lesson_id = 36 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 26.4,
    audio_end_seconds = 28.779
WHERE lesson_id = 36 AND position_index = 51;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 28.779,
    audio_end_seconds = 31.428
WHERE lesson_id = 36 AND position_index = 52;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 31.428,
    audio_end_seconds = 33.12
WHERE lesson_id = 36 AND position_index = 53;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-03-Present-Continuous/conversation-4-A2-03-4-Present-Continuous-Accident.mp3',
    audio_start_seconds = 33.12,
    audio_end_seconds = 34.84
WHERE lesson_id = 36 AND position_index = 54;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 3.38,
    audio_end_seconds = 5.722
WHERE lesson_id = 37 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 5.722,
    audio_end_seconds = 9.473
WHERE lesson_id = 37 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 9.473,
    audio_end_seconds = 13.122
WHERE lesson_id = 37 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 13.122,
    audio_end_seconds = 15.444
WHERE lesson_id = 37 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 15.444,
    audio_end_seconds = 17.369
WHERE lesson_id = 37 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 17.369,
    audio_end_seconds = 19.845
WHERE lesson_id = 37 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 19.845,
    audio_end_seconds = 22.17
WHERE lesson_id = 37 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 22.17,
    audio_end_seconds = 26.98
WHERE lesson_id = 37 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 26.98,
    audio_end_seconds = 31.346
WHERE lesson_id = 37 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 31.346,
    audio_end_seconds = 33.517
WHERE lesson_id = 37 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 33.517,
    audio_end_seconds = 35.102
WHERE lesson_id = 37 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 35.102,
    audio_end_seconds = 38.061
WHERE lesson_id = 37 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-1-A2-04-1-Will-Class.mp3',
    audio_start_seconds = 38.061,
    audio_end_seconds = 39.82
WHERE lesson_id = 37 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 2.28,
    audio_end_seconds = 5.62
WHERE lesson_id = 37 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 5.62,
    audio_end_seconds = 7.5
WHERE lesson_id = 37 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 7.5,
    audio_end_seconds = 10.399
WHERE lesson_id = 37 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 10.399,
    audio_end_seconds = 12.641
WHERE lesson_id = 37 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 12.641,
    audio_end_seconds = 16.58
WHERE lesson_id = 37 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 16.58,
    audio_end_seconds = 19.791
WHERE lesson_id = 37 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 19.791,
    audio_end_seconds = 21.733
WHERE lesson_id = 37 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 21.733,
    audio_end_seconds = 25.045
WHERE lesson_id = 37 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 25.045,
    audio_end_seconds = 26.684
WHERE lesson_id = 37 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 26.684,
    audio_end_seconds = 29.14
WHERE lesson_id = 37 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 29.14,
    audio_end_seconds = 32.372
WHERE lesson_id = 37 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 32.372,
    audio_end_seconds = 34.401
WHERE lesson_id = 37 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 34.401,
    audio_end_seconds = 37.162
WHERE lesson_id = 37 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 37.162,
    audio_end_seconds = 40.437
WHERE lesson_id = 37 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 40.437,
    audio_end_seconds = 43.196
WHERE lesson_id = 37 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 43.196,
    audio_end_seconds = 44.989
WHERE lesson_id = 37 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 44.989,
    audio_end_seconds = 47.819
WHERE lesson_id = 37 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-2-A2-04-2-Will-Picnic.mp3',
    audio_start_seconds = 47.819,
    audio_end_seconds = 49.46
WHERE lesson_id = 37 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 3.38,
    audio_end_seconds = 5.879
WHERE lesson_id = 37 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 5.879,
    audio_end_seconds = 9.446
WHERE lesson_id = 37 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 9.446,
    audio_end_seconds = 11.25
WHERE lesson_id = 37 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 11.25,
    audio_end_seconds = 13.898
WHERE lesson_id = 37 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 13.898,
    audio_end_seconds = 18.88
WHERE lesson_id = 37 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 18.88,
    audio_end_seconds = 20.287
WHERE lesson_id = 37 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 20.287,
    audio_end_seconds = 22.314
WHERE lesson_id = 37 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 22.314,
    audio_end_seconds = 25.455
WHERE lesson_id = 37 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 25.455,
    audio_end_seconds = 27.236
WHERE lesson_id = 37 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 27.236,
    audio_end_seconds = 28.88
WHERE lesson_id = 37 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-3-A2-04-3-Will-Summer.mp3',
    audio_start_seconds = 28.88,
    audio_end_seconds = 31.38
WHERE lesson_id = 37 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 3.32,
    audio_end_seconds = 5.723
WHERE lesson_id = 37 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 5.723,
    audio_end_seconds = 7.879
WHERE lesson_id = 37 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 7.879,
    audio_end_seconds = 10.08
WHERE lesson_id = 37 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 10.08,
    audio_end_seconds = 13.944
WHERE lesson_id = 37 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 13.944,
    audio_end_seconds = 15.366
WHERE lesson_id = 37 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 15.366,
    audio_end_seconds = 18.058
WHERE lesson_id = 37 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 18.058,
    audio_end_seconds = 21.968
WHERE lesson_id = 37 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 21.968,
    audio_end_seconds = 27.102
WHERE lesson_id = 37 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 27.102,
    audio_end_seconds = 28.74
WHERE lesson_id = 37 AND position_index = 51;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 28.74,
    audio_end_seconds = 34.104
WHERE lesson_id = 37 AND position_index = 52;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 34.104,
    audio_end_seconds = 37.288
WHERE lesson_id = 37 AND position_index = 53;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 37.288,
    audio_end_seconds = 39.847
WHERE lesson_id = 37 AND position_index = 54;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 39.847,
    audio_end_seconds = 41.492
WHERE lesson_id = 37 AND position_index = 55;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 41.492,
    audio_end_seconds = 44.195
WHERE lesson_id = 37 AND position_index = 56;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 44.195,
    audio_end_seconds = 45.702
WHERE lesson_id = 37 AND position_index = 57;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 45.702,
    audio_end_seconds = 47.282
WHERE lesson_id = 37 AND position_index = 58;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 47.282,
    audio_end_seconds = 51.555
WHERE lesson_id = 37 AND position_index = 59;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 51.555,
    audio_end_seconds = 53.671
WHERE lesson_id = 37 AND position_index = 60;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 53.671,
    audio_end_seconds = 55.8
WHERE lesson_id = 37 AND position_index = 61;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-04-Will/conversation-4-A2-04-4-Will-Game.mp3',
    audio_start_seconds = 55.8,
    audio_end_seconds = 58.1
WHERE lesson_id = 37 AND position_index = 62;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 3.86,
    audio_end_seconds = 6.276
WHERE lesson_id = 38 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 6.276,
    audio_end_seconds = 8.92
WHERE lesson_id = 38 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 8.92,
    audio_end_seconds = 10.942
WHERE lesson_id = 38 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 10.942,
    audio_end_seconds = 13.882
WHERE lesson_id = 38 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 13.882,
    audio_end_seconds = 14.789
WHERE lesson_id = 38 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 14.789,
    audio_end_seconds = 17.22
WHERE lesson_id = 38 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 17.22,
    audio_end_seconds = 20.83
WHERE lesson_id = 38 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 20.83,
    audio_end_seconds = 22.799
WHERE lesson_id = 38 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 22.799,
    audio_end_seconds = 25.242
WHERE lesson_id = 38 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 25.242,
    audio_end_seconds = 27.328
WHERE lesson_id = 38 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 27.328,
    audio_end_seconds = 28.88
WHERE lesson_id = 38 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 28.88,
    audio_end_seconds = 31.529
WHERE lesson_id = 38 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 31.529,
    audio_end_seconds = 32.83
WHERE lesson_id = 38 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 32.83,
    audio_end_seconds = 35.705
WHERE lesson_id = 38 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-1-A2-05-1-Going-To-Tonight.mp3',
    audio_start_seconds = 35.705,
    audio_end_seconds = 38.4
WHERE lesson_id = 38 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 3.66,
    audio_end_seconds = 7.18
WHERE lesson_id = 38 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 7.18,
    audio_end_seconds = 8.06
WHERE lesson_id = 38 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 8.06,
    audio_end_seconds = 10.92
WHERE lesson_id = 38 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 10.92,
    audio_end_seconds = 13.36
WHERE lesson_id = 38 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 13.36,
    audio_end_seconds = 15.34
WHERE lesson_id = 38 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 15.34,
    audio_end_seconds = 16.88
WHERE lesson_id = 38 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 16.88,
    audio_end_seconds = 21.28
WHERE lesson_id = 38 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 21.28,
    audio_end_seconds = 24.32
WHERE lesson_id = 38 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 24.32,
    audio_end_seconds = 28.04
WHERE lesson_id = 38 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 28.04,
    audio_end_seconds = 30.938
WHERE lesson_id = 38 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 30.938,
    audio_end_seconds = 34.192
WHERE lesson_id = 38 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 34.192,
    audio_end_seconds = 35.369
WHERE lesson_id = 38 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-2-A2-05-2-Going-To-Camping.mp3',
    audio_start_seconds = 35.369,
    audio_end_seconds = 37.92
WHERE lesson_id = 38 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 2.7,
    audio_end_seconds = 6.32
WHERE lesson_id = 38 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 6.32,
    audio_end_seconds = 8.28
WHERE lesson_id = 38 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 8.28,
    audio_end_seconds = 10.8
WHERE lesson_id = 38 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 10.8,
    audio_end_seconds = 13.48
WHERE lesson_id = 38 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 13.48,
    audio_end_seconds = 18.1
WHERE lesson_id = 38 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 18.1,
    audio_end_seconds = 22.68
WHERE lesson_id = 38 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 22.68,
    audio_end_seconds = 24.42
WHERE lesson_id = 38 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 24.42,
    audio_end_seconds = 29.622
WHERE lesson_id = 38 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 29.622,
    audio_end_seconds = 30.626
WHERE lesson_id = 38 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 30.626,
    audio_end_seconds = 33.107
WHERE lesson_id = 38 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 33.107,
    audio_end_seconds = 39.25
WHERE lesson_id = 38 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 39.25,
    audio_end_seconds = 40.36
WHERE lesson_id = 38 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 40.36,
    audio_end_seconds = 42.9
WHERE lesson_id = 38 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 42.9,
    audio_end_seconds = 47.52
WHERE lesson_id = 38 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 47.52,
    audio_end_seconds = 51.505
WHERE lesson_id = 38 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 51.505,
    audio_end_seconds = 52.741
WHERE lesson_id = 38 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-3-A2-05-3-Going-To-BBQ.mp3',
    audio_start_seconds = 52.741,
    audio_end_seconds = 56.16
WHERE lesson_id = 38 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 4.12,
    audio_end_seconds = 6.46
WHERE lesson_id = 38 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 6.46,
    audio_end_seconds = 9.58
WHERE lesson_id = 38 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 9.58,
    audio_end_seconds = 11.26
WHERE lesson_id = 38 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 11.26,
    audio_end_seconds = 13.02
WHERE lesson_id = 38 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 13.02,
    audio_end_seconds = 15.62
WHERE lesson_id = 38 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 15.62,
    audio_end_seconds = 19.1
WHERE lesson_id = 38 AND position_index = 51;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 19.1,
    audio_end_seconds = 21.669
WHERE lesson_id = 38 AND position_index = 52;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 21.669,
    audio_end_seconds = 22.46
WHERE lesson_id = 38 AND position_index = 53;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 22.46,
    audio_end_seconds = 25.58
WHERE lesson_id = 38 AND position_index = 54;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 25.58,
    audio_end_seconds = 29.81
WHERE lesson_id = 38 AND position_index = 55;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 29.81,
    audio_end_seconds = 32.998
WHERE lesson_id = 38 AND position_index = 56;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 32.998,
    audio_end_seconds = 36.742
WHERE lesson_id = 38 AND position_index = 57;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 36.742,
    audio_end_seconds = 40.908
WHERE lesson_id = 38 AND position_index = 58;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 40.908,
    audio_end_seconds = 43.852
WHERE lesson_id = 38 AND position_index = 59;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/A2/A2-05-Going-To/conversation-4-A2-05-4-Going-To-Vacation.mp3',
    audio_start_seconds = 43.852,
    audio_end_seconds = 47.24
WHERE lesson_id = 38 AND position_index = 60;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 2.7,
    audio_end_seconds = 5.166
WHERE lesson_id = 39 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 5.166,
    audio_end_seconds = 7.875
WHERE lesson_id = 39 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 7.875,
    audio_end_seconds = 9.056
WHERE lesson_id = 39 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 9.056,
    audio_end_seconds = 11.287
WHERE lesson_id = 39 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 11.287,
    audio_end_seconds = 14.833
WHERE lesson_id = 39 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 14.833,
    audio_end_seconds = 16.915
WHERE lesson_id = 39 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 16.915,
    audio_end_seconds = 18.893
WHERE lesson_id = 39 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 18.893,
    audio_end_seconds = 21.232
WHERE lesson_id = 39 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 21.232,
    audio_end_seconds = 22.508
WHERE lesson_id = 39 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-1-B1-01-1-Do-Emphasis-Sports.mp3',
    audio_start_seconds = 22.508,
    audio_end_seconds = 24.86
WHERE lesson_id = 39 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 3.46,
    audio_end_seconds = 5.5
WHERE lesson_id = 39 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 5.5,
    audio_end_seconds = 7.62
WHERE lesson_id = 39 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 7.62,
    audio_end_seconds = 9.62
WHERE lesson_id = 39 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 9.62,
    audio_end_seconds = 12.7
WHERE lesson_id = 39 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 12.7,
    audio_end_seconds = 14.24
WHERE lesson_id = 39 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 14.24,
    audio_end_seconds = 15.76
WHERE lesson_id = 39 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 15.76,
    audio_end_seconds = 18.28
WHERE lesson_id = 39 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 18.28,
    audio_end_seconds = 21.1
WHERE lesson_id = 39 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-2-B1-01-2-Do-Emphasis-Cooking.mp3',
    audio_start_seconds = 21.1,
    audio_end_seconds = 24.74
WHERE lesson_id = 39 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 2.68,
    audio_end_seconds = 4.86
WHERE lesson_id = 39 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 4.86,
    audio_end_seconds = 7.74
WHERE lesson_id = 39 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 7.74,
    audio_end_seconds = 9.84
WHERE lesson_id = 39 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 9.84,
    audio_end_seconds = 12.02
WHERE lesson_id = 39 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 12.02,
    audio_end_seconds = 13.2
WHERE lesson_id = 39 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 13.2,
    audio_end_seconds = 15.14
WHERE lesson_id = 39 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 15.14,
    audio_end_seconds = 18.5
WHERE lesson_id = 39 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 18.5,
    audio_end_seconds = 19.56
WHERE lesson_id = 39 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 19.56,
    audio_end_seconds = 22.68
WHERE lesson_id = 39 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-3-B1-01-3-Do-Emphasis-TV.mp3',
    audio_start_seconds = 22.68,
    audio_end_seconds = 26.08
WHERE lesson_id = 39 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 5.9,
    audio_end_seconds = 7.259
WHERE lesson_id = 39 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 7.259,
    audio_end_seconds = 8.537
WHERE lesson_id = 39 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 8.537,
    audio_end_seconds = 12.615
WHERE lesson_id = 39 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 12.615,
    audio_end_seconds = 15.292
WHERE lesson_id = 39 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 15.292,
    audio_end_seconds = 18.929
WHERE lesson_id = 39 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 18.929,
    audio_end_seconds = 21.968
WHERE lesson_id = 39 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 21.968,
    audio_end_seconds = 23.797
WHERE lesson_id = 39 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 23.797,
    audio_end_seconds = 26.52
WHERE lesson_id = 39 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 26.52,
    audio_end_seconds = 28.738
WHERE lesson_id = 39 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 28.738,
    audio_end_seconds = 30.832
WHERE lesson_id = 39 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-01-Do-Emphasis/conversation-4-B1-01-4-Do-Emphasis-Kids.mp3',
    audio_start_seconds = 30.832,
    audio_end_seconds = 32.8
WHERE lesson_id = 39 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 2.58,
    audio_end_seconds = 4.504
WHERE lesson_id = 40 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 4.504,
    audio_end_seconds = 7.081
WHERE lesson_id = 40 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 7.081,
    audio_end_seconds = 9.61
WHERE lesson_id = 40 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 9.61,
    audio_end_seconds = 11.524
WHERE lesson_id = 40 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 11.524,
    audio_end_seconds = 13.561
WHERE lesson_id = 40 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 13.561,
    audio_end_seconds = 14.578
WHERE lesson_id = 40 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 14.578,
    audio_end_seconds = 16.833
WHERE lesson_id = 40 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 16.833,
    audio_end_seconds = 18.993
WHERE lesson_id = 40 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-1-B1-02-1-Causative-Verbs-Tent.mp3',
    audio_start_seconds = 18.993,
    audio_end_seconds = 21.2
WHERE lesson_id = 40 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 3.26,
    audio_end_seconds = 5.082
WHERE lesson_id = 40 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 5.082,
    audio_end_seconds = 7.943
WHERE lesson_id = 40 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 7.943,
    audio_end_seconds = 9.976
WHERE lesson_id = 40 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 9.976,
    audio_end_seconds = 13.265
WHERE lesson_id = 40 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 13.265,
    audio_end_seconds = 13.856
WHERE lesson_id = 40 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 13.856,
    audio_end_seconds = 15.422
WHERE lesson_id = 40 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 15.422,
    audio_end_seconds = 17.77
WHERE lesson_id = 40 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-2-B1-02-2-Causative-Verbs-Late.mp3',
    audio_start_seconds = 17.77,
    audio_end_seconds = 18.88
WHERE lesson_id = 40 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 3.66,
    audio_end_seconds = 6.02
WHERE lesson_id = 40 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 6.02,
    audio_end_seconds = 10.34
WHERE lesson_id = 40 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 10.34,
    audio_end_seconds = 13.02
WHERE lesson_id = 40 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 13.02,
    audio_end_seconds = 15.04
WHERE lesson_id = 40 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 15.04,
    audio_end_seconds = 16.24
WHERE lesson_id = 40 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 16.24,
    audio_end_seconds = 18.42
WHERE lesson_id = 40 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-3-B1-02-3-Causative-Verbs-Car.mp3',
    audio_start_seconds = 18.42,
    audio_end_seconds = 22.6
WHERE lesson_id = 40 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 2.32,
    audio_end_seconds = 5.28
WHERE lesson_id = 40 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 5.28,
    audio_end_seconds = 11.04
WHERE lesson_id = 40 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 11.04,
    audio_end_seconds = 12.12
WHERE lesson_id = 40 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 12.12,
    audio_end_seconds = 13.4
WHERE lesson_id = 40 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 13.4,
    audio_end_seconds = 18.94
WHERE lesson_id = 40 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 18.94,
    audio_end_seconds = 20.22
WHERE lesson_id = 40 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 20.22,
    audio_end_seconds = 21.99
WHERE lesson_id = 40 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 21.99,
    audio_end_seconds = 29.377
WHERE lesson_id = 40 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 29.377,
    audio_end_seconds = 30.352
WHERE lesson_id = 40 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-02-Causative-Verbs/conversation-4-B1-02-4-Causative-Verbs-Mom.mp3',
    audio_start_seconds = 30.352,
    audio_end_seconds = 32.56
WHERE lesson_id = 40 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 3.24,
    audio_end_seconds = 4.86
WHERE lesson_id = 41 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 4.86,
    audio_end_seconds = 7.92
WHERE lesson_id = 41 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 7.92,
    audio_end_seconds = 10.84
WHERE lesson_id = 41 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 10.84,
    audio_end_seconds = 12.58
WHERE lesson_id = 41 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 12.58,
    audio_end_seconds = 14.6
WHERE lesson_id = 41 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 14.6,
    audio_end_seconds = 18.96
WHERE lesson_id = 41 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 18.96,
    audio_end_seconds = 20.16
WHERE lesson_id = 41 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 20.16,
    audio_end_seconds = 21.7
WHERE lesson_id = 41 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 21.7,
    audio_end_seconds = 24.48
WHERE lesson_id = 41 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 24.48,
    audio_end_seconds = 26.508
WHERE lesson_id = 41 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 26.508,
    audio_end_seconds = 30.263
WHERE lesson_id = 41 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-1-B1-03-1-Used-To-Sports.mp3',
    audio_start_seconds = 30.263,
    audio_end_seconds = 32.96
WHERE lesson_id = 41 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 3.68,
    audio_end_seconds = 6.151
WHERE lesson_id = 41 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 6.151,
    audio_end_seconds = 8.424
WHERE lesson_id = 41 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 8.424,
    audio_end_seconds = 12.083
WHERE lesson_id = 41 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 12.083,
    audio_end_seconds = 13.1
WHERE lesson_id = 41 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 13.1,
    audio_end_seconds = 14.846
WHERE lesson_id = 41 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 14.846,
    audio_end_seconds = 19.094
WHERE lesson_id = 41 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 19.094,
    audio_end_seconds = 20.898
WHERE lesson_id = 41 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 20.898,
    audio_end_seconds = 22.525
WHERE lesson_id = 41 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 22.525,
    audio_end_seconds = 24.8
WHERE lesson_id = 41 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 24.8,
    audio_end_seconds = 28.37
WHERE lesson_id = 41 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 28.37,
    audio_end_seconds = 29.846
WHERE lesson_id = 41 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 29.846,
    audio_end_seconds = 31.566
WHERE lesson_id = 41 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-2-B1-03-2-Used-To-Language.mp3',
    audio_start_seconds = 31.566,
    audio_end_seconds = 34.5
WHERE lesson_id = 41 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 3.46,
    audio_end_seconds = 5.362
WHERE lesson_id = 41 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 5.362,
    audio_end_seconds = 8.063
WHERE lesson_id = 41 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 8.063,
    audio_end_seconds = 9.062
WHERE lesson_id = 41 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 9.062,
    audio_end_seconds = 10.359
WHERE lesson_id = 41 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 10.359,
    audio_end_seconds = 12.606
WHERE lesson_id = 41 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 12.606,
    audio_end_seconds = 17.759
WHERE lesson_id = 41 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 17.759,
    audio_end_seconds = 22.5
WHERE lesson_id = 41 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 22.5,
    audio_end_seconds = 23.714
WHERE lesson_id = 41 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-3-B1-03-3-Used-To-Party.mp3',
    audio_start_seconds = 23.714,
    audio_end_seconds = 25.9
WHERE lesson_id = 41 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 2.7,
    audio_end_seconds = 7.54
WHERE lesson_id = 41 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 7.54,
    audio_end_seconds = 10.78
WHERE lesson_id = 41 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 10.78,
    audio_end_seconds = 11.54
WHERE lesson_id = 41 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 11.54,
    audio_end_seconds = 13.36
WHERE lesson_id = 41 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 13.36,
    audio_end_seconds = 14.72
WHERE lesson_id = 41 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 14.72,
    audio_end_seconds = 16.84
WHERE lesson_id = 41 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 16.84,
    audio_end_seconds = 18.32
WHERE lesson_id = 41 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 18.32,
    audio_end_seconds = 20.68
WHERE lesson_id = 41 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 20.68,
    audio_end_seconds = 22.68
WHERE lesson_id = 41 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 22.68,
    audio_end_seconds = 29.98
WHERE lesson_id = 41 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 29.98,
    audio_end_seconds = 33.837
WHERE lesson_id = 41 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 33.837,
    audio_end_seconds = 35.982
WHERE lesson_id = 41 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 35.982,
    audio_end_seconds = 37.233
WHERE lesson_id = 41 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-03-UsedTo/conversation-4-B1-03-4-Used-To-CoWorker.mp3',
    audio_start_seconds = 37.233,
    audio_end_seconds = 38.64
WHERE lesson_id = 41 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 3.22,
    audio_end_seconds = 5.558
WHERE lesson_id = 42 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 5.558,
    audio_end_seconds = 7.47
WHERE lesson_id = 42 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 7.47,
    audio_end_seconds = 10.092
WHERE lesson_id = 42 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 10.092,
    audio_end_seconds = 11.161
WHERE lesson_id = 42 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 11.161,
    audio_end_seconds = 12.84
WHERE lesson_id = 42 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 12.84,
    audio_end_seconds = 19.55
WHERE lesson_id = 42 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 19.55,
    audio_end_seconds = 21.164
WHERE lesson_id = 42 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 21.164,
    audio_end_seconds = 22.686
WHERE lesson_id = 42 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 22.686,
    audio_end_seconds = 29.98
WHERE lesson_id = 42 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 29.98,
    audio_end_seconds = 32.771
WHERE lesson_id = 42 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-1-B1-04-1-Would-PastTense-Farm.mp3',
    audio_start_seconds = 32.771,
    audio_end_seconds = 34.28
WHERE lesson_id = 42 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 2.68,
    audio_end_seconds = 5.54
WHERE lesson_id = 42 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 5.54,
    audio_end_seconds = 7.56
WHERE lesson_id = 42 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 7.56,
    audio_end_seconds = 9.5
WHERE lesson_id = 42 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 9.5,
    audio_end_seconds = 11.82
WHERE lesson_id = 42 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 11.82,
    audio_end_seconds = 14.9
WHERE lesson_id = 42 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 14.9,
    audio_end_seconds = 18.42
WHERE lesson_id = 42 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 18.42,
    audio_end_seconds = 20.18
WHERE lesson_id = 42 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 20.18,
    audio_end_seconds = 23.12
WHERE lesson_id = 42 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 23.12,
    audio_end_seconds = 25.2
WHERE lesson_id = 42 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 25.2,
    audio_end_seconds = 28.979
WHERE lesson_id = 42 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 28.979,
    audio_end_seconds = 30.076
WHERE lesson_id = 42 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 30.076,
    audio_end_seconds = 32.16
WHERE lesson_id = 42 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-2-B1-04-2-Would-PastTense-City.mp3',
    audio_start_seconds = 32.16,
    audio_end_seconds = 34.68
WHERE lesson_id = 42 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 3.3,
    audio_end_seconds = 5.562
WHERE lesson_id = 42 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 5.562,
    audio_end_seconds = 8.739
WHERE lesson_id = 42 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 8.739,
    audio_end_seconds = 9.665
WHERE lesson_id = 42 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 9.665,
    audio_end_seconds = 11.186
WHERE lesson_id = 42 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 11.186,
    audio_end_seconds = 14.002
WHERE lesson_id = 42 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 14.002,
    audio_end_seconds = 17.03
WHERE lesson_id = 42 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 17.03,
    audio_end_seconds = 19.863
WHERE lesson_id = 42 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 19.863,
    audio_end_seconds = 23.082
WHERE lesson_id = 42 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 23.082,
    audio_end_seconds = 25.12
WHERE lesson_id = 42 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 25.12,
    audio_end_seconds = 27.489
WHERE lesson_id = 42 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 27.489,
    audio_end_seconds = 31.216
WHERE lesson_id = 42 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 31.216,
    audio_end_seconds = 33.353
WHERE lesson_id = 42 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 33.353,
    audio_end_seconds = 35.441
WHERE lesson_id = 42 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 35.441,
    audio_end_seconds = 37.288
WHERE lesson_id = 42 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 37.288,
    audio_end_seconds = 41.047
WHERE lesson_id = 42 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 41.047,
    audio_end_seconds = 43.395
WHERE lesson_id = 42 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 43.395,
    audio_end_seconds = 45.446
WHERE lesson_id = 42 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-04-Would-PastTense/conversation-3-B1-04-3-Would-PastTense-Army.mp3',
    audio_start_seconds = 45.446,
    audio_end_seconds = 47.42
WHERE lesson_id = 42 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 0,
    audio_end_seconds = 2.107
WHERE lesson_id = 43 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 2.107,
    audio_end_seconds = 3.767
WHERE lesson_id = 43 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 3.767,
    audio_end_seconds = 7.911
WHERE lesson_id = 43 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 7.911,
    audio_end_seconds = 11.129
WHERE lesson_id = 43 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 11.129,
    audio_end_seconds = 14.651
WHERE lesson_id = 43 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 14.651,
    audio_end_seconds = 16.6
WHERE lesson_id = 43 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 16.6,
    audio_end_seconds = 18.932
WHERE lesson_id = 43 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 18.932,
    audio_end_seconds = 20.602
WHERE lesson_id = 43 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 20.602,
    audio_end_seconds = 24.035
WHERE lesson_id = 43 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 24.035,
    audio_end_seconds = 26.367
WHERE lesson_id = 43 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 26.367,
    audio_end_seconds = 28.345
WHERE lesson_id = 43 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 28.345,
    audio_end_seconds = 30.046
WHERE lesson_id = 43 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 30.046,
    audio_end_seconds = 31.622
WHERE lesson_id = 43 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 31.622,
    audio_end_seconds = 33.763
WHERE lesson_id = 43 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 33.763,
    audio_end_seconds = 35.167
WHERE lesson_id = 43 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-1-B1-05-1-Should-Sick.mp3',
    audio_start_seconds = 35.167,
    audio_end_seconds = 37.18
WHERE lesson_id = 43 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 0,
    audio_end_seconds = 3.7
WHERE lesson_id = 43 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 3.7,
    audio_end_seconds = 6.84
WHERE lesson_id = 43 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 6.84,
    audio_end_seconds = 11.198
WHERE lesson_id = 43 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 11.198,
    audio_end_seconds = 12.693
WHERE lesson_id = 43 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 12.693,
    audio_end_seconds = 14.632
WHERE lesson_id = 43 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 14.632,
    audio_end_seconds = 20.701
WHERE lesson_id = 43 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 20.701,
    audio_end_seconds = 22.216
WHERE lesson_id = 43 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 22.216,
    audio_end_seconds = 24.91
WHERE lesson_id = 43 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 24.91,
    audio_end_seconds = 28.64
WHERE lesson_id = 43 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 28.64,
    audio_end_seconds = 32.146
WHERE lesson_id = 43 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 32.146,
    audio_end_seconds = 33.591
WHERE lesson_id = 43 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 33.591,
    audio_end_seconds = 35.507
WHERE lesson_id = 43 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 35.507,
    audio_end_seconds = 40.597
WHERE lesson_id = 43 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 40.597,
    audio_end_seconds = 42.03
WHERE lesson_id = 43 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 42.03,
    audio_end_seconds = 43.485
WHERE lesson_id = 43 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 43.485,
    audio_end_seconds = 44.985
WHERE lesson_id = 43 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 44.985,
    audio_end_seconds = 46.075
WHERE lesson_id = 43 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-2-B1-05-2-Should-Doctor.mp3',
    audio_start_seconds = 46.075,
    audio_end_seconds = 48.14
WHERE lesson_id = 43 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 0,
    audio_end_seconds = 3.35
WHERE lesson_id = 43 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 3.35,
    audio_end_seconds = 8.677
WHERE lesson_id = 43 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 8.677,
    audio_end_seconds = 9.658
WHERE lesson_id = 43 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 9.658,
    audio_end_seconds = 10.857
WHERE lesson_id = 43 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 10.857,
    audio_end_seconds = 12.812
WHERE lesson_id = 43 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 12.812,
    audio_end_seconds = 15.845
WHERE lesson_id = 43 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 15.845,
    audio_end_seconds = 19.081
WHERE lesson_id = 43 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 19.081,
    audio_end_seconds = 21.048
WHERE lesson_id = 43 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 21.048,
    audio_end_seconds = 23.529
WHERE lesson_id = 43 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 23.529,
    audio_end_seconds = 27
WHERE lesson_id = 43 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 27,
    audio_end_seconds = 30.486
WHERE lesson_id = 43 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 30.486,
    audio_end_seconds = 31.622
WHERE lesson_id = 43 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 31.622,
    audio_end_seconds = 33.455
WHERE lesson_id = 43 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-3-B1-05-3-Should-Spanish.mp3',
    audio_start_seconds = 33.455,
    audio_end_seconds = 34.599
WHERE lesson_id = 43 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 0,
    audio_end_seconds = 2.198
WHERE lesson_id = 43 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 2.198,
    audio_end_seconds = 5.245
WHERE lesson_id = 43 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 5.245,
    audio_end_seconds = 6.726
WHERE lesson_id = 43 AND position_index = 51;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 6.726,
    audio_end_seconds = 9.587
WHERE lesson_id = 43 AND position_index = 52;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 9.587,
    audio_end_seconds = 17.03
WHERE lesson_id = 43 AND position_index = 53;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 17.03,
    audio_end_seconds = 18.855
WHERE lesson_id = 43 AND position_index = 54;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 18.855,
    audio_end_seconds = 21.233
WHERE lesson_id = 43 AND position_index = 55;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 21.233,
    audio_end_seconds = 27.52
WHERE lesson_id = 43 AND position_index = 56;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 27.52,
    audio_end_seconds = 29.866
WHERE lesson_id = 43 AND position_index = 57;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 29.866,
    audio_end_seconds = 32.25
WHERE lesson_id = 43 AND position_index = 58;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 32.25,
    audio_end_seconds = 36.86
WHERE lesson_id = 43 AND position_index = 59;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 36.86,
    audio_end_seconds = 39.696
WHERE lesson_id = 43 AND position_index = 60;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 39.696,
    audio_end_seconds = 41.42
WHERE lesson_id = 43 AND position_index = 61;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 41.42,
    audio_end_seconds = 43.12
WHERE lesson_id = 43 AND position_index = 62;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 43.12,
    audio_end_seconds = 45.357
WHERE lesson_id = 43 AND position_index = 63;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 45.357,
    audio_end_seconds = 49.168
WHERE lesson_id = 43 AND position_index = 64;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 49.168,
    audio_end_seconds = 50.31
WHERE lesson_id = 43 AND position_index = 65;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 50.31,
    audio_end_seconds = 51.743
WHERE lesson_id = 43 AND position_index = 66;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B1/B1-05-Should/conversation-4-B1-05-4-Should-Spanish.mp3',
    audio_start_seconds = 51.743,
    audio_end_seconds = 52.56
WHERE lesson_id = 43 AND position_index = 67;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 5.36,
    audio_end_seconds = 7.883
WHERE lesson_id = 44 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 7.883,
    audio_end_seconds = 9.246
WHERE lesson_id = 44 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 9.246,
    audio_end_seconds = 12.779
WHERE lesson_id = 44 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 12.779,
    audio_end_seconds = 14.325
WHERE lesson_id = 44 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 14.325,
    audio_end_seconds = 15.774
WHERE lesson_id = 44 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 15.774,
    audio_end_seconds = 21.048
WHERE lesson_id = 44 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 21.048,
    audio_end_seconds = 22.74
WHERE lesson_id = 44 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 22.74,
    audio_end_seconds = 23.68
WHERE lesson_id = 44 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-1-B2-01-1-Passive-Present-Restaurant.mp3',
    audio_start_seconds = 23.68,
    audio_end_seconds = 25.12
WHERE lesson_id = 44 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-2-B2-01-2-Passive-Present-Basketball.mp3',
    audio_start_seconds = 4.94,
    audio_end_seconds = 7.194
WHERE lesson_id = 44 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-2-B2-01-2-Passive-Present-Basketball.mp3',
    audio_start_seconds = 7.194,
    audio_end_seconds = 9.245
WHERE lesson_id = 44 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-2-B2-01-2-Passive-Present-Basketball.mp3',
    audio_start_seconds = 9.245,
    audio_end_seconds = 11.617
WHERE lesson_id = 44 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-2-B2-01-2-Passive-Present-Basketball.mp3',
    audio_start_seconds = 11.617,
    audio_end_seconds = 14.091
WHERE lesson_id = 44 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-2-B2-01-2-Passive-Present-Basketball.mp3',
    audio_start_seconds = 14.091,
    audio_end_seconds = 15.383
WHERE lesson_id = 44 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-2-B2-01-2-Passive-Present-Basketball.mp3',
    audio_start_seconds = 15.383,
    audio_end_seconds = 18.98
WHERE lesson_id = 44 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 4.1,
    audio_end_seconds = 6.47
WHERE lesson_id = 44 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 6.47,
    audio_end_seconds = 7.903
WHERE lesson_id = 44 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 7.903,
    audio_end_seconds = 9.255
WHERE lesson_id = 44 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 9.255,
    audio_end_seconds = 11.363
WHERE lesson_id = 44 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 11.363,
    audio_end_seconds = 14.66
WHERE lesson_id = 44 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 14.66,
    audio_end_seconds = 15.511
WHERE lesson_id = 44 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-3-B2-01-3-Passive-Present-Report.mp3',
    audio_start_seconds = 15.511,
    audio_end_seconds = 18.26
WHERE lesson_id = 44 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 5.6,
    audio_end_seconds = 7.714
WHERE lesson_id = 44 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 7.714,
    audio_end_seconds = 8.93
WHERE lesson_id = 44 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 8.93,
    audio_end_seconds = 10.06
WHERE lesson_id = 44 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 10.06,
    audio_end_seconds = 12.008
WHERE lesson_id = 44 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 12.008,
    audio_end_seconds = 13.357
WHERE lesson_id = 44 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 13.357,
    audio_end_seconds = 16.22
WHERE lesson_id = 44 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 16.22,
    audio_end_seconds = 17.173
WHERE lesson_id = 44 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 17.173,
    audio_end_seconds = 19.421
WHERE lesson_id = 44 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-01-Passive-Simple/conversation-4-B2-01-4-Passive-Present-Chair.mp3',
    audio_start_seconds = 19.421,
    audio_end_seconds = 20.8
WHERE lesson_id = 44 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-1-B2-02-1-Past-Passive-Subway.mp3',
    audio_start_seconds = 6.1,
    audio_end_seconds = 8.22
WHERE lesson_id = 45 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-1-B2-02-1-Past-Passive-Subway.mp3',
    audio_start_seconds = 8.22,
    audio_end_seconds = 11.054
WHERE lesson_id = 45 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-1-B2-02-1-Past-Passive-Subway.mp3',
    audio_start_seconds = 11.054,
    audio_end_seconds = 12.861
WHERE lesson_id = 45 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-1-B2-02-1-Past-Passive-Subway.mp3',
    audio_start_seconds = 12.861,
    audio_end_seconds = 15.43
WHERE lesson_id = 45 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-1-B2-02-1-Past-Passive-Subway.mp3',
    audio_start_seconds = 15.43,
    audio_end_seconds = 17.486
WHERE lesson_id = 45 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-1-B2-02-1-Past-Passive-Subway.mp3',
    audio_start_seconds = 17.486,
    audio_end_seconds = 18.56
WHERE lesson_id = 45 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-2-B2-02-2-Past-Passive-Stadium.mp3',
    audio_start_seconds = 5.44,
    audio_end_seconds = 8.626
WHERE lesson_id = 45 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-2-B2-02-2-Past-Passive-Stadium.mp3',
    audio_start_seconds = 8.626,
    audio_end_seconds = 11.367
WHERE lesson_id = 45 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-2-B2-02-2-Past-Passive-Stadium.mp3',
    audio_start_seconds = 11.367,
    audio_end_seconds = 13.137
WHERE lesson_id = 45 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-2-B2-02-2-Past-Passive-Stadium.mp3',
    audio_start_seconds = 13.137,
    audio_end_seconds = 16.185
WHERE lesson_id = 45 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-2-B2-02-2-Past-Passive-Stadium.mp3',
    audio_start_seconds = 16.185,
    audio_end_seconds = 18.297
WHERE lesson_id = 45 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-2-B2-02-2-Past-Passive-Stadium.mp3',
    audio_start_seconds = 18.297,
    audio_end_seconds = 21.4
WHERE lesson_id = 45 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-3-B2-02-3-Past-Passive-Hotel.mp3',
    audio_start_seconds = 6.04,
    audio_end_seconds = 9.459
WHERE lesson_id = 45 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-3-B2-02-3-Past-Passive-Hotel.mp3',
    audio_start_seconds = 9.459,
    audio_end_seconds = 11.966
WHERE lesson_id = 45 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-3-B2-02-3-Past-Passive-Hotel.mp3',
    audio_start_seconds = 11.966,
    audio_end_seconds = 13.67
WHERE lesson_id = 45 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-3-B2-02-3-Past-Passive-Hotel.mp3',
    audio_start_seconds = 13.67,
    audio_end_seconds = 17.11
WHERE lesson_id = 45 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-3-B2-02-3-Past-Passive-Hotel.mp3',
    audio_start_seconds = 17.11,
    audio_end_seconds = 19.426
WHERE lesson_id = 45 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-3-B2-02-3-Past-Passive-Hotel.mp3',
    audio_start_seconds = 19.426,
    audio_end_seconds = 23.26
WHERE lesson_id = 45 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 5.4,
    audio_end_seconds = 7.009
WHERE lesson_id = 45 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 7.009,
    audio_end_seconds = 10.41
WHERE lesson_id = 45 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 10.41,
    audio_end_seconds = 13.036
WHERE lesson_id = 45 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 13.036,
    audio_end_seconds = 14.49
WHERE lesson_id = 45 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 14.49,
    audio_end_seconds = 16.644
WHERE lesson_id = 45 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 16.644,
    audio_end_seconds = 19.26
WHERE lesson_id = 45 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-02-Past-Passive/conversation-4-B2-02-4-Past-Passive-Golf.mp3',
    audio_start_seconds = 19.26,
    audio_end_seconds = 22.14
WHERE lesson_id = 45 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 3.38,
    audio_end_seconds = 5.659
WHERE lesson_id = 46 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 5.659,
    audio_end_seconds = 7.34
WHERE lesson_id = 46 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 7.34,
    audio_end_seconds = 9.865
WHERE lesson_id = 46 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 9.865,
    audio_end_seconds = 11.939
WHERE lesson_id = 46 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 11.939,
    audio_end_seconds = 13.603
WHERE lesson_id = 46 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 13.603,
    audio_end_seconds = 17.02
WHERE lesson_id = 46 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 17.02,
    audio_end_seconds = 19.938
WHERE lesson_id = 46 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 19.938,
    audio_end_seconds = 23.815
WHERE lesson_id = 46 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 23.815,
    audio_end_seconds = 28.54
WHERE lesson_id = 46 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 28.54,
    audio_end_seconds = 30.766
WHERE lesson_id = 46 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 30.766,
    audio_end_seconds = 33.127
WHERE lesson_id = 46 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 33.127,
    audio_end_seconds = 37.016
WHERE lesson_id = 46 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 37.016,
    audio_end_seconds = 38.378
WHERE lesson_id = 46 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 38.378,
    audio_end_seconds = 43.387
WHERE lesson_id = 46 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-1-B2-03-1-Was-Able-To-Test.mp3',
    audio_start_seconds = 43.387,
    audio_end_seconds = 46.02
WHERE lesson_id = 46 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 2.58,
    audio_end_seconds = 5.92
WHERE lesson_id = 46 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 5.92,
    audio_end_seconds = 10.739
WHERE lesson_id = 46 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 10.739,
    audio_end_seconds = 14.009
WHERE lesson_id = 46 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 14.009,
    audio_end_seconds = 18.67
WHERE lesson_id = 46 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 18.67,
    audio_end_seconds = 23.08
WHERE lesson_id = 46 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 23.08,
    audio_end_seconds = 27.053
WHERE lesson_id = 46 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 27.053,
    audio_end_seconds = 29.6
WHERE lesson_id = 46 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 29.6,
    audio_end_seconds = 34.4
WHERE lesson_id = 46 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 34.4,
    audio_end_seconds = 38.86
WHERE lesson_id = 46 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 38.86,
    audio_end_seconds = 40.68
WHERE lesson_id = 46 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-2-B2-03-2-Was-Able-To-Wedding.mp3',
    audio_start_seconds = 40.68,
    audio_end_seconds = 43.4
WHERE lesson_id = 46 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 2.94,
    audio_end_seconds = 7.594
WHERE lesson_id = 46 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 7.594,
    audio_end_seconds = 9.853
WHERE lesson_id = 46 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 9.853,
    audio_end_seconds = 12.511
WHERE lesson_id = 46 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 12.511,
    audio_end_seconds = 17.341
WHERE lesson_id = 46 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 17.341,
    audio_end_seconds = 20.105
WHERE lesson_id = 46 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 20.105,
    audio_end_seconds = 22.009
WHERE lesson_id = 46 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 22.009,
    audio_end_seconds = 24.629
WHERE lesson_id = 46 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 24.629,
    audio_end_seconds = 27
WHERE lesson_id = 46 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 27,
    audio_end_seconds = 28.475
WHERE lesson_id = 46 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 28.475,
    audio_end_seconds = 31.566
WHERE lesson_id = 46 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 31.566,
    audio_end_seconds = 33.323
WHERE lesson_id = 46 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-3-B2-03-3-Was-Able-To-Party.mp3',
    audio_start_seconds = 33.323,
    audio_end_seconds = 35.64
WHERE lesson_id = 46 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 2.6,
    audio_end_seconds = 6.39
WHERE lesson_id = 46 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 6.39,
    audio_end_seconds = 7.986
WHERE lesson_id = 46 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 7.986,
    audio_end_seconds = 10.229
WHERE lesson_id = 46 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 10.229,
    audio_end_seconds = 13.403
WHERE lesson_id = 46 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 13.403,
    audio_end_seconds = 17.568
WHERE lesson_id = 46 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 17.568,
    audio_end_seconds = 20.821
WHERE lesson_id = 46 AND position_index = 44;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 20.821,
    audio_end_seconds = 23.717
WHERE lesson_id = 46 AND position_index = 45;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 23.717,
    audio_end_seconds = 28.24
WHERE lesson_id = 46 AND position_index = 46;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 28.24,
    audio_end_seconds = 32.044
WHERE lesson_id = 46 AND position_index = 47;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 32.044,
    audio_end_seconds = 35.149
WHERE lesson_id = 46 AND position_index = 48;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 35.149,
    audio_end_seconds = 38.001
WHERE lesson_id = 46 AND position_index = 49;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-03-Was-Able-To/conversation-4-B2-03-4-Was-Able-To-Trip.mp3',
    audio_start_seconds = 38.001,
    audio_end_seconds = 40.64
WHERE lesson_id = 46 AND position_index = 50;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 5.48,
    audio_end_seconds = 7.356
WHERE lesson_id = 47 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 7.356,
    audio_end_seconds = 8.214
WHERE lesson_id = 47 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 8.214,
    audio_end_seconds = 9.991
WHERE lesson_id = 47 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 9.991,
    audio_end_seconds = 10.664
WHERE lesson_id = 47 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 10.664,
    audio_end_seconds = 13.001
WHERE lesson_id = 47 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 13.001,
    audio_end_seconds = 14.134
WHERE lesson_id = 47 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 14.134,
    audio_end_seconds = 16.458
WHERE lesson_id = 47 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 16.458,
    audio_end_seconds = 17.8
WHERE lesson_id = 47 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 17.8,
    audio_end_seconds = 22.859
WHERE lesson_id = 47 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-1-B2-04-1-Was-Going-To-Lunch.mp3',
    audio_start_seconds = 22.859,
    audio_end_seconds = 24.72
WHERE lesson_id = 47 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 6.28,
    audio_end_seconds = 8.401
WHERE lesson_id = 47 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 8.401,
    audio_end_seconds = 10.653
WHERE lesson_id = 47 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 10.653,
    audio_end_seconds = 13.223
WHERE lesson_id = 47 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 13.223,
    audio_end_seconds = 13.976
WHERE lesson_id = 47 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 13.976,
    audio_end_seconds = 15.699
WHERE lesson_id = 47 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 15.699,
    audio_end_seconds = 17.543
WHERE lesson_id = 47 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 17.543,
    audio_end_seconds = 21.99
WHERE lesson_id = 47 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 21.99,
    audio_end_seconds = 23.919
WHERE lesson_id = 47 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 23.919,
    audio_end_seconds = 25.78
WHERE lesson_id = 47 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-2-B2-04-2-Was-Going-To-Car.mp3',
    audio_start_seconds = 25.78,
    audio_end_seconds = 28
WHERE lesson_id = 47 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 7.16,
    audio_end_seconds = 8.841
WHERE lesson_id = 47 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 8.841,
    audio_end_seconds = 10.518
WHERE lesson_id = 47 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 10.518,
    audio_end_seconds = 11.219
WHERE lesson_id = 47 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 11.219,
    audio_end_seconds = 12.36
WHERE lesson_id = 47 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 12.36,
    audio_end_seconds = 14.77
WHERE lesson_id = 47 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 14.77,
    audio_end_seconds = 16.349
WHERE lesson_id = 47 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 16.349,
    audio_end_seconds = 18.27
WHERE lesson_id = 47 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 18.27,
    audio_end_seconds = 19.19
WHERE lesson_id = 47 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 19.19,
    audio_end_seconds = 21.476
WHERE lesson_id = 47 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 21.476,
    audio_end_seconds = 23.68
WHERE lesson_id = 47 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 23.68,
    audio_end_seconds = 26.313
WHERE lesson_id = 47 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 26.313,
    audio_end_seconds = 27.241
WHERE lesson_id = 47 AND position_index = 32;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-3-B2-04-3-Was-Going-To-Call.mp3',
    audio_start_seconds = 27.241,
    audio_end_seconds = 29.34
WHERE lesson_id = 47 AND position_index = 33;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 7.36,
    audio_end_seconds = 9.209
WHERE lesson_id = 47 AND position_index = 34;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 9.209,
    audio_end_seconds = 10.958
WHERE lesson_id = 47 AND position_index = 35;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 10.958,
    audio_end_seconds = 13.607
WHERE lesson_id = 47 AND position_index = 36;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 13.607,
    audio_end_seconds = 14.58
WHERE lesson_id = 47 AND position_index = 37;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 14.58,
    audio_end_seconds = 15.534
WHERE lesson_id = 47 AND position_index = 38;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 15.534,
    audio_end_seconds = 18.97
WHERE lesson_id = 47 AND position_index = 39;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 18.97,
    audio_end_seconds = 20.999
WHERE lesson_id = 47 AND position_index = 40;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 20.999,
    audio_end_seconds = 22.604
WHERE lesson_id = 47 AND position_index = 41;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 22.604,
    audio_end_seconds = 24.89
WHERE lesson_id = 47 AND position_index = 42;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-04-Was-Going-To/conversation-4-B2-04-4-Was-Going-To-Game.mp3',
    audio_start_seconds = 24.89,
    audio_end_seconds = 27
WHERE lesson_id = 47 AND position_index = 43;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 5.3,
    audio_end_seconds = 7.67
WHERE lesson_id = 48 AND position_index = 1;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 7.67,
    audio_end_seconds = 12.37
WHERE lesson_id = 48 AND position_index = 2;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 12.37,
    audio_end_seconds = 15.16
WHERE lesson_id = 48 AND position_index = 3;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 15.16,
    audio_end_seconds = 18.409
WHERE lesson_id = 48 AND position_index = 4;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 18.409,
    audio_end_seconds = 19.422
WHERE lesson_id = 48 AND position_index = 5;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 19.422,
    audio_end_seconds = 21.42
WHERE lesson_id = 48 AND position_index = 6;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 21.42,
    audio_end_seconds = 24.822
WHERE lesson_id = 48 AND position_index = 7;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-1-B2-05-1-Adverb-Placement-Europe.mp3',
    audio_start_seconds = 24.822,
    audio_end_seconds = 28.28
WHERE lesson_id = 48 AND position_index = 8;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 6.92,
    audio_end_seconds = 9.649
WHERE lesson_id = 48 AND position_index = 9;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 9.649,
    audio_end_seconds = 11.733
WHERE lesson_id = 48 AND position_index = 10;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 11.733,
    audio_end_seconds = 13.061
WHERE lesson_id = 48 AND position_index = 11;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 13.061,
    audio_end_seconds = 16.076
WHERE lesson_id = 48 AND position_index = 12;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 16.076,
    audio_end_seconds = 17.31
WHERE lesson_id = 48 AND position_index = 13;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 17.31,
    audio_end_seconds = 19.021
WHERE lesson_id = 48 AND position_index = 14;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 19.021,
    audio_end_seconds = 22.787
WHERE lesson_id = 48 AND position_index = 15;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-2-B2-05-2-Adverb-Placement-Weekend.mp3',
    audio_start_seconds = 22.787,
    audio_end_seconds = 23.8
WHERE lesson_id = 48 AND position_index = 16;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 6.6,
    audio_end_seconds = 9.474
WHERE lesson_id = 48 AND position_index = 17;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 9.474,
    audio_end_seconds = 11.739
WHERE lesson_id = 48 AND position_index = 18;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 11.739,
    audio_end_seconds = 14.553
WHERE lesson_id = 48 AND position_index = 19;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 14.553,
    audio_end_seconds = 15.951
WHERE lesson_id = 48 AND position_index = 20;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 15.951,
    audio_end_seconds = 19.284
WHERE lesson_id = 48 AND position_index = 21;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 19.284,
    audio_end_seconds = 21.382
WHERE lesson_id = 48 AND position_index = 22;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-3-B2-05-3-Adverb-Placement-Restaurant.mp3',
    audio_start_seconds = 21.382,
    audio_end_seconds = 23.78
WHERE lesson_id = 48 AND position_index = 23;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 4.28,
    audio_end_seconds = 9.103
WHERE lesson_id = 48 AND position_index = 24;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 9.103,
    audio_end_seconds = 13.452
WHERE lesson_id = 48 AND position_index = 25;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 13.452,
    audio_end_seconds = 15.769
WHERE lesson_id = 48 AND position_index = 26;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 15.769,
    audio_end_seconds = 17.467
WHERE lesson_id = 48 AND position_index = 27;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 17.467,
    audio_end_seconds = 20.978
WHERE lesson_id = 48 AND position_index = 28;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 20.978,
    audio_end_seconds = 22.71
WHERE lesson_id = 48 AND position_index = 29;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 22.71,
    audio_end_seconds = 25.84
WHERE lesson_id = 48 AND position_index = 30;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 25.84,
    audio_end_seconds = 27.801
WHERE lesson_id = 48 AND position_index = 31;

UPDATE lesson_sentences
SET audio_url = '/audio/elllo/B2/B2-05-Adverb-Placement/conversation-4-B2-05-4-Adverb-Placement-Test.mp3',
    audio_start_seconds = 27.801,
    audio_end_seconds = 29.4
WHERE lesson_id = 48 AND position_index = 32;
