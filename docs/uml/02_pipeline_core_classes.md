# UML: Core Pipeline Classes

```mermaid
classDiagram
    class TurnScorer {
        -StanfordCoreNLP scoringPipeline$
        -Object PIPELINE_LOCK$
        -String[] LABELS$
        -Set~String~ SENATOR_TITLES$
        +analyzeOnly(String text, String sourceName) AnalysisBundle
        +runPipeline(File inputFile, boolean persistDb, boolean returnBundle) RunResult
        +finalizeRun(AnalysisBundle bundle, boolean persistDb) RunResult
        +buildTextPreview(AnalysisBundle bundle) String
        +buildJsonPreview(AnalysisBundle bundle) String
        -loadTranscriptText(File f) String
        -getScoringPipeline() StanfordCoreNLP
        -scoreSubstantiveTurns(List~ResolvedTarget~ targets) List~ScoredTurn~
        -aggregateByPair(List~ScoredTurn~ turns) Map~String,InteractionScore~
        -buildJsonOutput(AnalysisBundle bundle) String
        -buildTextOutput(AnalysisBundle bundle) String
    }

    class RunResult {
        +File jsonFile
        +File textFile
        +int totalTurns
        +int scoredTurns
        +String dbMessage
    }

    class AnalysisBundle {
        +String sourceFileName
        +List~SpeakerTurn~ allTurns
        +List~ResolvedTarget~ resolvedTargets
        +List~ScoredTurn~ scoredTurns
        +Map~String,InteractionScore~ interactions
        +SpeakerAliasResolver.AliasMap aliasMap
    }

    class InteractionScore {
        +String senatorLabel
        +String nomineeLabel
        +int turnCount
        +double totalWeightedScore
        +double totalRawScore
        +double totalConfidence
        +double avgWeightedScore
        +double avgRawScore
        +Map~String,Integer~ methodCounts
    }

    class SpeakerTurnParser {
        -Pattern SPEAKER_LINE$
        -Pattern TOC_DOTS$
        -Pattern ANNOTATION_LINE$
        +parse(String text) List~SpeakerTurn~
        +parse(List~String~ lines) List~SpeakerTurn~
    }

    class SpeakerTurn {
        -String title
        -String lastName
        -int turnNumber
        -String text
        -int startLine
        +getSpeakerLabel() String
        +hasSubstantiveText() boolean
        +getTitle() String
        +getLastName() String
        +getText() String
        +getTurnNumber() int
    }

    class TargetResolver {
        -Pattern NOMINATIONS_HEADER$
        -Pattern DATE_LINE$
        -Pattern NOMINEE_IN_HEADER$
        -Pattern DIRECT_ADDRESS$
        -List~SpeakerTurn~ turns
        -List~String~ lines
        +resolve() List~ResolvedTarget~
        -detectSections() List~HearingSection~
        -findDirectAddress(SpeakerTurn turn) NomineeInfo
    }

    class ResolvedTarget {
        -SpeakerTurn turn
        -NomineeInfo nominee
        -List~NomineeInfo~ panelNominees
        -Method method
        -double confidence
        +hasSpecificTarget() boolean
        +isSelfTurn() boolean
        +getTargetLabel() String
        +getConfidence() double
    }

    class ScoredTurn {
        -ResolvedTarget target
        -int sentenceCount
        -double totalScore
        -double avgScore
        -double avgSentimentConfidence
        +getWeightedScore() double
        +getAvgScore() double
        +getTarget() ResolvedTarget
    }

    class SpeakerAliasResolver {
        +resolve(List~SpeakerTurn~ turns) AliasMap
    }

    class AliasMap {
        -Map~String,String~ aliasToCanonical
        -Map~String,List~String~~ canonicalToAliases
        -Map~String,Integer~ aliasTurnCounts
        +canonicalize(String label) String
        +getAliases(String canonicalLabel) List~String~
        +getAliasTurnCount(String label) int
        +hasAnyAliases() boolean
    }

    class ScoringPersistence {
        -DatabaseManager db
        +persistRun(AnalysisBundle bundle, File sourceFile) PersistenceResult
        -findHearingIdBySourceFile(String sourceFile) int
        -upsertHearing(String sourceFile) int
        -clearHearingChildren(int hearingId) void
        -insertSections(int hearingId, List~HearingSection~ sections) Map
        -insertNominations(int hearingId, Map sectionIds) Map
        -insertTurns(int hearingId, List~SpeakerTurn~ turns) Map
        -insertScoringRun(int hearingId, AnalysisBundle bundle) int
        -insertTurnScores(int runId, List~ScoredTurn~ turns, Map turnIds) void
    }

    class PersistenceResult {
        +int hearingId
        +int scoringRunId
        +int turnsInserted
        +int turnScoresInserted
        +boolean replacedExistingRun
    }

    TurnScorer --> SpeakerTurnParser : uses
    TurnScorer --> TargetResolver : uses
    TurnScorer --> SpeakerAliasResolver : uses
    TurnScorer --> ScoringPersistence : uses
    TurnScorer +-- RunResult : inner class
    TurnScorer +-- AnalysisBundle : inner class
    TurnScorer +-- InteractionScore : inner class
    SpeakerTurnParser ..> SpeakerTurn : creates
    TargetResolver ..> ResolvedTarget : creates
    TargetResolver --> SpeakerTurn : reads
    ResolvedTarget --> SpeakerTurn : wraps
    ResolvedTarget --> NomineeInfo : targets
    ScoredTurn --> ResolvedTarget : wraps
    SpeakerAliasResolver +-- AliasMap : inner class
    ScoringPersistence +-- PersistenceResult : inner class
    ScoringPersistence --> AnalysisBundle : reads
```
