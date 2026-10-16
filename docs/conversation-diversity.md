# Conversation session diversity

New curated sessions store an internal `sessionBlueprint` (content version, branch ID,
four-stage journey) and the complete validated opening task in `state_json`.
Reloads/retries reuse this content. Existing sessions without a blueprint keep the old flow.
No schema migration or frontend changes are required.

## Coverage

The bank has 66 meaningful branches across all 24 scenarios. Cafe, hotel and directions
have eight branches each; the other 21 scenarios have two each. Openings are short warmups:
A1 asks for two explicit details; A2/B1 additionally ask for a reason. A2 and B1 share the
warmup; generated followups retain their existing distinct CEFR bounds. These are not
66 new top-level scenarios or a proficiency test.

Creation uses the existing user-row transaction lock to choose an unseen, then least
recently reserved branch from the last ten sessions with the same user/scenario/level.
Unopened sessions reserve a branch too. Legacy curated sessions count as the standard
branch. Once the finite bank is exhausted, the oldest branch is reused deliberately.

## Recent practice and generation

Creation snapshots tasks already opened in up to ten matching sessions, including
unfinished sessions. It copies only questions, utterances, skills and answer keys,
never learner replies, reports or account identifiers. Historical snapshots are not
copied recursively. Tasks opened in other sessions after this snapshot are not included.

Generation and the existing independent review receive only historical question/skill/
answer-meaning summaries plus the saved journey. Local checks reject exact/near utterance
duplicates and complete repeated answer sets; the existing reviewer checks semantic
repetition, including cosmetic name/number changes. Shared skills or a single common
detail are allowed. Semantic judgment remains model-dependent, not a uniqueness guarantee.

The same two-attempt/45-second deadline and daily AI budget apply. No additional model
request is added to the normal generation flow; a rejected task uses the existing single
repair opportunity. Network failures do not reroll the branch or lose saved answers.
Curated openings need no text-generation request. Internal metadata and history are not
returned to the learner-facing API.

## Operation and checks

`DICTALAB_CONVERSATION_DIVERSITY=false` disables diversification for future sessions only.
Saved blueprints continue to work. Default is enabled when curated starters are enabled.

Run:

```sh
mvn --batch-mode --no-transfer-progress -Dtest=ConversationContentTest,ConversationUxIntegrationTest,ConversationIntegrationTest test
```

Tests cover all bank entries/levels, evidence grounding, rotation/exhaustion, concurrent
reservations, stable reloads, privacy/isolation, bounded history, duplicate repair with the
shared deadline, disabling the feature and legacy compatibility. AI tests use mocks;
they do not certify the external model's pedagogical quality or latency. Monitor fixed
validation code `REPEATED_RECENT_TASK`, generation failures and repair frequency before
expanding the curated bank or changing rejection thresholds.
