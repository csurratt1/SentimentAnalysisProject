# Claude Code Project Context — Senate Hearing Sentiment Analysis

Last updated: 2026-04-23

This file is the authoritative context for Claude Code sessions on this project. Read it at the start of every session.

---

## Project Identity

**Course:** CSC 470 — Final Project (Concordia College CS)  
**Developer:** Colton Surratt  
**Advisor:** Dr. Howard  
**Purpose:** Research platform to analyze U.S. Senate Judiciary Committee confirmation hearing transcripts using NLP — scoring senator sentiment toward nominees across turns.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Build | Maven 3.9.6 (`mvnw.cmd`) |
| NLP | Stanford CoreNLP 4.5.10 (SR beam parser) |
| Database | MySQL 8.0 |
| DB credentials | `db.properties` in repo root (gitignored) |
| `.docx` parsing | Apache POI 5.2.5 |
| JSON | `jakarta.json-1.1.6.jar` (bundled in CoreNLP — do NOT add external JSON lib) |
| GUI | Java Swing, dark theme, `CardLayout` |

**Before adding any external dependency:** check if CoreNLP already bundles equivalent functionality (it includes javax.json, ejml, joda-time, protobuf, etc.).

---

## Entry Points

- **GUI:** `Main.java` → `SentimentAnalysisApp` (Swing)
- **CLI:** `TurnScorer.main()` → `java -cp "target\classes;target\dependency\*" TurnScorer -o output [--no-db] <file>`

---

## Build & Run Commands (Windows PowerShell)

```powershell
# Compile
.\mvnw.cmd -q -DskipTests compile

# Copy runtime deps
.\mvnw.cmd -q dependency:copy-dependencies -DincludeScope=runtime

# Launch GUI
.\mvnw.cmd -q exec:java -Dexec.mainClass=Main

# Run scorer — no DB
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output --no-db input\qa_exchange_test.txt

# Run scorer — DB enabled
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output input\qa_exchange_test.txt

# Legacy helper script shortcuts
.\CoreNLP\run.ps1 -Parse input\qa_exchange_test.txt
.\CoreNLP\run.ps1 -Resolve input\qa_exchange_test.txt
.\CoreNLP\run.ps1 -Score input\qa_exchange_test.txt
```

---

## Pipeline (Execution Order)

`TurnScorer` stages, in order:

1. Load transcript text (`.txt` or `.docx` via Apache POI)
2. `SpeakerTurnParser` — regex state machine → `List<SpeakerTurn>`
3. `TargetResolver` — 5-layer resolution: SELF → DIRECT_ADDRESS → RESPONSE_PAIR → PRIOR_CONTEXT → SECTION → UNKNOWN
4. CoreNLP pipeline build/reuse — `tokenize, ssplit, pos, parse, sentiment` (lazy-load, cached, thread-safe via `PIPELINE_LOCK`)
5. Score substantive turns → `List<ScoredTurn>` with per-sentence confidence
6. `SpeakerAliasResolver` — merge alternate senator labels into canonical labels
7. `aggregateByPair()` — interaction map keyed by `"senator|nominee"`
8. Build JSON + text reports
9. Optionally persist via `ScoringPersistence` (transactional)
10. Finalize output files — pending-file commit flow (see below)

**Weighted score formula:** `weightedScore = avgScore * resolutionConfidence`

---

## Core Classes

| Class | Role |
|-------|------|
| `Main` | Desktop entry point |
| `SentimentAnalysisApp` | GUI shell (Dashboard, Run Analysis, Results) + orchestration |
| `TurnScorer` | Scoring orchestrator, report generation, CLI runner |
| `SpeakerTurnParser` | Transcript → `SpeakerTurn` list |
| `TargetResolver` | Nominee targeting (6 resolution methods + confidence) |
| `SpeakerAliasResolver` | Merges alternate senator labels into canonical labels |
| `ScoredTurn` | Per-turn score result with weighted score |
| `ResolvedTarget` | Turn + nominee + resolution method + confidence |
| `AnalysisBundle` | Carries all pipeline outputs through the run |
| `RunResult` | Final file paths + counts returned to GUI |
| `ScoringPersistence` | Transactional DB writer (idempotent per source file) |
| `DatabaseManager` | Reusable JDBC connect/execute/query layer |

Inner classes (nested): `RunResult` and `AnalysisBundle` live inside `TurnScorer`; `AliasMap` inside `SpeakerAliasResolver`; `PersistenceResult` inside `ScoringPersistence`; `FileNameRenderer` and `RunOutcome` inside `SentimentAnalysisApp`.

---

## Output File Contract

Output directory: `output/`

Filename pattern:
- `score_<inputLabel>_<yyyy-MM-dd_HHmmss>.json`
- `score_<inputLabel>_<yyyy-MM-dd_HHmmss>.txt`

**Pending-file commit flow (do not break):**
- With DB persistence ON: files are written as `.json.pending` / `.txt.pending` first.
- Pending files are promoted to final names only after DB COMMIT succeeds.
- Pending files are deleted on DB failure.

JSON output sections: `metadata`, `turns`, `interactions`, `nominees`

---

## Database Contract

Schema: `sql/schema.sql`

**7 tables:** `speakers`, `hearings`, `hearing_sections`, `nominations`, `turns`, `scoring_runs`, `turn_scores`  
**1 view:** `interactions_view`

Persistence rules:
- One effective scoring run per source transcript file (idempotent replace).
- `ScoringPersistence.persistRun()` wraps everything in one transaction.
- Existing child rows for the same hearing are cleared before re-insert.
- Rollback on any SQL exception.

Connection: `db.properties` (gitignored). Keys: `db.host`, `db.port`, `db.name`, `db.user`, `db.password`.

---

## Rubric Checklist (CSC 470 Final — Mastery tier)

| ID | Requirement | Status |
|----|-------------|--------|
| M3 | UML diagrams for ≥ 4 major components | Done (`docs/uml/` — 4 diagrams, Mermaid errors fixed) |
| M4 | Time estimates for each major component | Done (`docs/time_estimates.md`) |
| M5 | All hours in 0.25 hr increments | Done |
| M7 | Code comments + citations for external help/code | **Open — verify before submission** |
| M13 | CRUD in DB | Create ✓, Read ✓, Update (upsert) ✓ — **verify explicit Delete path** |
| M16 | No magic numbers | **Open — check for raw ints/doubles inline in scoring logic** |
| M17 | Input validation | File type and empty input guards in place — verify edge cases |
| M18 | Graceful error messages | GUI dialogs + log area in place |
| M19 | Error logging | Done — SLF4J + Logback; file appender to `logs/app.log`; daily rolling; integrated in `DatabaseManager`, `ScoringPersistence`, `TurnScorer` |
| M20 | Unit tests for major components | Done — 101 tests passing; 5 test files in `src/test/java/` |

---

## Unit Test Coverage (M20 — completed 2026-04-23)

101 tests, 0 failures. Files in `src/test/java/`:

| File | Tests | Focus |
|------|-------|-------|
| `SpeakerTurnParserTest.java` | 21 | Parser regex, turn segmentation, TOC/annotation filtering, title recognition |
| `ScoredTurnTest.java` | 16 | Weighted score math, avgScore formula, confidence storage |
| `AggregationTest.java` | 16 | `InteractionScore` accumulation, reliability tier thresholds, `aggregate()` |
| `SpeakerAliasResolverTest.java` | 20 | Alias merging, canonical election, title tie-breaking, exclusions |
| `TargetResolverTest.java` | 14 | `ResolvedTarget` model, `hasSpecificTarget`, `isSelfTurn`, panel immutability |
| `TurnScorerTest.java` | 14 | `NomineeInfo` + `SpeakerTurn` model correctness, all getters |

Run with: `.\mvnw.cmd test`

---

## Known Open Items

1. **M7 — Code comments/citations**: Review all source files for any external help or copied snippets before submission; ensure attribution comments are in place.
2. **M13 — Explicit Delete path**: Confirm there is at least one exercised code path that deletes a DB row (not just upsert/replace). `ScoringPersistence` clears child rows on re-run — verify this qualifies.
3. **M16 — Magic numbers**: Scan scoring logic for inline literals (e.g., the `classIndex - 2` mapping, reliability tier thresholds `0.85`/`0.65`). These should be named constants.
4. **Parser edge cases** — some heading/annotation noise in certain transcript formats.
5. **Unused code** — some unused constants in `TurnScorer`, unused import in `SpeakerTurnParser`, generics warning in `TargetResolver`.

---

## UML Diagrams (`docs/uml/`)

| File | Content |
|------|---------|
| `01_database_entity_classes.md` | DB entity class diagram |
| `02_pipeline_core_classes.md` | Core pipeline class diagram |
| `03_analysis_pipeline_sequence.md` | Full pipeline sequence diagram |
| `04_gui_class_diagram.md` | GUI class diagram + navigation state diagram |

All diagrams use Mermaid. Use `*--` for composition/inner-class relationships (not `+--`, which is invalid syntax).

---

## Development Rules (follow these every session)

1. **Consult before building.** Present options and wait for approval before any architecture or algorithm decision. Never scaffold future phases speculatively.
2. **Incremental.** Each change must compile and run before moving to the next. This is a year-long incremental research project.
3. **Prefer CoreNLP-bundled libraries** over new external dependencies.
4. **Do not break** the pending-file commit flow, DB transaction semantics, or alias merge behavior.
5. **No hardcoded credentials.** `db.properties` stays gitignored.
6. **Scope to what was asked.** Do not add features, refactors, or abstractions beyond the task.
7. **No comments explaining what code does** — only comment the non-obvious WHY (hidden constraint, workaround, subtle invariant).

---

## Planned Future Phases (DO NOT implement without explicit plan + user sign-off)

1. Speaker alias resolution improvements
2. Legal precedent detection (regex + `precedents.json` dictionary)
3. Rule-based scoring layer (hostile question patterns, supportive framing)
4. Windowed precedent sentiment
5. Export/query helpers for class demos and downstream analysis
6. Interactive viewer (front-end, architecture TBD)
