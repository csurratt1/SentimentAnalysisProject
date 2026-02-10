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

Generate the **complete file and directory structure** for this project as a Maven Java project. Create all the files with:
- Proper package declarations
- Class/interface declarations with JavaDoc describing purpose
- Method signatures (no implementations yet — just signatures with TODO comments)
- Proper imports where obvious
- Indicate which classes depend on which

The structure should follow standard Maven conventions (`src/main/java`, `src/main/resources`, `src/test/java`) and organize code into these packages:
- `edu.concordia.sentiment` — main App entry point
- `edu.concordia.sentiment.config` — configuration and properties loading
- `edu.concordia.sentiment.model` — POJOs/data classes (Hearing, Speaker, Nominee, Turn, PrecedentReference)
- `edu.concordia.sentiment.ingest` — document reading (DocxParser, TranscriptIngestor)
- `edu.concordia.sentiment.parse` — speaker identification and turn segmentation
- `edu.concordia.sentiment.legal` — precedent detection and legal reference extraction
- `edu.concordia.sentiment.sentiment` — CoreNLP integration, rule-based scoring, aggregation
- `edu.concordia.sentiment.db` — DAO layer, connection pool, schema management
- `edu.concordia.sentiment.batch` — batch processing, progress tracking
- `edu.concordia.sentiment.util` — shared utilities (text cleaning, etc.)

Also include:
- `pom.xml` with all dependencies
- `src/main/resources/application.properties` template
- `src/main/resources/precedents.json` structure for the case dictionary
- `scripts/schema.sql` for MySQL table creation
- `data/input/` directory (with a `.gitkeep`)
- `README.md` skeleton
- `.gitignore` for Java/Maven
