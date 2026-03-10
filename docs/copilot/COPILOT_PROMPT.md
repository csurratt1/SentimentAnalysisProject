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

5. **SQL Database Storage** — MySQL 8.0 with `DatabaseManager` (reusable JDBC class) and `Hearing` entity (full CRUD). Schema in `sql/schema.sql`. Additional entity tables (speakers, nominees, turns, sentiment) planned. JSON remains the intermediate format for NLP output; database stores structured metadata.

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
    → (planned) DatabaseManager → MySQL (structured storage)
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
│   └── Hearing.java            # Hearing entity with CRUD: save, load, loadAll, delete
├── sql/
│   └── schema.sql              # CREATE DATABASE + CREATE TABLE hearings (run once to bootstrap)
├── CoreNLP/
│   └── run.ps1                 # Compiler + runner: uses mvnw.cmd, -Parse/-Resolve/-Score modes
├── input/                      # Transcript files (.docx, .txt)
│   ├── hearing_text.txt        # Full test hearing (PN908 S.Hrg. 111-695, Pt. 3)
│   ├── qa_exchange_test.txt    # Small multi-speaker excerpt for smoke tests
│   └── sample_test.txt         # Minimal test input
├── output/                     # Scoring results (gitignored, regeneratable)
│   ├── score_YYYY-MM-DD_HHmmss.json  # Timestamped structured scoring data
│   ├── score_YYYY-MM-DD_HHmmss.txt   # Timestamped human-readable report
│   ├── score_latest.json              # Latest run (copied for easy access)
│   └── score_latest.txt               # Latest run (copied for easy access)
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

### Current Phase: Phase 2 — Per-Speaker Scoring & Structured Output

**Status:** COMPLETE. CoreNLP sentiment scoring pipeline is fully operational.
All 400 turns from a real GPO hearing transcript are parsed, resolved, scored,
and output to both structured JSON and human-readable TXT reports.

**What exists and works end-to-end:**

- **SentimentTest.java** — Standalone sentence-level sentiment (5-class label + [-2,+2] score)
- **SpeakerTurn.java** — POJO for a speaker turn (title, lastName, text, turnNumber, startLine); `getSpeakerLabel()`, `hasSubstantiveText()`, `getTextPreview()`
- **SpeakerTurnParser.java** — Regex state machine segmenting transcript text into speaker turns; handles TOC filtering, annotation skipping, multi-line continuation; standalone runner
- **NomineeInfo.java** — Lightweight nominee model (firstName, lastName, position, titleUsed); `matchesLastName()`, `getDisplayName()`
- **HearingSection.java** — One nominee panel (date, nominees map, line range); `containsLine()`, `findNominee()`
- **ResolvedTarget.java** — Target resolution result (nominee, method enum, confidence 0.0–1.0); methods: SELF, DIRECT_ADDRESS, RESPONSE_PAIR, PRIOR_CONTEXT, SECTION_DEFAULT, UNKNOWN
- **TargetResolver.java** — 5-layer resolution strategy; detects NOMINATIONS headers, parses nominee names, corrects titles from transcript usage, outputs interaction matrix
- **ScoredTurn.java** (70 lines) — Wraps `ResolvedTarget` + sentiment results: `sentenceCount`, `totalScore`, `getAvgScore()`, `getWeightedScore()`, `isSelfTurn()`, `hasSpecificTarget()`, `getSpeakerLabel()`
- **TurnScorer.java** (~613 lines) — Full pipeline class:
  - Parses arguments (`-v` verbose, `-o <dir>` output directory)
  - Reads transcript, calls `SpeakerTurnParser.parse()`, calls `TargetResolver.resolve()`
  - Builds CoreNLP pipeline: `tokenize,ssplit,pos,parse,sentiment` with SR beam parser
  - Scores each substantive turn via `scoreTurn()` (strips bracketed annotations first)
  - Aggregates by (senator, nominee) pairs in `InteractionScore` objects
  - Prints to console: Nominee Scorecard, Senator Voting Profile, Summary
  - Writes `output/score_<timestamp>.json` — structured JSON via `javax.json` builders
  - Writes `output/score_<timestamp>.txt` — human-readable report
  - Copies both to `score_latest.json` / `score_latest.txt`
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
1. **Apache POI `.docx` reading in Java** — Replace the PowerShell `Extract-TextFromDocx` function with native Java reading via Apache POI. Would eliminate the PowerShell-to-Java handoff for document extraction.
2. **Speaker alias resolution** — Merge alternate titles for the same person (e.g., "Ranking Member Sessions" → "Senator Sessions", "The Chairman" → "Chairman Leahy"). Currently each unique title+lastName combo is treated as distinct.
3. **Legal precedent detection** — Regex + dictionary approach to find case references (`"Roe v. Wade"`, `"554 U.S. 570"`, `"14th Amendment"`, `"stare decisis"`). Needs a `precedents.json` dictionary of ~200 landmark cases.
4. **Rule-based scoring layer** — Pattern matching for hostile question types (`"Isn't it true that..."`, `"How can you justify..."`), supportive framing (`"Your impressive record..."`), and precedent stance signals (`"wrongly decided"`, `"settled law"`). Would supplement CoreNLP's tree-based sentiment.
5. **Windowed precedent sentiment** — When a legal precedent is referenced, score the speaker's stance using a ±2-sentence window around the mention.
6. **Additional CRUD entities** — Expand database layer with more entity classes (speakers, nominees, turns, sentiment scores, precedent references) following the same DatabaseManager + POJO CRUD pattern as Hearing.java.

**Medium-term (infrastructure):**
7. **Batch processing** — Process multiple transcripts from a configurable input directory. Idempotent re-processing, error recovery, progress tracking.
8. **Pipeline-to-database integration** — Wire TurnScorer output directly into MySQL via DatabaseManager. Currently JSON is the intermediate format; the pipeline should also persist to SQL.

**Long-term (visualization):**
9. **Interactive viewer** — Front-end to browse results by hearing, nominee, senator, or precedent. Architecture undecided (web-based vs. local desktop app).

### Key design principles:
- **Pipeline pattern** — each processing stage is a separate class that can run independently
- **Maven-managed dependencies** — all JARs from Maven Central via `pom.xml`, no manual JAR downloads. Maven Wrapper (`mvnw.cmd`) ensures reproducible builds with no global install.
- **Leverage CoreNLP-bundled libraries** — javax.json, ejml, joda-time, protobuf, etc. are all available without adding external dependencies
- **JSON as intermediate format** — keeps all doors open for SQL, pandas, web dashboards
- **DatabaseManager pattern** — single reusable class for all DB operations. Entity classes (Hearing, etc.) use `save(db)` / `load(db, id)` / `delete(db, id)` CRUD pattern.
- **Incremental build** — each phase compiles and runs before moving to the next
- **Consult before deciding** — always present options and get approval before logic/architecture choices
