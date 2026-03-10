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

-- ── Hearings table ───────────────────────────────────────────────────
-- One row per hearing transcript processed.
-- This is the root entity — other tables reference it.

CREATE TABLE IF NOT EXISTS hearings (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    hearing_date    DATE                    COMMENT 'Date of the hearing',
    session         VARCHAR(50)             COMMENT 'e.g. 111th Congress, 1st Session',
    serial_number   VARCHAR(100)            COMMENT 'e.g. S.Hrg. 111-695, Pt. 3',
    committee       VARCHAR(255)            COMMENT 'e.g. Senate Judiciary Committee',
    source_file     VARCHAR(500)            COMMENT 'Original filename (.docx or .txt)',
    title           VARCHAR(1000)           COMMENT 'Descriptive title of the hearing',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Prevent duplicate imports of the same file
    UNIQUE KEY uq_source_file (source_file(255))
) ENGINE=InnoDB;

-- ======================================================================
-- Future tables (not yet created — listed for reference)
-- ======================================================================
--
-- speakers        — canonical speaker entities (name, title, party, state, role)
-- nominees        — nominees with position and hearing link
-- turns           — individual speaker turns (text, speaker, nominee, order)
-- turn_sentiment  — CoreNLP scores + approval scores per turn
-- precedent_refs  — detected legal references with sentiment
-- precedent_dict  — lookup table of landmark cases
--
-- These will be added as the project progresses.
-- ======================================================================
