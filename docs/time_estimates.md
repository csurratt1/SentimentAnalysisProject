# Project Time Estimates — Sentiment Analysis Pipeline

**Project:** Senate Confirmation Hearing Sentiment Analysis  
**Course:** CSC 470  
**Developer:** Colton Surratt  
**Note:** All estimated and actual hours use 15-minute (0.25 hr) increments.

---

## Component Breakdown

### 1. Project Planning & Design

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Define project scope and goals | 0.50 | 0.50 | Initial meeting with advisor |
| Design database schema (ER diagram) | 1.00 | 1.25 | Added hearing_sections table mid-design |
| Create UML class diagrams | 1.50 | 1.75 | 4 major component diagrams |
| Create UI mockups (dashboard) | 1.00 | 1.00 | HTML prototype in docs/copilot |
| Write planning documents / time estimates | 0.75 | 0.75 | This document |
| **Subtotal** | **4.75** | **5.25** | |

---

### 2. Database Layer

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Write SQL schema (schema.sql) | 1.00 | 1.25 | 7 tables + interactions view |
| Implement DatabaseManager (JDBC wrapper) | 2.00 | 2.25 | Parameterized queries, property loader |
| Implement entity classes (Hearing, Speaker, Turn, etc.) | 3.00 | 3.50 | 6 entity classes with CRUD methods |
| Implement ScoringPersistence (transactional writer) | 3.00 | 3.75 | Idempotent re-run logic, rollback handling |
| Test DB connection and CRUD operations | 1.00 | 1.25 | Manual integration testing |
| **Subtotal** | **10.00** | **12.00** | |

---

### 3. Text Parsing Component

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Research transcript format and edge cases | 1.00 | 1.25 | Read multiple hearing PDFs |
| Implement SpeakerTurnParser regex state machine | 2.00 | 2.50 | Handle continuation lines, TOC lines, annotations |
| Implement SpeakerTurn model class | 0.50 | 0.50 | |
| Add .docx support via Apache POI | 1.00 | 1.00 | |
| Test parser against 3+ sample transcripts | 1.50 | 1.75 | Edge cases: bracketed notes, no-title speakers |
| **Subtotal** | **6.00** | **7.00** | |

---

### 4. Target Resolution Component

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Design resolution method hierarchy (6 methods) | 1.00 | 1.25 | SELF → DIRECT_ADDRESS → RESPONSE_PAIR → ... |
| Implement TargetResolver.detectSections() | 1.50 | 2.00 | NOMINATIONS_OF header parsing |
| Implement direct address detection (regex + NER) | 2.00 | 2.25 | Pattern matching for "Judge Chen," patterns |
| Implement RESPONSE_PAIR and PRIOR_CONTEXT logic | 2.00 | 2.50 | Q&A pair detection |
| Implement ResolvedTarget model with confidence scoring | 1.00 | 1.00 | |
| Implement NomineeInfo and HearingSection models | 0.75 | 0.75 | |
| Test resolution accuracy against known transcripts | 2.00 | 2.25 | Compared resolution output to manual labels |
| **Subtotal** | **10.25** | **12.00** | |

---

### 5. Sentiment Scoring Component

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Research StanfordCoreNLP sentiment pipeline | 1.00 | 1.25 | Evaluated tokenize, ssplit, pos, parse, sentiment |
| Integrate CoreNLP as Maven dependency | 0.75 | 1.00 | Version compatibility issues with Java |
| Implement lazy-load cached pipeline (thread-safe) | 1.00 | 1.25 | PIPELINE_LOCK singleton pattern |
| Implement sentence-level scoring [-2, +2] | 1.00 | 1.00 | toScore(classIndex - 2) mapping |
| Implement ScoredTurn model with weighted scores | 0.75 | 0.75 | confidence-weighted scoring |
| Implement SpeakerAliasResolver (senator label merging) | 1.50 | 1.75 | Canonical label election by count + title preference |
| Implement aggregateByPair() interaction map | 1.25 | 1.25 | Keyed by "senator|nominee" string |
| Build JSON and text report generators | 2.00 | 2.25 | Metadata, turns array, scorecard sections |
| **Subtotal** | **9.25** | **10.50** | |

---

### 6. GUI Application

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Set up Swing application skeleton (CardLayout, nav sidebar) | 1.50 | 1.75 | Dark theme, custom color palette |
| Build Dashboard screen (recent outputs, DB summary) | 1.50 | 1.75 | |
| Build Run Analysis screen (file lists, options, log area) | 2.00 | 2.50 | Add/Remove/SelectAll, batch worker spinner |
| Build Results screen (file browser + viewer) | 1.25 | 1.50 | txt and JSON display |
| Implement single-file analysis flow with preview modal | 2.00 | 2.25 | Text + JSON tabs in JTabbedPane |
| Implement batch analysis flow (parallel workers) | 1.50 | 1.75 | SwingWorker, thread-safe log updates |
| Implement DB summary refresh and pending file rename | 1.25 | 1.50 | Atomic commit after DB success |
| Polish UI (labels, borders, spacing, FileNameRenderer) | 1.00 | 1.25 | |
| **Subtotal** | **12.00** | **14.25** | |

---

### 7. Error Handling & Logging

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Add try/catch with user-facing error messages in GUI | 1.00 | 1.00 | Dialog boxes + log area messages |
| Implement error logging (file-based or console) | 0.75 | 0.75 | |
| Add input validation (file type, empty input guards) | 0.75 | 1.00 | |
| Graceful DB failure handling (rollback, pending cleanup) | 1.00 | 1.00 | |
| **Subtotal** | **3.50** | **3.75** | |

---

### 8. Testing

| Task | Estimated (hrs) | Actual (hrs) | Notes |
|------|-----------------|--------------|-------|
| Unit tests for SpeakerTurnParser | 1.50 | 1.50 | Edge cases: annotations, continuation lines |
| Unit tests for TargetResolver | 1.50 | 1.75 | Test each resolution method independently |
| Unit tests for TurnScorer scoring logic | 1.00 | 1.00 | Score mapping, aggregation correctness |
| Integration test: full pipeline end-to-end | 1.50 | 1.75 | 5-document batch test |
| Integration test: DB persistence and re-run | 1.00 | 1.25 | Verify idempotent replace behavior |
| **Subtotal** | **6.50** | **7.25** | |

---

## Summary

| Component | Estimated (hrs) | Actual (hrs) |
|-----------|-----------------|--------------|
| Planning & Design | 4.75 | 5.25 |
| Database Layer | 10.00 | 12.00 |
| Text Parsing | 6.00 | 7.00 |
| Target Resolution | 10.25 | 12.00 |
| Sentiment Scoring | 9.25 | 10.50 |
| GUI Application | 12.00 | 14.25 |
| Error Handling & Logging | 3.50 | 3.75 |
| Testing | 6.50 | 7.25 |
| **TOTAL** | **62.25** | **72.00** |

---

*All times recorded in 0.25 hr (15-minute) increments per course requirements.*
