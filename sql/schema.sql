-- ======================================================================
-- Senate Hearing Sentiment Analysis — Database Schema
-- ======================================================================
-- Run this script against your MySQL instance to create the database
-- and tables. Requires MySQL 8.0+.
--
-- Usage:
--   mysql -u root -p < sql/schema.sql
-- ======================================================================

-- Create the database if it doesn't exist
CREATE DATABASE IF NOT EXISTS sentiment_analysis
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE sentiment_analysis;

-- Drop view first to avoid dependency issues on table recreation
DROP VIEW IF EXISTS interactions_view;

-- Drop in child-to-parent order
DROP TABLE IF EXISTS turn_scores;
DROP TABLE IF EXISTS scoring_runs;
DROP TABLE IF EXISTS turns;
DROP TABLE IF EXISTS nominations;
DROP TABLE IF EXISTS hearing_sections;
DROP TABLE IF EXISTS speakers;
DROP TABLE IF EXISTS hearings;

-- 1) SPEAKERS: canonical person entities reused across hearings
CREATE TABLE speakers (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    first_name      VARCHAR(100),
    last_name       VARCHAR(100) NOT NULL,
    canonical_name  VARCHAR(200),
    party           CHAR(1),
    state           VARCHAR(50),
    role            ENUM('SENATOR','NOMINEE','PRESENTER','OTHER') NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_last_name (last_name),
    INDEX idx_role (role)
) ENGINE=InnoDB;

-- 2) HEARINGS: root entity, one per transcript
CREATE TABLE hearings (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    hearing_date    DATE,
    session         VARCHAR(50),
    serial_number   VARCHAR(100),
    committee       VARCHAR(255),
    source_file     VARCHAR(500),
    title           VARCHAR(1000),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_source_file (source_file(255))
) ENGINE=InnoDB;

-- 3) HEARING_SECTIONS: nominee panels within a hearing
CREATE TABLE hearing_sections (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id      INT NOT NULL,
    section_number  INT NOT NULL,
    header_text     TEXT,
    section_date    VARCHAR(50),
    start_line      INT,
    end_line        INT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_hearing_section (hearing_id, section_number),
    CONSTRAINT fk_section_hearing
        FOREIGN KEY (hearing_id) REFERENCES hearings(id)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- 4) NOMINATIONS: nomination event for a person within a section
CREATE TABLE nominations (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id          INT NOT NULL,
    hearing_section_id  INT NOT NULL,
    speaker_id          INT NOT NULL,
    position            VARCHAR(500),
    title_used          VARCHAR(20),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_section_speaker (hearing_section_id, speaker_id),
    INDEX idx_nomination_hearing (hearing_id),
    INDEX idx_nomination_speaker (speaker_id),
    CONSTRAINT fk_nomination_hearing
        FOREIGN KEY (hearing_id) REFERENCES hearings(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_nomination_section
        FOREIGN KEY (hearing_section_id) REFERENCES hearing_sections(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_nomination_speaker
        FOREIGN KEY (speaker_id) REFERENCES speakers(id)
) ENGINE=InnoDB;

-- 5) TURNS: every speaker turn with full text
CREATE TABLE turns (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id          INT NOT NULL,
    hearing_section_id  INT,
    speaker_id          INT NOT NULL,
    turn_number         INT NOT NULL,
    speaker_label       VARCHAR(200),
    text                TEXT NOT NULL,
    start_line          INT,
    char_count          INT,
    is_substantive      BOOLEAN DEFAULT TRUE,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_hearing_turn (hearing_id, turn_number),
    INDEX idx_turn_speaker (speaker_id),
    INDEX idx_turn_section (hearing_section_id),
    CONSTRAINT fk_turn_hearing
        FOREIGN KEY (hearing_id) REFERENCES hearings(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_turn_section
        FOREIGN KEY (hearing_section_id) REFERENCES hearing_sections(id)
        ON DELETE SET NULL,
    CONSTRAINT fk_turn_speaker
        FOREIGN KEY (speaker_id) REFERENCES speakers(id)
) ENGINE=InnoDB;

-- 6) SCORING_RUNS: one row per pipeline execution
CREATE TABLE scoring_runs (
    id                      INT AUTO_INCREMENT PRIMARY KEY,
    hearing_id              INT NOT NULL,
    scored_at               DATETIME NOT NULL,
    parser_model            VARCHAR(200),
    total_turns             INT,
    scored_turns            INT,
    senator_turns           INT,
    self_turns              INT,
    skipped_turns           INT,
    sentences_processed     INT,
    unique_pairs            INT,
    avg_confidence          DECIMAL(6,4),
    scoring_time_seconds    DECIMAL(8,3),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_scoring_run_hearing (hearing_id),
    CONSTRAINT fk_scoring_run_hearing
        FOREIGN KEY (hearing_id) REFERENCES hearings(id)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- 7) TURN_SCORES: per-turn score data for each scoring run
CREATE TABLE turn_scores (
    id                      INT AUTO_INCREMENT PRIMARY KEY,
    turn_id                 INT NOT NULL,
    scoring_run_id          INT NOT NULL,
    target_nomination_id    INT,
    resolution_method       ENUM('SELF','DIRECT_ADDRESS','RESPONSE_PAIR','PRIOR_CONTEXT','SECTION_DEFAULT','UNKNOWN') NOT NULL,
    confidence              DECIMAL(4,3),
    sentence_count          INT,
    total_score             INT,
    avg_score               DECIMAL(6,4),
    weighted_score          DECIMAL(6,4),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_turn_run (turn_id, scoring_run_id),
    INDEX idx_turn_scores_run (scoring_run_id),
    INDEX idx_turn_scores_target (target_nomination_id),
    CONSTRAINT fk_turn_score_turn
        FOREIGN KEY (turn_id) REFERENCES turns(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_turn_score_run
        FOREIGN KEY (scoring_run_id) REFERENCES scoring_runs(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_turn_score_target_nomination
        FOREIGN KEY (target_nomination_id) REFERENCES nominations(id)
) ENGINE=InnoDB;

-- Computed aggregation view for senator -> nominee interaction analytics
CREATE VIEW interactions_view AS
SELECT
    ts.scoring_run_id,
    s_speaker.last_name AS senator_last_name,
    t.speaker_label AS senator_label,
    s_nominee.last_name AS nominee_last_name,
    CONCAT(n.title_used, ' ', s_nominee.last_name) AS nominee_label,
    COUNT(*) AS turns,
    SUM(ts.sentence_count) AS sentences,
    AVG(ts.weighted_score) AS avg_weighted_score,
    AVG(ts.avg_score) AS avg_raw_score,
    SUM(ts.weighted_score) AS total_weighted
FROM turn_scores ts
JOIN turns t ON ts.turn_id = t.id
JOIN speakers s_speaker ON t.speaker_id = s_speaker.id
JOIN nominations n ON ts.target_nomination_id = n.id
JOIN speakers s_nominee ON n.speaker_id = s_nominee.id
WHERE ts.resolution_method != 'SELF'
  AND ts.resolution_method != 'UNKNOWN'
GROUP BY ts.scoring_run_id, t.speaker_id, n.id;
