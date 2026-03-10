import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * TargetResolver.java
 *
 * Determines WHO each speaker turn is directed toward in a Senate hearing
 * transcript. This is the key step that turns flat "speaker → text" data
 * into meaningful "senator → nominee" interaction pairs for scoring.
 *
 * <h3>Resolution strategy (applied in priority order):</h3>
 * <ol>
 *   <li><b>Self-detection</b> — if the speaker IS a nominee on the current
 *       panel, the turn is tagged as SELF.</li>
 *   <li><b>Direct address</b> (confidence 0.95) — the speaker names a
 *       nominee in their text ("Judge Chen, let me ask you...").</li>
 *   <li><b>Response pairing</b> (confidence 0.90) — a nominee responds
 *       immediately after this senator's turn. Also tags the senator's
 *       preceding turn retroactively.</li>
 *   <li><b>Prior context</b> (confidence 0.70) — no new target cue;
 *       inherits the nominee from the ongoing Q&A exchange.</li>
 *   <li><b>Section default</b> (confidence 0.30) — falls back to the
 *       nominee panel. If the panel has only one nominee, the target is
 *       clear; if multiple, the target is ambiguous.</li>
 * </ol>
 *
 * <h3>Required input:</h3>
 * <ul>
 *   <li>A list of {@link SpeakerTurn} objects (from {@link SpeakerTurnParser})</li>
 *   <li>The raw transcript lines (for section-heading detection)</li>
 * </ul>
 *
 * <p>Run standalone: {@code java TargetResolver hearing.txt}</p>
 */
public class TargetResolver {

    // ── Section header patterns ──────────────────────────────────────────

    /** Matches "NOMINATIONS OF ..." or "NOMINATION OF ..." header lines. */
    private static final Pattern NOMINATIONS_HEADER = Pattern.compile(
        "^\\s*NOMINATIONS?\\s+OF\\s+(.+)",
        Pattern.CASE_INSENSITIVE
    );

    /** Matches hearing date lines like "WEDNESDAY, JULY 29, 2009". */
    private static final Pattern DATE_LINE = Pattern.compile(
        "^\\s*(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY),\\s+" +
        "(JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|AUGUST|SEPTEMBER|" +
        "OCTOBER|NOVEMBER|DECEMBER)\\s+(\\d{1,2}),\\s+(\\d{4})\\s*$"
    );

    /**
     * Extracts nominee names from a NOMINATIONS header block.
     * Matches patterns like:
     *   "BEVERLY BALDWIN MARTIN, NOMINEE TO BE UNITED STATES CIRCUIT JUDGE"
     *   "DAVID J. KAPPOS, NOMINEE TO BE UNDER SECRETARY OF COMMERCE"
     *   "JACQUELINE H. NGUYEN, OF CALIFORNIA, TO BE DISTRICT JUDGE"
     */
    private static final Pattern NOMINEE_IN_HEADER = Pattern.compile(
        "([A-Z][A-Z.]+(?:\\s+[A-Z][A-Z.]+)+),\\s+(?:NOMINEE\\s+)?(?:OF\\s+[A-Z]+,\\s+)?TO\\s+BE\\s+(.+?)(?:;|$)"
    );

    /** Detects direct-address patterns in turn text. */
    private static final Pattern DIRECT_ADDRESS = Pattern.compile(
        "(?:Judge|Ms\\.|Mr\\.|Mrs\\.)\\s+(\\w+),"
    );

    // ── Known nominee titles (for identifying nominee turns) ─────────────

    private static final Set<String> NOMINEE_TITLES = Set.of(
        "Mr.", "Ms.", "Mrs.", "Judge"
    );

    private static final Set<String> SENATOR_TITLES = Set.of(
        "Chairman", "The Chairman", "Senator"
    );

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Resolve targets for all speaker turns.
     *
     * @param turns the parsed speaker turns
     * @param lines the raw transcript lines (for section-heading detection)
     * @return a ResolvedTarget for each turn, in order
     */
    public static List<ResolvedTarget> resolve(List<SpeakerTurn> turns,
                                               List<String> lines) {

        // 1. Detect hearing sections (nominee panels)
        List<HearingSection> sections = detectSections(lines);

        // 2. Build a map: nominee lastName → NomineeInfo (across all sections)
        Map<String, NomineeInfo> allNominees = new LinkedHashMap<>();
        for (HearingSection sec : sections) {
            for (NomineeInfo n : sec.getNominees()) {
                allNominees.put(n.getLastName().toLowerCase(), n);
            }
        }

        // 2b. Correct nominee titles from how they actually appear in
        //     transcript turns (more reliable than header inference).
        correctNomineeTitlesFromTurns(turns, allNominees, sections);

        // 3. Resolve each turn
        List<ResolvedTarget> results = new ArrayList<>();
        NomineeInfo currentTarget = null;  // tracks the ongoing Q&A target

        for (int i = 0; i < turns.size(); i++) {
            SpeakerTurn turn = turns.get(i);

            // Find which section this turn belongs to
            HearingSection section = findSection(turn.getStartLine(), sections);
            List<NomineeInfo> panelNominees = section != null
                ? new ArrayList<>(section.getNominees())
                : Collections.emptyList();

            // ── A) Self-detection: is the speaker a nominee? ──
            if (NOMINEE_TITLES.contains(turn.getTitle())) {
                NomineeInfo self = allNominees.get(turn.getLastName().toLowerCase());
                if (self != null) {
                    currentTarget = self;
                    results.add(new ResolvedTarget(turn, self, panelNominees,
                        ResolvedTarget.Method.SELF, 1.0));
                    continue;
                }
            }

            // ── B) Direct address: speaker names a nominee in their text ──
            NomineeInfo directTarget = findDirectAddress(turn.getText(), allNominees);
            if (directTarget != null) {
                currentTarget = directTarget;
                results.add(new ResolvedTarget(turn, directTarget, panelNominees,
                    ResolvedTarget.Method.DIRECT_ADDRESS, 0.95));
                continue;
            }

            // ── C) Response pairing: does a nominee respond next? ──
            if (SENATOR_TITLES.contains(turn.getTitle()) && i + 1 < turns.size()) {
                SpeakerTurn nextTurn = turns.get(i + 1);
                if (NOMINEE_TITLES.contains(nextTurn.getTitle())) {
                    NomineeInfo responder = allNominees.get(
                        nextTurn.getLastName().toLowerCase());
                    if (responder != null) {
                        currentTarget = responder;
                        results.add(new ResolvedTarget(turn, responder,
                            panelNominees,
                            ResolvedTarget.Method.RESPONSE_PAIR, 0.90));
                        continue;
                    }
                }
            }

            // ── D) Prior context: inherit from ongoing exchange ──
            if (currentTarget != null && section != null
                    && section.findNominee(currentTarget.getLastName()) != null) {
                results.add(new ResolvedTarget(turn, currentTarget,
                    panelNominees,
                    ResolvedTarget.Method.PRIOR_CONTEXT, 0.70));
                continue;
            }

            // ── E) Section default: fall back to the panel ──
            if (section != null && panelNominees.size() == 1) {
                currentTarget = panelNominees.get(0);
                results.add(new ResolvedTarget(turn, currentTarget,
                    panelNominees,
                    ResolvedTarget.Method.SECTION_DEFAULT, 0.50));
                continue;
            }
            if (section != null && !panelNominees.isEmpty()) {
                results.add(new ResolvedTarget(turn, null, panelNominees,
                    ResolvedTarget.Method.SECTION_DEFAULT, 0.30));
                continue;
            }

            // ── F) Unknown ──
            currentTarget = null;
            results.add(new ResolvedTarget(turn, null, panelNominees,
                ResolvedTarget.Method.UNKNOWN, 0.0));
        }

        return results;
    }

    // ── Section detection ────────────────────────────────────────────────

    /**
     * Walk the raw transcript lines and find NOMINATIONS OF ... header blocks.
     * Each block starts a new section. Section boundaries are set so that
     * each section ends where the next begins.
     */
    static List<HearingSection> detectSections(List<String> lines) {
        List<HearingSection> sections = new ArrayList<>();
        int sectionNum = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // Detect NOMINATIONS OF ... header
            Matcher nm = NOMINATIONS_HEADER.matcher(line);
            if (nm.matches()) {
                // Accumulate the full header block (may span several lines)
                StringBuilder headerBuf = new StringBuilder(line.trim());
                int j = i + 1;
                // Header typically continues until a blank line or a date line
                while (j < lines.size()
                        && !lines.get(j).trim().isEmpty()
                        && !DATE_LINE.matcher(lines.get(j)).matches()
                        && !NOMINATIONS_HEADER.matcher(lines.get(j)).matches()) {
                    headerBuf.append(" ").append(lines.get(j).trim());
                    j++;
                }

                // Look FORWARD (within 20 lines) for the hearing date
                String sectionDate = null;
                for (int k = j; k < Math.min(j + 20, lines.size()); k++) {
                    Matcher dm = DATE_LINE.matcher(lines.get(k));
                    if (dm.matches()) {
                        sectionDate = dm.group(2) + " " + dm.group(3) + ", " + dm.group(4);
                        break;
                    }
                }

                String fullHeader = headerBuf.toString();
                sectionNum++;
                HearingSection section = new HearingSection(
                    sectionNum, fullHeader, sectionDate, i);

                // Parse nominee names from the header
                parseNomineesFromHeader(fullHeader, section);

                // Close the previous section
                if (!sections.isEmpty()) {
                    sections.get(sections.size() - 1).setEndLine(i);
                }

                sections.add(section);
            }
        }

        return sections;
    }

    /**
     * Extract nominee names and positions from a NOMINATIONS header string.
     * Header format: "NOMINATIONS OF FIRST LAST, NOMINEE TO BE POSITION;
     * FIRST LAST, NOMINEE TO BE POSITION; AND, FIRST LAST, ..."
     */
    static void parseNomineesFromHeader(String header, HearingSection section) {
        // Split on semicolons to handle multi-nominee headers
        // Then extract name + position from each chunk
        String cleaned = header
            .replaceAll("^\\s*NOMINATIONS?\\s+OF\\s+", "")
            .replaceAll("\\s+AND,?\\s+", "; ")
            .replaceAll("\\s+", " ")
            .trim();

        // Split on ";" to get individual nominee chunks
        String[] chunks = cleaned.split(";");

        for (String chunk : chunks) {
            chunk = chunk.trim();
            if (chunk.isEmpty()) continue;

            // Try to match: "FIRST [MIDDLE] LAST, [NOMINEE] [OF STATE,] TO BE POSITION"
            Matcher m = NOMINEE_IN_HEADER.matcher(chunk);
            if (m.find()) {
                String rawName = m.group(1).trim();
                String position = m.group(2).trim();

                // Parse first/last from ALL-CAPS name
                String[] nameParts = rawName.split("\\s+");
                String firstName = toTitleCase(nameParts[0]);
                String lastName = toTitleCase(nameParts[nameParts.length - 1]);

                // Determine title used in transcript
                String titleUsed = inferTitle(position, rawName);

                section.addNominee(new NomineeInfo(firstName, lastName,
                    toTitleCase(position), titleUsed));
            }
        }
    }

    // ── Direct address detection ─────────────────────────────────────────

    /**
     * Scan turn text for direct-address patterns like "Judge Chen, ..."
     * Returns the first matching nominee, or null.
     */
    static NomineeInfo findDirectAddress(String text,
                                         Map<String, NomineeInfo> nominees) {
        Matcher m = DIRECT_ADDRESS.matcher(text);
        while (m.find()) {
            String name = m.group(1).toLowerCase();
            NomineeInfo match = nominees.get(name);
            if (match != null) return match;
        }
        return null;
    }

    // ── Title correction from transcript usage ──────────────────────────

    /**
     * The NOMINATIONS header doesn't reliably tell us whether a nominee
     * is addressed as "Ms.", "Mr.", or "Judge" in the transcript. This
     * method scans the parsed turns to find how each nominee is actually
     * addressed, then updates the NomineeInfo objects accordingly.
     */
    private static void correctNomineeTitlesFromTurns(
            List<SpeakerTurn> turns,
            Map<String, NomineeInfo> allNominees,
            List<HearingSection> sections) {

        for (SpeakerTurn turn : turns) {
            if (!NOMINEE_TITLES.contains(turn.getTitle())) continue;
            String key = turn.getLastName().toLowerCase();
            NomineeInfo existing = allNominees.get(key);
            if (existing == null) continue;

            String actualTitle = turn.getTitle();
            if (!actualTitle.equals(existing.getTitleUsed())) {
                // Replace with corrected version
                NomineeInfo corrected = new NomineeInfo(
                    existing.getFirstName(), existing.getLastName(),
                    existing.getPosition(), actualTitle);
                allNominees.put(key, corrected);
                // Also update in the sections
                for (HearingSection sec : sections) {
                    if (sec.findNominee(existing.getLastName()) != null) {
                        sec.addNominee(corrected);  // overwrites by lastName key
                    }
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static HearingSection findSection(int lineNumber,
                                              List<HearingSection> sections) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            if (sections.get(i).containsLine(lineNumber)) {
                return sections.get(i);
            }
        }
        return null;
    }

    /** Infer the transcript title (Mr., Ms., Judge, etc.) from the position. */
    private static String inferTitle(String position, String rawName) {
        String posLower = position.toLowerCase();
        if (posLower.contains("judge")) return "Judge";
        // Check for "HON." prefix (indicates existing judge)
        if (rawName.startsWith("HON.") || rawName.startsWith("HON ")) return "Judge";
        // Default: Mr./Ms. — without more data we'll use Mr.
        // This can be overridden later with a gender lookup
        return "Mr.";
    }

    /** Converts "BEVERLY" → "Beverly", "KAPPOS" → "Kappos". */
    private static String toTitleCase(String allCaps) {
        if (allCaps == null || allCaps.isEmpty()) return allCaps;
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : allCaps.toCharArray()) {
            if (Character.isWhitespace(c) || c == '-') {
                sb.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ── Standalone runner ────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {

        // 1. Read input
        String text;
        if (args.length > 0) {
            text = new String(Files.readAllBytes(Paths.get(args[0])));
        } else {
            text = getDefaultTestText();
        }

        List<String> lines = Arrays.asList(text.split("\\r?\\n"));

        // 2. Parse speaker turns
        List<SpeakerTurn> turns = SpeakerTurnParser.parse(lines);

        // 3. Detect sections
        List<HearingSection> sections = detectSections(lines);
        System.out.println("=".repeat(78));
        System.out.println("  Target Resolution");
        System.out.println("=".repeat(78));
        System.out.println();

        // Print detected sections
        System.out.println("  HEARING SECTIONS DETECTED:");
        for (HearingSection sec : sections) {
            System.out.println("    " + sec);
            for (NomineeInfo n : sec.getNominees()) {
                System.out.println("      - " + n);
            }
        }
        System.out.println();

        // 4. Resolve targets
        List<ResolvedTarget> resolved = resolve(turns, lines);

        // 5. Print per-turn results
        System.out.println("  RESOLVED TURNS:");
        System.out.println("-".repeat(78));

        for (ResolvedTarget rt : resolved) {
            String speaker = String.format("%-22s", rt.getTurn().getSpeakerLabel());
            System.out.printf("  Turn %3d  %s  %s%n",
                rt.getTurn().getTurnNumber(), speaker, rt.getTargetLabel());
        }
        System.out.println();

        // 6. Aggregated interaction matrix: senator × nominee
        System.out.println("-".repeat(78));
        System.out.println("  INTERACTION MATRIX (senator → nominee turn count):");
        System.out.println("-".repeat(78));

        // Build matrix
        Map<String, Map<String, int[]>> matrix = new LinkedHashMap<>();
        for (ResolvedTarget rt : resolved) {
            if (rt.isSelfTurn()) continue;
            if (!rt.hasSpecificTarget()) continue;
            if (!SENATOR_TITLES.contains(rt.getTurn().getTitle())) continue;

            String senator = rt.getTurn().getSpeakerLabel();
            String nominee = rt.getNominee().getDisplayName();
            matrix.computeIfAbsent(senator, k -> new LinkedHashMap<>())
                  .computeIfAbsent(nominee, k -> new int[]{0, 0}); // [turns, chars]
            matrix.get(senator).get(nominee)[0]++;
            matrix.get(senator).get(nominee)[1] += rt.getTurn().getText().length();
        }

        // Print
        for (var entry : matrix.entrySet()) {
            System.out.printf("  %s%n", entry.getKey());
            entry.getValue().entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> System.out.printf("    → %-22s  %3d turns  %6d chars%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
        }

        // 7. Resolution method distribution
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println("  RESOLUTION METHODS:");
        Map<ResolvedTarget.Method, Integer> methodCounts = new LinkedHashMap<>();
        for (ResolvedTarget rt : resolved) {
            methodCounts.merge(rt.getMethod(), 1, Integer::sum);
        }
        for (var entry : methodCounts.entrySet()) {
            System.out.printf("    %-20s  %4d turns%n",
                entry.getKey(), entry.getValue());
        }
        System.out.println("=".repeat(78));
    }

    // ── Default test text ────────────────────────────────────────────────

    private static String getDefaultTestText() {
        return String.join("\n",
            "                         WEDNESDAY, JULY 29, 2009",
            "",
            "  NOMINATIONS OF BEVERLY BALDWIN MARTIN, NOMINEE TO BE UNITED STATES",
            "CIRCUIT JUDGE FOR THE ELEVENTH CIRCUIT; JEFFREY L. VIKEN, NOMINEE TO BE",
            "  UNITED STATES DISTRICT JUDGE FOR THE DISTRICT OF SOUTH DAKOTA; AND,",
            "    DAVID J. KAPPOS, NOMINEE TO BE UNDER SECRETARY OF COMMERCE FOR",
            "  INTELLECTUAL PROPERTY AND DIRECTOR OF THE U.S. PATENT AND TRADEMARK",
            "                                 OFFICE",
            "",
            "    Chairman Leahy. Good morning. We welcome our nominees today.",
            "Judge Martin, I am delighted to see you here.",
            "    Senator Sessions. Thank you, Chairman Leahy. Judge Martin, I want",
            "to welcome you and the other nominees. I have a few questions.",
            "    Ms. Martin. Thank you, Senator Sessions. I appreciate the opportunity",
            "to be here today and I look forward to answering your questions.",
            "    Senator Sessions. Judge Martin, can you tell us about your experience",
            "on the bench?",
            "    Ms. Martin. Certainly. I have served for 9 years on the Northern",
            "District of Georgia and handled over 3,100 cases.",
            "    Chairman Leahy. Thank you. Mr. Kappos, do you have family here?",
            "    Mr. Kappos. Yes, I do, Chairman.",
            "    Chairman Leahy. Mr. Viken, I notice you supervise magistrate judges.",
            "    Mr. Viken. Yes, Mr. Chairman, that is correct.",
            "    Senator Sessions. Mr. Kappos, can you discuss patent reform?",
            "    Mr. Kappos. Absolutely, Senator. Patent reform is critical to our",
            "innovation economy and I believe strongly in it."
        );
    }
}
