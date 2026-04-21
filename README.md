# Senate Confirmation Hearing Sentiment Analysis

Year-long Concordia College CS research project for analyzing U.S. Senate confirmation hearing transcripts with NLP and storing structured results in MySQL.

## What This Project Does

This system ingests hearing transcripts (`.txt` or `.docx`) and produces:

- Parsed speaker turns (senators, nominees, chair, etc.)
- Resolved targets (who each turn is directed at)
- Turn-level sentiment scores using Stanford CoreNLP
- Aggregated senator -> nominee interaction scores
- Timestamped JSON/TXT reports in `output/`
- Relational persistence in MySQL for querying and dashboards

## Pipeline Logic (Execution Order)

Main scoring flow is implemented in `TurnScorer`.

1. Input load
- Reads transcript text from `.txt` or `.docx` (`loadTranscriptText`).
- `.docx` support is native Java via Apache POI.

2. Turn segmentation
- `SpeakerTurnParser` converts transcript lines into ordered `SpeakerTurn` objects.

3. Target resolution
- `TargetResolver` maps each turn to a nominee target using:
  - `SELF`
  - `DIRECT_ADDRESS`
  - `RESPONSE_PAIR`
  - `PRIOR_CONTEXT`
  - `SECTION_DEFAULT`
  - `UNKNOWN`
- Includes regex direct address and CoreNLP NER fallback.

4. Sentiment scoring
- CoreNLP pipeline: `tokenize,ssplit,pos,parse,sentiment`
- Parser model: `englishSR.beam.ser.gz`
- Sentence class scores map from 0..4 to -2..+2.

5. Alias normalization
- `SpeakerAliasResolver` merges alternate senator labels to a canonical speaker label.

6. Aggregation
- Aggregates by canonical senator + nominee pair (`InteractionScore`).

7. Report generation
- JSON and TXT reports are generated with metadata, turns, interactions, and nominee scorecards.

8. Database persistence (default ON)
- `ScoringPersistence` writes hearing/section/nomination/turn/run/score rows in one transaction.
- For each `source_file`, existing run data is replaced to keep one effective run per transcript.

9. Safe output commit
- When DB is enabled, files are written to `.pending` first.
- Pending files are renamed to final output names only after DB success.
- Pending files are deleted if DB persistence fails.

## Architecture Snapshot

```text
input/*.txt or *.docx
    -> SpeakerTurnParser
    -> TargetResolver
    -> CoreNLP scoring (TurnScorer)
    -> SpeakerAliasResolver
    -> Interaction aggregation
    -> output/score_<input>_<timestamp>.json|txt
    -> ScoringPersistence -> MySQL (7 tables + 1 view)
```

## Tech Stack

- Java 17
- Maven Wrapper (`mvnw.cmd`)
- Stanford CoreNLP 4.5.10 (core + models + models-english)
- Apache POI 5.2.5 (DOCX input)
- MySQL 8.0 + mysql-connector-j 8.0.33
- Swing desktop UI (`SentimentAnalysisApp`)

## Stanford Resources Used

- Sentiment and Stanford Sentiment Treebank overview:
  https://nlp.stanford.edu/sentiment/treebank.html
  Used as the primary reference for the sentiment label framework and background on tree-based sentiment modeling.
- Stanford CoreNLP repository:
  https://github.com/stanfordnlp/CoreNLP
  Used as the main implementation reference for pipeline setup, annotators, model usage, and practical integration details.

## Project Structure

```text
SentimentAnalysisProject/
|- src/main/java/
|  |- Main.java
|  |- SentimentAnalysisApp.java
|  |- TurnScorer.java
|  |- SpeakerTurnParser.java
|  |- TargetResolver.java
|  |- SpeakerAliasResolver.java
|  |- ScoringPersistence.java
|  |- DatabaseManager.java
|  |- (entity classes: Hearing, Speaker, Nomination, Turn, ScoringRun, TurnScore)
|- sql/schema.sql
|- input/
|- output/
|- docs/copilot/COPILOT_PROMPT.md
|- db.properties (gitignored)
|- pom.xml
```

## Database Information

Schema file: `sql/schema.sql`

Tables:

1. `speakers`
- Canonical people entities (role, name, metadata)

2. `hearings`
- Root record for each transcript/source file

3. `hearing_sections`
- Nominee panel sections parsed from hearing headers

4. `nominations`
- Nominee events linking section + speaker + position

5. `turns`
- Every parsed speaker turn with full text and metadata

6. `scoring_runs`
- Metadata for each pipeline scoring execution

7. `turn_scores`
- Per-turn target resolution and sentiment metrics

View:

- `interactions_view`
  - Query-ready aggregated senator -> nominee interaction metrics

Data integrity notes:

- Foreign keys with cascades maintain hearing-rooted cleanup.
- Persistence is transaction-wrapped.
- Re-runs for the same transcript replace prior run data for that hearing.

## Setup

### 1) Prerequisites

- JDK 17 available on PATH
- MySQL 8.0 running locally
- Windows PowerShell for script-driven runs (optional)

### 2) Initialize database

```powershell
mysql -u root -p < sql/schema.sql
```

### 3) Configure local DB credentials

Create/edit `db.properties` in repo root:

```properties
db.host=localhost
db.port=3306
db.name=sentiment_analysis
db.user=your_user
db.password=your_password
```

`db.properties` is ignored by git and should never be committed.

### 4) Build

```powershell
.\mvnw.cmd -q -DskipTests compile
```

For direct `java -cp` runs, also copy dependencies:

```powershell
.\mvnw.cmd -q dependency:copy-dependencies -DincludeScope=runtime
```

## Running the Project

### Option A: Desktop GUI (recommended for class demo)

```powershell
.\mvnw.cmd -q exec:java -Dexec.mainClass=Main
```

In the GUI:

- Dashboard: recent outputs + last DB summary
- Run Analysis: single run or batch run
- Results: browse generated JSON/TXT files

Important GUI behaviors:

- Single run can preview report/JSON before commit.
- Batch workers are capped at 2 for runtime stability.

### Option B: CLI full pipeline

No DB write:

```powershell
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output --no-db input\qa_exchange_test.txt
```

With DB persistence:

```powershell
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output input\qa_exchange_test.txt
```

DOCX input is also supported:

```powershell
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output input\your_hearing.docx
```

### Option C: Parser/Resolver component runs (script helper)

```powershell
.\CoreNLP\run.ps1 -Parse input\qa_exchange_test.txt
.\CoreNLP\run.ps1 -Resolve input\qa_exchange_test.txt
.\CoreNLP\run.ps1 -Score input\qa_exchange_test.txt
```

## Output Files

Generated into `output/`:

- `score_<inputLabel>_<timestamp>.json`
- `score_<inputLabel>_<timestamp>.txt`

JSON includes:

- `metadata`
- `turns`
- `interactions`
- `nominees`

## Class Demo Guide (10-15 Minutes)

1. Show project goal
- Explain this is hearing-text -> interaction analytics, not generic sentiment on isolated sentences.

2. Run GUI
- Start with `Main`, open Run Analysis page.

3. Run one sample transcript
- Use `input/qa_exchange_test.txt` first.
- Keep preview ON to show pre-commit report review.

4. Show outputs
- Open Results tab.
- Compare TXT summary and JSON structure.

5. Show DB persistence impact
- Re-run with DB enabled.
- Point out Dashboard DB summary values (hearing id, run id, turns, scores, replaced prior).

6. Explain confidence and weighting
- Resolution confidence and sentiment confidence are tracked separately.
- Weighted sentiment uses target confidence.

7. Discuss extension opportunities
- Better parser robustness, richer test coverage, logging, analytics UI.

## Handoff Notes for New Students

Start here in order:

1. `src/main/java/TurnScorer.java`
- End-to-end orchestrator and output contract.

2. `src/main/java/TargetResolver.java`
- Most important logic for who-is-speaking-to-whom.

3. `src/main/java/ScoringPersistence.java`
- DB write semantics, replacement policy, transaction behavior.

4. `sql/schema.sql`
- Canonical data model.

5. `src/main/java/SentimentAnalysisApp.java`
- UI workflow and demo entrypoint.

Recommended first contributions:

- Add logging backend and structured logs
- Improve parser handling for heading edge cases
- Add integration tests for parse -> resolve -> score -> persist
- Add query/report scripts for common class discussion questions

## Troubleshooting

- "No speaker turns parsed"
  - Verify transcript formatting includes speaker-prefixed lines.

- "No substantive turns were scored"
  - Input may be mostly metadata/annotations or malformed text.

- DB persistence fails
  - Check `db.properties`, MySQL service status, and schema initialization.

- Slow first run
  - First CoreNLP pipeline/model load is expected; subsequent runs are faster due to cached pipeline instance.

## Notes

- `output/` and `db.properties` are gitignored by design.
- Current project is validated primarily by end-to-end workflow runs rather than broad automated tests.
