# README_CLAUDE.md — Handoff: Per-Sentence Scoring & Database Persistence

> **Why this file exists.** This feature was prototyped on another machine (the
> "desktop"). That machine's copy of the repo had diverged badly from this line
> of work — it was missing the unit tests, SLF4J logging, `SpeakerAliasResolver`,
> and the `SentimentAnalysisApp` rewrite that live on `main`. Rather than force a
> messy merge that risked clobbering that work, the desktop was reset to match
> GitHub, and the feature is captured here so it can be **re-implemented cleanly,
> on top of the current code.**
>
> **If you are a Claude Code session reading this:** implement the feature
> described below. Do **not** paste old code — the files have changed since the
> prototype. Read the *current* versions, adapt, and — per the explicit request
> of the project owner — **critically review and improve** the design as you go
> (see §5). When the feature is implemented, tested, verified, and committed,
> **delete this file.**

---

## 1. The goal

Two connected features the project owner wants:

**A. A per-sentence verification report.** Each scoring run currently writes a
JSON file and an aggregated text report. Add a *third* output file that lists
**every individual sentence** CoreNLP scored, with that sentence's own score,
its speaker, and its resolved target. Purpose: a researcher can manually eyeball
each sentence next to its score to verify the model behaves reasonably, and cite
individual sentences as small examples in a paper. Metadata is abbreviated for
readability.

**B. Persist that per-sentence data to the database.** Store the same
per-sentence rows in a new MySQL table so multiple scoring runs can be queried
and compared directly in SQL.

---

## 2. Feature A — per-sentence verification text file

Write a third file per run, alongside the existing
`score_<name>_<timestamp>.json` and `score_<name>_<timestamp>.txt`:

```
score_<name>_<timestamp>_sentences.txt
```

**Format: grouped by speaker turn** (the owner chose this over a flat table):

```
======================================================================
  PER-SENTENCE SCORING DETAIL  (manual verification reference)
======================================================================
(short explanation + score key here)

----------------------------------------------------------------------
Turn 12 | SPK Sen. Sessions | TGT Judge Chen (direct)
  [-1 Negative     ] conf=0.62  I have grave concerns about your judicial record.
  [ 0 Neutral      ] conf=0.55  You wrote that opinion in 2003.
  [+1 Positive     ] conf=0.50  Your community service is admirable.
```

- One block per scored turn. Header: turn number, abbreviated speaker
  (`Senator`→`Sen.`, `Chairman`/`The Chairman`→`Chair.`), resolved target + method.
- One line per sentence: `[<signed score> <label>] conf=<0.00>  <sentence text>`.
- Score key: `-2 Very Negative … +2 Very Positive`.

**Implementation approach used in the prototype (adapt to current code):**

- New model class `SentenceScore` — holds one sentence's `text`, `classIndex`
  (0–4), `score` (−2..+2), `confidence`, and a `getLabel()`.
- `TurnScorer.scoreTurn(...)` already loops `List<CoreMap> sentences` and computes
  `toScore(classIdx)` plus a max-prediction `sentenceConfidence` per sentence.
  Extend that loop to also capture `sentence.get(CoreAnnotations.TextAnnotation.class)`
  (whitespace-collapsed) and build a `List<SentenceScore>`.
- `ScoredTurn` — add a `List<SentenceScore> sentenceScores` field + getter. Add a
  new constructor that accepts it; **keep the existing constructors** (delegate to
  the new one with an empty list) so current callers and `ScoredTurnTest` keep working.
- New `TurnScorer` methods: `printSentenceVerification(PrintStream, List<ScoredTurn>)`,
  `writeSentenceVerificationReport(Path, List<ScoredTurn>)`, and
  `buildVerificationPreview(AnalysisBundle)` for the GUI preview.
- `finalizeRun(...)` — write the third file alongside json/txt; replicate the
  existing `.pending` rename pattern (write `<prefix>_sentences.txt.pending`,
  promote on DB success, delete on DB failure). Add a `verificationFile` field to
  `RunResult`.
- GUI (`SentimentAnalysisApp`) — add a "Per-Sentence Verification" preview tab and
  log the new file. The GUI was rewritten on this branch — read the current
  `SentimentAnalysisApp.java` and fit the addition to its current structure.

---

## 3. Feature B — database persistence

**New table `sentence_scores`** — one row per scored sentence. It is table #8;
the current schema has 7 tables ending with `turn_scores`. Suggested shape:

```sql
CREATE TABLE sentence_scores (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    turn_score_id    INT NOT NULL,     -- FK -> turn_scores(id)
    scoring_run_id   INT NOT NULL,     -- FK -> scoring_runs(id); enables cross-run queries
    turn_id          INT NOT NULL,     -- FK -> turns(id)
    sentence_index   INT NOT NULL,     -- 1-based position within the turn
    sentence_text    TEXT NOT NULL,
    sentiment_class  TINYINT NOT NULL, -- CoreNLP class 0..4
    sentiment_label  VARCHAR(20),      -- e.g. 'Negative'  (see §5 self-review note)
    sentiment_score  TINYINT NOT NULL, -- class mapped to [-2,+2]
    confidence       DECIMAL(6,4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_turn_score_sentence (turn_score_id, sentence_index),
    INDEX idx_sentence_scores_run (scoring_run_id),
    INDEX idx_sentence_scores_turn (turn_id),
    CONSTRAINT fk_sentence_score_turn_score FOREIGN KEY (turn_score_id) REFERENCES turn_scores(id) ON DELETE CASCADE,
    CONSTRAINT fk_sentence_score_run        FOREIGN KEY (scoring_run_id) REFERENCES scoring_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_sentence_score_turn       FOREIGN KEY (turn_id)        REFERENCES turns(id)         ON DELETE CASCADE
) ENGINE=InnoDB;
```

Also add a `sentence_scores_view` joining run / transcript / speaker / target
(mirror how `interactions_view` is built) so multiple runs read easily side by
side. Update the schema's drop section: add `DROP VIEW IF EXISTS sentence_scores_view;`
and `DROP TABLE IF EXISTS sentence_scores;` — drop the new table **first**, before
`turn_scores`.

**Java side:**

- New DB entity `SentenceScoreRecord` — follow the `TurnScore` entity pattern
  (constructor, `save()` that INSERTs, read helpers such as `loadAll` /
  `loadByScoringRun`). `save()` must be **silent** — a hearing produces thousands
  of sentences; do not print or log per row.
- `ScoringPersistence` — in the turn-score insert loop, after each `turn_scores`
  row is saved, capture its generated id and insert one `sentence_scores` row per
  sentence, **inside the existing transaction**. Add a sentence count to
  `PersistenceResult`.
- GUI — add a "Sentences Stored" stat to the DB summary.

**Applying it:** the schema must be recreated — `mysql -u root -p < sql/schema.sql`
drops and recreates every table. The owner already agreed to "start fresh", so
prior rows being wiped is expected.

---

## 4. Re-implement against the *current* code — do not transcribe

`main` has work the prototype never saw: SLF4J/logback logging,
`SpeakerAliasResolver`, a large `SentimentAnalysisApp` rewrite, a `src/test/java`
unit-test suite, and schema tweaks (`turn_scores` gained `avg_sentiment_confidence`).
**Read the current files before editing them.** Adapt the feature to what's there now.

---

## 5. Self-review directives — improve as you implement

The project owner explicitly wants this re-implementation to *critique and
improve* the original design, not copy it. While implementing:

- **Logging.** The project uses SLF4J now. Match the surrounding code; don't add
  `System.out` debug noise.
- **Tests.** There is a `src/test/java` suite. **Add tests** for `SentenceScore`,
  `SentenceScoreRecord`, and the verification-report formatting, in the existing
  test style. Confirm the new `ScoredTurn` constructor doesn't break `ScoredTurnTest`.
- **Schema fit.** Integrate `sentence_scores` consistently with the current
  schema's numbering, comment style, and the `avg_sentiment_confidence` column.
- **Question the design — decide deliberately, don't default:**
  - Is storing `sentiment_label` (derivable from `sentiment_class`) worth the
    denormalization, or should code/the view derive it?
  - Is grouped-by-turn still the clearest report layout, or would a short per-run
    summary header (counts per score band) help a researcher more?
  - Edge cases: turns with no sentences, very long sentences, punctuation-only
    sentences, non-ASCII text.
- **Performance.** Inserting thousands of rows one statement at a time is slow —
  consider a JDBC batch insert.
- **Verify.** `mvn test` passes; run a transcript with DB persist on; confirm
  `_sentences.txt` is written and `sentence_scores` populates; spot-check
  `SELECT * FROM sentence_scores_view`. (Note: input transcript files were removed
  from the repo — you may need to add one to `input/` to run an end-to-end test.)

---

## 6. When done

Implement → test → verify → commit. Then **delete this file** — it is a one-time
handoff, not permanent project documentation.
