# GitHub Copilot Project Context Prompt

Use this as your initial prompt in Copilot Chat (or paste into a COPILOT_CONTEXT.md file at the repo root so Copilot always has it).

---

## Prompt:

I am building a year-long Java research project for my Computer Science program at Concordia College. The project is a **Senate Confirmation Hearing Sentiment Analysis Platform**. Here is the full context:

### What the project does:

This system ingests U.S. Senate Judiciary Committee confirmation hearing transcripts (`.docx` files from the Government Publishing Office) and performs multi-layered NLP analysis:

1. **Transcript Parsing** — Reads `.docx` files, identifies individual speaker turns (senators, nominees, presenters), and segments the raw text into structured exchanges. Senate hearing transcripts use a semi-consistent format where speaker transitions look like `"Senator Sessions."`, `"Chairman Leahy."`, `"Judge Chen."`, `"Ms. Gee."`, `"Mr. Kappos."` at the start of a paragraph. Bracketed annotations like `[Laughter.]` or `[The information appears as a submission for the record.]` are editorial metadata, not speech.

2. **Speaker Resolution** — Maps different references to the same person (`"Senator Sessions"`, `"Ranking Member Sessions"`, `"Mr. Sessions"` → same canonical entity). Tags speakers with role (SENATOR, NOMINEE, PRESENTER, CHAIRMAN, RANKING_MEMBER), party (D/R/I), and state.

3. **Legal Precedent Detection** — (planned) Scans turn text for references to Supreme Court cases, formal citations, constitutional amendments, statutes, and legal doctrines. Uses regex patterns and an expandable dictionary.

4. **Dual-Target Sentiment Analysis** using Stanford CoreNLP:
   - **Nominee Approval Scoring** — For each senator's turn, score how favorable/hostile they appear toward the nominee's confirmation. Scale: -1.0 (hostile) to +1.0 (supportive).
   - **Precedent Sentiment Scoring** — (planned) When a legal precedent is referenced, score the speaker's stance toward that precedent.
   - **Rule-based scoring layer** — (planned) Pattern matching for hostile question types, supportive framing, and precedent stance signals.

5. **SQL Database Storage** — MySQL 8.0 with `DatabaseManager` (reusable JDBC class), `Hearing` entity (full CRUD), and implemented entities for `Speaker`, `Nomination`, `Turn`, `ScoringRun`, and `TurnScore`. The full 7-table + 1-view schema is implemented in `sql/schema.sql`, and `TurnScorer` now contains pipeline-to-database persistence. JSON remains a parallel artifact for portability and validation.

6. **Batch Processing** — (planned) Process hundreds of transcripts from a configurable input directory.

7. **Interactive Viewer** — (planned) A front-end to browse results by hearing, nominee, senator, or precedent. Architecture undecided (web-based vs. local).

### Tech Stack (current):
- **Language:** Java 17.0.9 (Oracle JDK, build 17.0.9+11-LTS-201)
- **Build:** Maven 3.9.6 via Maven Wrapper (`mvnw.cmd`) — no global Maven install required. `pom.xml` manages all dependencies.
- **NLP Engine:** Stanford CoreNLP 4.5.10 — pulled from Maven Central (core + models + models-english). All 30 dependency JARs resolved to `~/.m2/repository/`.
- **Parser:** Shift-Reduce beam parser (`englishSR.beam.ser.gz`) — O(n) linear time, ~17-26 sec load, F1 88.6. Requires `stanford-corenlp-4.5.10-models-english.jar` (424 MB, English extra models, not in default models JAR).
- **JSON Processing:** `javax.json` 1.1.6 (GlassFish implementation) — **ships with CoreNLP** in `jakarta.json-1.1.6.jar`. NOTE: JAR filename says "jakarta" but internal package is `javax.json.*` (pre-namespace-migration).
- **Database:** MySQL 8.0 (local instance). Driver: `mysql-connector-j` 8.0.33 (from Maven). Database: `sentiment_analysis`. Credentials stored in `db.properties` (gitignored).
- **Document Parsing:** PowerShell `.docx → .txt` extraction (inline in `run.ps1` via .NET System.IO.Compression)
- **OS:** Windows, PowerShell 5.1 (NOT PowerShell 7 — `utf8NoBOM` encoding not available)

### Tech Stack (planned — not yet integrated):
- **Document Parsing:** Apache POI (for native Java `.docx` reading)
- **Testing:** JUnit 5
- **Logging:** SLF4J with slf4j-simple

### Data flow (current operational pipeline):
```
.docx files (input/)
    → run.ps1 Extract-TextFromDocx (PowerShell .NET — .docx → plain text)
    → SpeakerTurnParser (regex state machine — identify speaker turns)
    → TargetResolver (5-layer strategy — who is each turn directed at)
    → TurnScorer (CoreNLP SR parser + sentiment — per-turn scoring)
    → Aggregation (by senator→nominee interaction pairs)
    → Output: JSON + TXT reports (output/)
    → MySQL schema + CRUD entities
    → TurnScorer pipeline-to-database persistence (default ON, disable with `--no-db`)
```

### Project structure:
```
SentimentAnalysisProject/
├── pom.xml                     # Maven project descriptor (CoreNLP 4.5.10, mysql-connector-j 8.0.33)
├── mvnw.cmd                    # Maven Wrapper script (no global Maven install needed)
├── .mvn/wrapper/               # Maven Wrapper support files (maven-wrapper.jar, .properties)
├── db.properties               # MySQL credentials — GITIGNORED, never committed
├── src/main/java/              # All Java sources (default package)
│   ├── SentimentTest.java      # Standalone sentence-level sentiment smoke test
│   ├── SpeakerTurn.java        # POJO: speaker turn (title, lastName, text, turnNumber, startLine)
│   ├── SpeakerTurnParser.java  # Regex state machine: transcript → List<SpeakerTurn>
│   ├── NomineeInfo.java        # POJO: nominee (firstName, lastName, position, titleUsed)
│   ├── HearingSection.java     # POJO: one nominee panel (date, nominees, line range)
│   ├── ResolvedTarget.java     # Result of target resolution (nominee, method, confidence)
│   ├── TargetResolver.java     # 5-layer resolution: SELF → DIRECT → RESPONSE → CONTEXT → DEFAULT
│   ├── ScoredTurn.java         # Wraps ResolvedTarget + sentiment scores
│   ├── TurnScorer.java         # Full pipeline: parse → resolve → CoreNLP score → aggregate → JSON + TXT
│   ├── DatabaseManager.java    # Reusable DB class: connect, disconnect, execute (parameterized queries)
│   ├── Hearing.java            # Hearing entity with CRUD: save, load, loadAll, delete
│   ├── Speaker.java            # Speaker entity with CRUD: save, load, loadAll, delete
│   ├── Nomination.java         # Nomination entity with CRUD: save, load, loadAll, delete
│   ├── Turn.java               # Turn entity with CRUD: save, load, loadAll, delete
│   ├── ScoringRun.java         # Scoring run entity with CRUD: save, load, loadAll, delete
│   └── TurnScore.java          # Turn score entity with CRUD: save, load, loadAll, delete
├── sql/
│   └── schema.sql              # Full implemented schema: 7 tables + interactions_view
├── CoreNLP/
│   └── run.ps1                 # Compiler + runner: uses mvnw.cmd, -Parse/-Resolve/-Score modes
├── input/                      # Transcript files (.docx, .txt)
│   ├── hearing_text.txt        # Full test hearing (PN908 S.Hrg. 111-695, Pt. 3)
│   ├── qa_exchange_test.txt    # Small multi-speaker excerpt for smoke tests
│   └── sample_test.txt         # Minimal test input
├── output/                     # Scoring results (gitignored, regeneratable)
│   ├── score_<inputLabel>_YYYY-MM-DD_HHmmss.json  # Timestamped structured scoring data
│   └── score_<inputLabel>_YYYY-MM-DD_HHmmss.txt   # Timestamped human-readable report
├── target/                     # Maven build output (gitignored)
│   ├── classes/                # Compiled .class files
│   └── cp.txt                  # Maven dependency classpath (generated by run.ps1)
├── docs/copilot/
│   └── COPILOT_PROMPT.md       # This file
└── .gitignore
```

### What I need you to generate:

> **DO NOT scaffold the entire project at once.** This is a year-long research project
> built incrementally. Only generate code for the current phase. Each phase should
> compile and run before moving to the next.

> **CRITICAL: Always consult me before making logic decisions.** Do not make multiple
> architectural or algorithmic choices without asking for my input first. Present
> options clearly and wait for my decision.

> **CRITICAL: Always leverage libraries that ship with CoreNLP** rather than building
> from scratch or adding external dependencies. CoreNLP bundles many useful libraries
> (javax.json, ejml, joda-time, protobuf, etc.) — use them.

### Current Phase: Phase 4 — Pipeline Persistence Integrated; Validation/Hardening

**Status:** Phase 2 scoring and Phase 3 database foundation are complete, and
pipeline-to-database persistence has been integrated into `TurnScorer`.
Current focus is runtime validation (DB credentials/environment), idempotency,
and hardening around operational workflows.

**What exists and works end-to-end:**

- **SentimentTest.java** — Standalone sentence-level sentiment (5-class label + [-2,+2] score)
- **SpeakerTurn.java** — POJO for a speaker turn (title, lastName, text, turnNumber, startLine); `getSpeakerLabel()`, `hasSubstantiveText()`
- **SpeakerTurnParser.java** — Regex state machine segmenting transcript text into speaker turns; handles TOC filtering, annotation skipping, multi-line continuation; standalone runner
- **NomineeInfo.java** — Lightweight nominee model (firstName, lastName, position, titleUsed); `matchesLastName()`, `getDisplayName()`
- **HearingSection.java** — One nominee panel (date, nominees map, line range); `containsLine()`, `findNominee()`
- **ResolvedTarget.java** — Target resolution result (nominee, method enum, confidence 0.0–1.0); methods: SELF, DIRECT_ADDRESS, RESPONSE_PAIR, PRIOR_CONTEXT, SECTION_DEFAULT, UNKNOWN
- **TargetResolver.java** — 5-layer resolution strategy; detects NOMINATIONS headers, parses nominee names, corrects titles from transcript usage, outputs interaction matrix
- **ScoredTurn.java** (70 lines) — Wraps `ResolvedTarget` + sentiment results: `sentenceCount`, `totalScore`, `getAvgScore()`, `getWeightedScore()`, `isSelfTurn()`, `hasSpecificTarget()`, `getSpeakerLabel()`
- **TurnScorer.java** (~613 lines) — Full pipeline class:
  - Parses arguments (`-v` verbose, `-o <dir>` output directory, `--no-db` disable DB writes)
  - Reads transcript, calls `SpeakerTurnParser.parse()`, calls `TargetResolver.resolve()`
  - Builds CoreNLP pipeline: `tokenize,ssplit,pos,parse,sentiment` with SR beam parser
  - Scores each substantive turn via `scoreTurn()` (strips bracketed annotations first)
  - Aggregates by (senator, nominee) pairs in `InteractionScore` objects
  - Prints to console: Nominee Scorecard, Senator Voting Profile, Summary
  - Writes `output/score_<inputLabel>_<timestamp>.json` — structured JSON via `javax.json` builders
  - Writes `output/score_<inputLabel>_<timestamp>.txt` — human-readable report
  - Persists scoring results into MySQL by default via `ScoringPersistence`
- **DatabaseManager.java** (~270 lines) — Reusable database class (satisfies CSC 470 Lab 01):
  - `connect()` — opens JDBC connection using `db.properties` (host, port, name, user, password)
  - `disconnect()` — closes connection and cleans up resources
  - `execute(String sql, Map<Integer, Object> params)` — parameterized INSERT/UPDATE/DELETE, returns affected rows
  - `executeQuery(String sql, Map<Integer, Object> params)` — parameterized SELECT, returns `List<Map<String, Object>>`
  - `executeInsert(String sql, Map<Integer, Object> params)` — INSERT returning generated key
  - Loads credentials from `db.properties` (searches current dir, then parent dir)
  - JDBC URL: `jdbc:mysql://host:port/dbName?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
- **Hearing.java** (~215 lines) — Hearing entity with full CRUD:
  - Fields: `id` (int, 0=new), `hearingDate`, `session`, `serialNumber`, `committee`, `sourceFile`, `title`
  - `save(DatabaseManager db)` — INSERT if id==0, UPDATE if id>0; sets `this.id` from generated key
  - `load(DatabaseManager db, int id)` — static, SELECT by PK, returns Hearing or null
  - `loadAll(DatabaseManager db)` — static, returns `List<Hearing>`
  - `delete(DatabaseManager db, int id)` — static, DELETE by PK, returns boolean
  - Maps to `hearings` table (see `sql/schema.sql`)
- **Speaker.java** — Speaker entity with full CRUD (`speakers` table)
- **Nomination.java** — Nomination entity with full CRUD (`nominations` table)
- **Turn.java** — Turn entity with full CRUD (`turns` table)
- **ScoringRun.java** — Scoring run entity with full CRUD (`scoring_runs` table)
- **TurnScore.java** — Turn score entity with full CRUD (`turn_scores` table)
- **run.ps1** (~165 lines) — Compiler + runner with 4 modes:
  - Uses `mvnw.cmd compile` for compilation, `mvnw.cmd dependency:build-classpath` for classpath
  - Default: `SentimentTest`
  - `-Parse`: `SpeakerTurnParser`
  - `-Resolve`: `TargetResolver`
  - `-Score`: `TurnScorer` (auto-creates `output/`, passes `-o`)

**JSON output schema:**
```json
{
  "metadata": {
    "hearingFile": "hearing_text.txt",
    "scoredAt": "2026-02-23T16:30:35",
    "parserModel": "englishSR.beam.ser.gz",
    "totalTurns": 400,
    "scoredTurns": 394,
    "senatorTurns": 248,
    "selfTurns": 146,
    "skippedTurns": 6,
    "sentencesProcessed": 1592,
    "uniquePairs": 50,
    "avgConfidence": 0.8723,
    "scoringTimeSeconds": 67.048
  },
  "turns": [
    {
      "turnNumber": 3,
      "speaker": "Senator Hatch",
      "target": "Ms. Martin",
      "resolutionMethod": "DIRECT_ADDRESS",
      "confidence": 0.95,
      "sentenceCount": 14,
      "totalScore": 3,
      "avgScore": 0.2143,
      "weightedScore": 0.2036
    }
  ],
  "interactions": [
    {
      "senator": "Senator Hatch",
      "nominee": "Ms. Martin",
      "turns": 6,
      "sentences": 39,
      "avgWeightedScore": 0.12,
      "avgRawScore": 0.14,
      "totalWeighted": 0.72
    }
  ],
  "nominees": [
    {
      "nominee": "Ms. Martin",
      "overallApproval": 0.09,
      "totalTurns": 40,
      "totalSentences": 171,
      "senators": [
        { "senator": "Senator Specter", "score": -0.06, "turns": 2, "sentences": 10 }
      ]
    }
  ]
}
```

**Validated results (full hearing — PN908 S.Hrg. 111-695, Pt. 3):**
- 400 speaker turns parsed, 28 unique speakers, 165,522 chars of speech
- 3 hearing sections detected (July 29, Sept 9, Sept 23, 2009), 12 nominees across 3 panels
- 400/400 turns resolved to targets (0 UNKNOWN):
  - SELF: 152 (nominee speaking — confidence 1.0)
  - RESPONSE_PAIR: 103 (nominee responds after senator — 0.90)
  - PRIOR_CONTEXT: 79 (inherits target from ongoing Q&A — 0.70)
  - DIRECT_ADDRESS: 46 (speaker names nominee in text — 0.95)
  - SECTION_DEFAULT: 20 (falls back to panel — 0.30–0.50)
- 394 turns scored by CoreNLP, 6 skipped (non-substantive)
- 1,592 sentences processed, 50 unique senator→nominee pairs
- **Scoring time: 67 seconds** (SR beam parser) vs 30-60+ minutes with PCFG parser
- Top speakers: Senator Sessions (61 turns, 23K chars), Senator Whitehouse (46), Senator Franken (43), Chairman Leahy (34)

**Target resolution strategy (priority order):**
1. **SELF** — speaker IS a nominee on the current panel (confidence 1.0)
2. **DIRECT_ADDRESS** — speaker names a nominee in their text (confidence 0.95)
3. **RESPONSE_PAIR** — nominee responds immediately after a senator's turn (confidence 0.90)
4. **PRIOR_CONTEXT** — inherits target from ongoing Q&A exchange (confidence 0.70)
5. **SECTION_DEFAULT** — falls back to panel (single nominee = 0.50, multi = 0.30)

**CoreNLP pipeline configuration:**
```java
annotators = tokenize,ssplit,pos,parse,sentiment
parse.model = edu/stanford/nlp/models/srparser/englishSR.beam.ser.gz
```
- `pos` annotator is **required** by SR parser (it uses POS tags as input features)
- SR beam parser chosen over PCFG for linear-time O(n) parsing vs O(n³)
- SR beam parser chosen over SR greedy for higher F1 (88.6 vs 86.1)

**Known parser limitations to address later:**
- Standalone speaker names used as section intros create small bogus turns
- ALL-CAPS section headings between speakers get absorbed as turn text
- `[Off microphone.]` turns have no useful content (filtered via `hasSubstantiveText()`)

---

### Tentative Future Work (DO NOT START — ask me for a clear plan first)

The following items have been discussed but **no implementation should begin without
presenting a clear plan and getting explicit approval**. Each item needs scoping,
approach options, and my sign-off before any code is written.

**Near-term (logical next steps):**
1. **Runtime DB validation and hardening** — Validate end-to-end persistence with working credentials in `db.properties`; finalize rerun policy and child-row replacement behavior.
2. **Apache POI `.docx` reading in Java** — Replace the PowerShell `Extract-TextFromDocx` function with native Java reading via Apache POI. Would eliminate the PowerShell-to-Java handoff for document extraction.
3. **Speaker alias resolution** — Merge alternate titles for the same person (e.g., "Ranking Member Sessions" → "Senator Sessions", "The Chairman" → "Chairman Leahy"). Currently each unique title+lastName combo is treated as distinct. Will populate `speakers.canonical_name` and potentially merge `speaker_id` FKs.
4. **Legal precedent detection** — Regex + dictionary approach to find case references (`"Roe v. Wade"`, `"554 U.S. 570"`, `"14th Amendment"`, `"stare decisis"`). Needs a `precedents.json` dictionary of ~200 landmark cases. Future tables (`precedent_dict`, `precedent_refs`) would FK to `turns`.
5. **Rule-based scoring layer** — Pattern matching for hostile question types (`"Isn't it true that..."`, `"How can you justify..."`), supportive framing (`"Your impressive record..."`), and precedent stance signals (`"wrongly decided"`, `"settled law"`). Would supplement CoreNLP's tree-based sentiment.
6. **Windowed precedent sentiment** — When a legal precedent is referenced, score the speaker's stance using a ±2-sentence window around the mention.

**Medium-term (infrastructure):**
7. **Batch processing** — Process multiple transcripts from a configurable input directory. Idempotent re-processing, error recovery, progress tracking. Each hearing gets a `hearings` row; cascade structure handles everything below it.

**Long-term (visualization):**
8. **Interactive viewer** — Front-end to browse results by hearing, nominee, senator, or precedent. Architecture undecided (web-based vs. local desktop app). The `interactions_view` (computed from joins on `turn_scores` + `turns` + `nominations` + `speakers`) replaces the need to pre-aggregate data.

---

### Database Schema (IMPLEMENTED)

The current `schema.sql` contains the implemented 7-table + 1-view schema.
It stores all pipeline inputs (transcripts,
speakers, hearing structure) and all outputs (turns, sentiment scores, scoring
metadata). Interactions/aggregations are computed via SQL queries, not stored.

Current `sql/schema.sql` is optimized for local development resets and includes
drop-and-recreate statements. Before production-style use, replace that behavior
with versioned migrations.

**Design decisions made:**
- Speakers table with FK linkage from the start (not deferred)
- Interactions are computed via views/queries (not materialized tables)
- Full turn text stored in `turns.text` (TEXT column) — essential for re-scoring,
  precedent detection, rule-based scoring, full-text search, and the interactive viewer
- All FKs use ON DELETE CASCADE — deleting a hearing removes all child records

**Table relationships:**
```
speakers (canonical entities — reusable across all hearings)
  ├── turns.speaker_id → FK
  ├── nominations.speaker_id → FK
  └── Shared across hearings; one row per unique person

hearings (root entity — one per transcript)
  ├── hearing_sections → hearing_id FK (CASCADE)
  ├── turns → hearing_id FK (CASCADE)
  └── scoring_runs → hearing_id FK (CASCADE)

hearing_sections (panels within a hearing)
  └── nominations → hearing_section_id FK (CASCADE)

nominations (a person nominated for a position in a section)
  └── turn_scores.target_nomination_id → FK

scoring_runs (one per pipeline execution)
  └── turn_scores.scoring_run_id → FK (CASCADE)
```

**Table definitions:**

```sql
-- 1. SPEAKERS — canonical person entities, reusable across hearings
-- first_name is nullable for senators (pipeline only knows "Senator Sessions")
-- canonical_name starts NULL, populated during speaker alias resolution phase
speakers (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    first_name      VARCHAR(100)            -- nullable for senators
    last_name       VARCHAR(100) NOT NULL,
    canonical_name  VARCHAR(200)            -- e.g. "Jeff Sessions" — populated later
    party           CHAR(1)                 -- D, R, I — nullable, enriched later
    state           VARCHAR(50)             -- nullable, enriched later
    role            ENUM('SENATOR','NOMINEE','PRESENTER','OTHER') NOT NULL,
    INDEX idx_last_name (last_name)
)

-- 2. HEARINGS — root entity, one per transcript processed
hearings (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    hearing_date    DATE,
    session         VARCHAR(50),            -- e.g. "111th Congress, 1st Session"
    serial_number   VARCHAR(100),           -- e.g. "S.Hrg. 111-695, Pt. 3"
    committee       VARCHAR(255),           -- e.g. "Senate Judiciary Committee"
    source_file     VARCHAR(500),           -- original filename
    title           VARCHAR(1000),
    UNIQUE KEY uq_source_file (source_file(255))
)

-- 3. HEARING_SECTIONS — panels within a hearing (detected from NOMINATIONS headers)
hearing_sections (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id      INT NOT NULL → FK hearings (CASCADE),
    section_number  INT NOT NULL,           -- 1-based sequential
    header_text     TEXT,                   -- full multi-line NOMINATIONS header
    section_date    VARCHAR(50),            -- e.g. "JULY 29, 2009"
    start_line      INT,                    -- 0-based line in source
    end_line        INT,                    -- exclusive
    UNIQUE KEY uq_hearing_section (hearing_id, section_number)
)

-- 4. NOMINATIONS — a person nominated for a position in a specific section
-- Named "nominations" not "nominees" to distinguish the EVENT from the PERSON
-- hearing_id is denormalized (could be derived via hearing_sections) for query convenience
nominations (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id          INT NOT NULL → FK hearings (CASCADE),
    hearing_section_id  INT NOT NULL → FK hearing_sections (CASCADE),
    speaker_id          INT NOT NULL → FK speakers,
    position            VARCHAR(500),       -- e.g. "U.S. Circuit Judge for the Eleventh Circuit"
    title_used          VARCHAR(20),        -- e.g. "Ms.", "Judge", "Mr."
    UNIQUE KEY uq_section_speaker (hearing_section_id, speaker_id)
)

-- 5. TURNS — every speaker turn, full text stored
-- speaker_label is the as-spoken label (e.g. "Chairman Leahy") preserved for display
-- speaker_id links to canonical identity in speakers table
turns (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id          INT NOT NULL → FK hearings (CASCADE),
    hearing_section_id  INT → FK hearing_sections (SET NULL),  -- nullable for pre-panel turns
    speaker_id          INT NOT NULL → FK speakers,
    turn_number         INT NOT NULL,       -- 1-based sequential within hearing
    speaker_label       VARCHAR(200),       -- as-spoken: "Senator Sessions", "Chairman Leahy"
    text                TEXT NOT NULL,       -- full speech text
    start_line          INT,                -- 0-based line in source
    char_count          INT,                -- length of text
    is_substantive      BOOLEAN DEFAULT TRUE, -- false for [Off microphone.] etc.
    UNIQUE KEY uq_hearing_turn (hearing_id, turn_number),
    INDEX idx_speaker (speaker_id)
)

-- 6. SCORING_RUNS — one per pipeline execution, tracks model + timing
scoring_runs (
    id                      INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id              INT NOT NULL → FK hearings (CASCADE),
    scored_at               DATETIME NOT NULL,
    parser_model            VARCHAR(200),   -- e.g. "englishSR.beam.ser.gz"
    total_turns             INT,
    scored_turns            INT,
    senator_turns           INT,
    self_turns              INT,
    skipped_turns           INT,
    sentences_processed     INT,
    unique_pairs            INT,
    avg_confidence          DECIMAL(6,4),
    scoring_time_seconds    DECIMAL(8,3)
)

-- 7. TURN_SCORES — per-turn sentiment scores, linked to a scoring run
-- target_nomination_id is nullable (turns with UNKNOWN target have no nomination)
-- Supports re-scoring: same turn can have scores from multiple scoring_runs
turn_scores (
    id                      INT AUTO_INCREMENT PRIMARY KEY,
    turn_id                 INT NOT NULL → FK turns (CASCADE),
    scoring_run_id          INT NOT NULL → FK scoring_runs (CASCADE),
    target_nomination_id    INT → FK nominations,  -- nullable for UNKNOWN targets
    resolution_method       ENUM('SELF','DIRECT_ADDRESS','RESPONSE_PAIR',
                                 'PRIOR_CONTEXT','SECTION_DEFAULT','UNKNOWN') NOT NULL,
    confidence              DECIMAL(4,3),   -- 0.000–1.000
    sentence_count          INT,
    total_score             INT,            -- sum of per-sentence [-2,+2] scores
    avg_score               DECIMAL(6,4),   -- totalScore / sentenceCount
    weighted_score          DECIMAL(6,4),   -- avgScore * confidence
    UNIQUE KEY uq_turn_run (turn_id, scoring_run_id),
    INDEX idx_scoring_run (scoring_run_id),
    INDEX idx_target (target_nomination_id)
)
```

**Computed view (replaces JSON interactions/nominees arrays):**
```sql
-- interactions_view — senator→nominee aggregations, computed not stored
-- Replaces the pre-aggregated "interactions" and "nominees" arrays in JSON output
-- GROUP BY scoring_run_id + speaker + nomination → turns, sentences, avg scores
CREATE VIEW interactions_view AS
SELECT
    ts.scoring_run_id,
    s_speaker.last_name   AS senator_last_name,
    t.speaker_label       AS senator_label,
    s_nominee.last_name   AS nominee_last_name,
    CONCAT(n.title_used, ' ', s_nominee.last_name) AS nominee_label,
    COUNT(*)              AS turns,
    SUM(ts.sentence_count) AS sentences,
    AVG(ts.weighted_score) AS avg_weighted_score,
    AVG(ts.avg_score)      AS avg_raw_score,
    SUM(ts.weighted_score) AS total_weighted
FROM turn_scores ts
JOIN turns t ON ts.turn_id = t.id
JOIN speakers s_speaker ON t.speaker_id = s_speaker.id
JOIN nominations n ON ts.target_nomination_id = n.id
JOIN speakers s_nominee ON n.speaker_id = s_nominee.id
WHERE ts.resolution_method != 'SELF'
  AND ts.resolution_method != 'UNKNOWN'
GROUP BY ts.scoring_run_id, t.speaker_id, n.id;
```

**Target data mapping by pipeline stage (used for persistence integration design):**

| Pipeline Stage | Tables Populated |
|---|---|
| Transcript import | `hearings` (metadata) |
| `SpeakerTurnParser.parse()` | `speakers` (from turn labels), `turns` (full text) |
| `TargetResolver.resolve()` | `hearing_sections`, `nominations`, `speakers` (nominees with first names) |
| `TurnScorer.scoreTurn()` | `scoring_runs`, `turn_scores` (planned integration target) |

**Known data gaps at import time:**
- **Senators have no first name** — pipeline only knows "Senator Sessions". `speakers.first_name` is nullable. `speakers.canonical_name` will be populated during future alias resolution phase.
- **No party/state data** — not in transcripts. Will require external enrichment (congressional directory lookup or manual entry).
- **SECTION_DEFAULT turns have UNKNOWN target** — 20 of 400 turns in the test hearing fall back to panel default with no specific nominee. `turn_scores.target_nomination_id` is nullable for these.
- **Cross-hearing speaker dedup** — Two hearings both having "Senator Johnson" could be different people (Tim Johnson vs Ron Johnson). Deferred to alias resolution phase.

**Example research queries this schema enables:**
```sql
-- All turns where Senator Sessions scored below -0.5 across all hearings
SELECT h.serial_number, t.turn_number, t.text, ts.weighted_score
FROM turn_scores ts
JOIN turns t ON ts.turn_id = t.id
JOIN speakers s ON t.speaker_id = s.id
JOIN hearings h ON t.hearing_id = h.id
WHERE s.last_name = 'Sessions' AND s.role = 'SENATOR'
  AND ts.weighted_score < -0.5;

-- Average nominee approval by resolution method
SELECT ts.resolution_method, AVG(ts.weighted_score), COUNT(*)
FROM turn_scores ts
GROUP BY ts.resolution_method;

-- Full-text search for legal precedent mentions
SELECT t.speaker_label, t.text, ts.weighted_score
FROM turns t
JOIN turn_scores ts ON ts.turn_id = t.id
WHERE t.text LIKE '%Roe v. Wade%';

-- Compare scores between two different scoring runs
SELECT t.turn_number, ts1.weighted_score AS run1, ts2.weighted_score AS run2
FROM turns t
JOIN turn_scores ts1 ON ts1.turn_id = t.id AND ts1.scoring_run_id = 1
JOIN turn_scores ts2 ON ts2.turn_id = t.id AND ts2.scoring_run_id = 2;
```

---

### Key design principles:
- **Pipeline pattern** — each processing stage is a separate class that can run independently
- **Maven-managed dependencies** — all JARs from Maven Central via `pom.xml`, no manual JAR downloads. Maven Wrapper (`mvnw.cmd`) ensures reproducible builds with no global install.
- **Leverage CoreNLP-bundled libraries** — javax.json, ejml, joda-time, protobuf, etc. are all available without adding external dependencies
- **JSON as intermediate format** — keeps all doors open for SQL, pandas, web dashboards
- **DatabaseManager pattern** — single reusable class for all DB operations. Entity classes (Hearing, etc.) use `save(db)` / `load(db, id)` / `delete(db, id)` CRUD pattern.
- **Incremental build** — each phase compiles and runs before moving to the next
- **Consult before deciding** — always present options and get approval before logic/architecture choices
