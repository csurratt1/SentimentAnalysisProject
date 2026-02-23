# GitHub Copilot Project Context Prompt

Use this as your initial prompt in Copilot Chat (or paste into a COPILOT_CONTEXT.md file at the repo root so Copilot always has it).

---

## Prompt:

I am building a year-long Java research project for my Computer Science program at Concordia College. The project is a **Senate Confirmation Hearing Sentiment Analysis Platform**. Here is the full context:

### What the project does:

This system ingests U.S. Senate Judiciary Committee confirmation hearing transcripts (`.docx` files from the Government Publishing Office) and performs multi-layered NLP analysis:

1. **Transcript Parsing** — Reads `.docx` files, identifies individual speaker turns (senators, nominees, presenters), and segments the raw text into structured exchanges. Senate hearing transcripts use a semi-consistent format where speaker transitions look like `"Senator Sessions."`, `"Chairman Leahy."`, `"Judge Chen."`, `"Ms. Gee."`, `"Mr. Kappos."` at the start of a paragraph. Bracketed annotations like `[Laughter.]` or `[The information appears as a submission for the record.]` are editorial metadata, not speech.

2. **Speaker Resolution** — Maps different references to the same person (`"Senator Sessions"`, `"Ranking Member Sessions"`, `"Mr. Sessions"` → same canonical entity). Tags speakers with role (SENATOR, NOMINEE, PRESENTER, CHAIRMAN, RANKING_MEMBER), party (D/R/I), and state.

3. **Legal Precedent Detection** — Scans turn text for references to Supreme Court cases (`"Roe v. Wade"`, `"Heller"`), formal citations (`"554 U.S. 570"`), constitutional amendments (`"14th Amendment"`), statutes (`"Title VII"`), and legal doctrines (`"stare decisis"`, `"originalism"`, `"Chevron deference"`). Uses a combination of regex patterns and an expandable dictionary of ~200 landmark cases with shorthand forms.

4. **Dual-Target Sentiment Analysis** using Stanford CoreNLP:
   - **Nominee Approval Scoring** — For each senator's turn, score how favorable/hostile they appear toward the nominee's confirmation. Scale: -1.0 (hostile) to +1.0 (supportive).
   - **Precedent Sentiment Scoring** — When a legal precedent is referenced, score the speaker's stance toward that precedent. Uses a windowed approach (±2 sentences around the mention). Scale: -1.0 (disapproves) to +1.0 (endorses).
   - Also includes a **rule-based scoring layer** with pattern matching for hostile question types (`"Isn't it true that..."`, `"How can you justify..."`), supportive framing (`"Your impressive record..."`), and precedent stance signals (`"wrongly decided"`, `"settled law"`).

5. **MySQL Database Storage** — All parsed data, speaker turns, legal references, and sentiment scores are stored in a relational MySQL database for querying and analysis.

6. **Batch Processing** — The system is designed to process hundreds of transcripts from a configurable input directory (synced from Dropbox or uploaded manually). Must support idempotent re-processing, error recovery, and progress tracking.

7. **Interactive Viewer (Phase 3, later)** — A front-end to browse results by hearing, nominee, senator, or precedent. This will be built later — the architecture should accommodate it but not implement it yet.

### Tech Stack:
- **Language:** Java 17+
- **NLP Engine:** Stanford CoreNLP 4.5+ (downloaded as local zip — include as local dependency or Maven dependency)
- **Document Parsing:** Apache POI (for `.docx` reading)
- **Database:** MySQL 8.0 with HikariCP connection pooling
- **JSON Processing:** Gson
- **Build:** Maven
- **Testing:** JUnit 5
- **Logging:** SLF4J with slf4j-simple

### Data flow:
```
.docx files (input directory)
    → DocxParser (Apache POI — extract paragraphs)
    → SpeakerParser (regex state machine — identify speaker turns)
    → SpeakerResolver (alias resolution — canonical speaker identity)
    → PrecedentDetector (regex + dictionary — find legal references)
    → CoreNLPAnalyzer (Stanford sentiment — sentence and turn-level scoring)
    → RuleBasedScorer (pattern matching — hostile/friendly classification)
    → SentimentAggregator (combine CoreNLP + rules into final scores)
    → MySQL (via DAO layer with HikariCP)
```

### Database tables needed:
- `hearings` — hearing metadata (date, session, serial number, committee, source file)
- `speakers` — canonical speaker entities (name, title, party, state, role)
- `nominees` — nominees with position and hearing link, confirmation status
- `turns` — individual speaker turns (text, speaker, nominee discussed, order, section type)
- `turn_sentiment` — CoreNLP scores + nominee approval scores per turn
- `precedent_references` — detected legal references with sentiment toward the cited case
- `precedent_dictionary` — lookup table of known landmark cases, shorthand names, topics

### Key design patterns:
- **DAO pattern** for all database access
- **Pipeline pattern** — each processing stage is a separate class that can run independently
- **Configuration via properties file** — database credentials, input/output paths, CoreNLP settings
- **Model classes (POJOs)** for all domain entities
- **Builder pattern** where constructors get complex

### What I need you to generate:

> **DO NOT scaffold the entire project at once.** This is a year-long research project
> built incrementally. Only generate code for the current phase. Each phase should
> compile and run before moving to the next.

### Current Phase: Phase 1 — Smoke Test & .docx Ingest

**Status:** CoreNLP sentiment pipeline compiles and runs. Speaker turn segmentation
and target resolution are both scaffolded and tested against a real 200K-char GPO
hearing transcript. Every speaker turn is resolved to a specific nominee target.

**What exists:**
- `CoreNLP/SentimentTest.java` — working sentence-level sentiment (5-class label + [-2,+2] score)
- `CoreNLP/SpeakerTurn.java` — model POJO for a speaker turn (title, lastName, text, turnNumber, startLine)
- `CoreNLP/SpeakerTurnParser.java` — regex state machine that segments transcript text into speaker turns; handles TOC filtering, annotation skipping, multi-line continuation; standalone runner with built-in test data
- `CoreNLP/NomineeInfo.java` — lightweight model for a nominee (firstName, lastName, position, titleUsed); `matchesLastName()` for case-insensitive lookup
- `CoreNLP/HearingSection.java` — represents one nominee panel (date, nominees map, line range); `containsLine()` and `findNominee()` for lookup
- `CoreNLP/ResolvedTarget.java` — result of target resolution for a turn (nominee, method enum, confidence 0.0–1.0); methods: SELF, DIRECT_ADDRESS, RESPONSE_PAIR, PRIOR_CONTEXT, SECTION_DEFAULT, UNKNOWN
- `CoreNLP/TargetResolver.java` — 5-layer resolution strategy determining WHO each speaker turn is directed at; detects NOMINATIONS headers, parses nominee names from headers, corrects titles from transcript usage, outputs interaction matrix
- `CoreNLP/run.ps1` — compiles all Java sources, extracts `.docx` → plain text, 3 modes: default (`SentimentTest`), `-Parse` (`SpeakerTurnParser`), `-Resolve` (`TargetResolver`)
- `input/` — drop folder for `.docx` test documents + `qa_exchange_test.txt` (multi-speaker excerpt)
- `lib/stanford-corenlp/` — local CoreNLP 4.5.10 JARs (gitignored)

**Validated results (full hearing document — PN908 S.Hrg. 111-695, Pt. 3):**
- 400 speaker turns parsed, 28 unique speakers, 165,522 chars of speech
- 3 hearing sections detected (July 29, Sept 9, Sept 23, 2009), 12 nominees across 3 panels
- 400/400 turns resolved to targets (0 UNKNOWN):
  - SELF: 152 (nominee speaking — confidence 1.0)
  - RESPONSE_PAIR: 103 (nominee responds after senator — 0.90)
  - PRIOR_CONTEXT: 79 (inherits target from ongoing Q&A — 0.70)
  - DIRECT_ADDRESS: 46 (speaker names nominee in text — 0.95)
  - SECTION_DEFAULT: 20 (falls back to panel — 0.30–0.50)
- Top speakers: Senator Sessions (61 turns, 23K chars), Senator Whitehouse (46), Senator Franken (43), Chairman Leahy (34)

**Target resolution strategy (priority order):**
1. **SELF** — speaker IS a nominee on the current panel
2. **DIRECT_ADDRESS** — speaker names a nominee in their text ("Judge Chen, let me ask...")
3. **RESPONSE_PAIR** — nominee responds immediately after a senator's turn
4. **PRIOR_CONTEXT** — inherits target from ongoing Q&A exchange between same senator
5. **SECTION_DEFAULT** — falls back to panel (single nominee = 0.50, multi = 0.30)

**Known parser limitations to address later:**
- Standalone speaker names used as section intros create small bogus turns
- ALL-CAPS section headings between speakers get absorbed as turn text
- `[Off microphone.]` turns have no useful content (use `hasSubstantiveText()` to filter)

**Next milestones (in order):**
1. Wire CoreNLP per-turn scoring — score each turn's text, aggregate by (senator, nominee) pairs
2. Output views: Nominee Scorecard, Senator Voting Profile, Precedent Heat Map
3. Add Apache POI `.docx` reading in Java (replace PowerShell extraction)
4. Speaker alias resolution (e.g., "Ranking Member Sessions" → "Senator Sessions")
5. Legal precedent detection (regex + dictionary — find case references)
6. Rule-based scoring layer (hostile question patterns, supportive framing)

**Do not build yet:**
- MySQL / database layer
- Batch processing
- Interactive viewer
- Full Maven project scaffold with all packages

### Future Phases (for context only — do not implement):

**Phase 2 — Per-Speaker Scoring & Output**
- Wire CoreNLP sentiment into speaker turns with target resolution
- Aggregate scores by (senator, nominee) pairs
- Output: Nominee Scorecard (sorted by party then score), Senator Voting Profile, Precedent Heat Map
- Apache POI for `.docx` reading in Java (replace PowerShell extraction)

**Phase 3 — Legal Precedent Detection & Dual-Target Scoring**
- Precedent dictionary (`precedents.json`)
- Regex + dictionary detection of case references
- Windowed sentiment around precedent mentions
- Rule-based scoring layer

**Phase 4 — Database & Batch Processing**
- MySQL schema, DAO layer, HikariCP
- Batch processing with idempotent re-runs
- Progress tracking

**Phase 5 — Interactive Viewer**
- Front-end to browse results by hearing, nominee, senator, or precedent
