# GitHub Copilot Project Context Prompt

Last updated: 2026-04-21

Use this file as the default context prompt for Copilot sessions in this repository.

## Authoritative Project Snapshot

This section is the source of truth for current behavior.

- Java desktop app entry point is `Main`, which launches `SentimentAnalysisApp` (Swing GUI).
- Core scoring pipeline is implemented and operational end-to-end.
- Supported transcript input formats: `.txt` and `.docx`.
- `.docx` is read natively in Java (`TurnScorer.loadTranscriptText`) using Apache POI.
- Target resolution is implemented (`TargetResolver`) with direct-address regex plus CoreNLP NER fallback.
- Speaker alias merging is implemented (`SpeakerAliasResolver`) and applied to interaction aggregation.
- MySQL persistence is enabled by default for scoring runs; `--no-db` disables persistence.
- Output commit semantics are hardened:
  - With DB persistence ON, reports are first written as `.json.pending` and `.txt.pending`.
  - Pending files are promoted to final names only after DB commit succeeds.
  - Pending files are deleted on DB failure.
- DB persistence is transactional and idempotent per source transcript filename:
  - Existing run data for the same `hearings.source_file` is replaced.
  - One effective scoring run is maintained per source transcript file.
- Fail-fast guardrails are in place:
  - Pipeline aborts if no speaker turns are parsed.
  - Pipeline aborts if no substantive turns are scoreable.
- Metadata now tracks both resolution confidence and sentiment confidence.
- GUI includes preview-before-save for single runs and controlled batch concurrency.
- Batch worker cap is enforced (`MAX_SAFE_BATCH_WORKERS = 2`) for runtime stability.

## Current Pipeline (Execution Order)

`TurnScorer` pipeline stages:

1. Load transcript text (`.txt` or `.docx`).
2. Parse turns with `SpeakerTurnParser`.
3. Resolve targets with `TargetResolver`.
4. Build/reuse cached CoreNLP scoring pipeline (`tokenize,ssplit,pos,parse,sentiment`).
5. Score substantive turns and compute per-turn sentiment confidence.
6. Merge speaker aliases with `SpeakerAliasResolver`.
7. Aggregate interactions by canonical senator label and nominee target.
8. Build outputs (JSON + text report).
9. Optionally persist to DB with `ScoringPersistence`.
10. If DB succeeds, finalize output files; otherwise clean pending outputs.

Weighted scoring relationship:

- `weightedScore = avgScore * resolutionConfidence`

## Core Classes and Responsibilities

- `Main`: desktop entry point.
- `SentimentAnalysisApp`: GUI shell (Dashboard, Run Analysis, Results) and orchestration.
- `TurnScorer`: scoring orchestrator, output generation, CLI entry point.
- `SpeakerTurnParser`: transcript-to-turn segmentation.
- `TargetResolver`: nominee targeting strategy (self, direct, response, context, section, unknown).
- `SpeakerAliasResolver`: merges alternate senator labels into canonical labels.
- `ScoringPersistence`: transactionally writes hearing/run/turn/score records.
- `DatabaseManager`: reusable JDBC connect/execute/query layer.

## Runtime Commands (Windows PowerShell)

Compile:

```powershell
.\mvnw.cmd -q -DskipTests compile
```

Copy runtime dependencies for direct `java -cp` execution:

```powershell
.\mvnw.cmd -q dependency:copy-dependencies -DincludeScope=runtime
```

Run GUI:

```powershell
.\mvnw.cmd -q exec:java -Dexec.mainClass=Main
```

Run full scorer (no DB):

```powershell
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output --no-db input\qa_exchange_test.txt
```

Run full scorer (DB enabled):

```powershell
java -Xmx4g -cp "target\classes;target\dependency\*" TurnScorer -o output input\qa_exchange_test.txt
```

Legacy helper script (parse/resolve/score shortcuts):

```powershell
.\CoreNLP\run.ps1 -Parse input\qa_exchange_test.txt
.\CoreNLP\run.ps1 -Resolve input\qa_exchange_test.txt
.\CoreNLP\run.ps1 -Score input\qa_exchange_test.txt
```

## Database Contract

Schema file: `sql/schema.sql`

- 7 tables:
  - `speakers`
  - `hearings`
  - `hearing_sections`
  - `nominations`
  - `turns`
  - `scoring_runs`
  - `turn_scores`
- 1 analytics view:
  - `interactions_view`

Persistence behavior:

- `ScoringPersistence.persistRun(...)` wraps writes in one transaction.
- Existing child rows for the same hearing are cleared before replacement insert.
- Rollback occurs on SQL exceptions.

Connection file:

- `db.properties` in repo root (gitignored).
- Required keys: `db.host`, `db.port`, `db.name`, `db.user`, `db.password`.

## Output Contract

Output directory: `output/`

Filename pattern:

- `score_<inputLabel>_<yyyy-MM-dd_HHmmss>.json`
- `score_<inputLabel>_<yyyy-MM-dd_HHmmss>.txt`

JSON includes:

- `metadata` (turn counts, scoring runtime, confidences, parser model)
- `turns` (turn-level targets and scores)
- `interactions` (senator->nominee aggregates, reliability, alias merges)
- `nominees` (nominee-level scorecard rollups)

## Known Limitations / Backlog

- Logging backend is not configured (SLF4J fallback warning may appear).
- Parser still has known heading-noise edge cases in some transcripts.
- Automated test coverage is minimal; project is primarily workflow-validated.

## Copilot Working Rules for This Repo

1. Preserve current pipeline semantics unless explicitly asked to change them.
2. Keep DB write behavior transactional and maintain pending-file commit flow.
3. Do not remove alias merge behavior or confidence reporting.
4. Ask before making architectural changes or scoring policy changes.
5. Keep work incremental and runnable after each change.
6. Never hardcode credentials or commit secrets.

## Suggested Next Focus Areas

1. Add explicit SLF4J binding and structured runtime logging.
2. Expand parser robustness for heading and annotation edge cases.
3. Add repeatable integration tests around parse -> resolve -> score -> persist.
4. Add export/query helpers for class demos and downstream analysis.
