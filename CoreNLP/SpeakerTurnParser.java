import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * SpeakerTurnParser.java
 *
 * Regex state-machine that walks the lines of a GPO Senate hearing transcript
 * and segments them into individual {@link SpeakerTurn} objects.
 *
 * <h3>Format assumptions (learned from real GPO .docx output):</h3>
 * <ul>
 *   <li>Speaker transitions are lines like {@code "    Senator Sessions. Thank you..."}
 *       — 2+ leading spaces, then Title + LastName + period.</li>
 *   <li>Titles observed: Chairman, Senator, Mr., Ms., Mrs., Judge, General.</li>
 *   <li>Table-of-Contents lines contain dot-leaders ({@code ....}) with page
 *       numbers — these are filtered out.</li>
 *   <li>Bracketed annotations like {@code [Laughter.]} or {@code [Off microphone.]}
 *       are editorial metadata, not speech.</li>
 *   <li>Continuation lines (wrapped text) are not indented with the 4-space
 *       speaker prefix — they're simply appended to the current turn.</li>
 * </ul>
 *
 * <h3>Known limitations (to revisit in later phases):</h3>
 * <ul>
 *   <li>Section headings (ALL CAPS) between speakers get appended as text to
 *       the preceding turn.  A future pass can filter these.</li>
 *   <li>Standalone speaker names used as introductions (e.g., a bare
 *       {@code "Senator Isakson."} before a heading) produce a turn whose text
 *       is the heading.  Check {@link SpeakerTurn#hasSubstantiveText()}.</li>
 *   <li>Inline annotations inside speech (e.g., "I agree [nodding] fully")
 *       are preserved in the turn text — not stripped.</li>
 * </ul>
 *
 * Compile / Run: see run.ps1 (or run standalone with {@code java SpeakerTurnParser file.txt})
 */
public class SpeakerTurnParser {

    // ── Regex patterns ───────────────────────────────────────────────────

    /**
     * Matches a speaker-transition line.
     * <p>Group 1 = title, Group 2 = last name, Group 3 = rest of line after the period.</p>
     * "The Chairman" must precede "Chairman" in the alternation so the longer
     * form is tried first.
     */
    private static final Pattern SPEAKER_LINE = Pattern.compile(
        "^\\s{2,}(The Chairman|Chairman|Senator|Mr\\.|Ms\\.|Mrs\\.|Judge|General)" +
        "\\s+(\\w+)\\.\\s*(.*)$"
    );

    /**
     * Detects table-of-contents lines: four or more consecutive dots (dot-leaders)
     * optionally followed by a page number.  These are not speech.
     */
    private static final Pattern TOC_DOTS = Pattern.compile("\\.{4,}");

    /**
     * A line that is entirely a bracketed annotation, e.g. {@code "    [Laughter.]"}.
     */
    private static final Pattern ANNOTATION_LINE = Pattern.compile(
        "^\\s*\\[.*\\]\\s*$"
    );

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Parse a raw transcript string into speaker turns.
     *
     * @param text the full transcript as a single string (newlines preserved)
     * @return ordered list of speaker turns
     */
    public static List<SpeakerTurn> parse(String text) {
        return parse(Arrays.asList(text.split("\\r?\\n")));
    }

    /**
     * Parse a list of lines into speaker turns.
     *
     * @param lines the transcript split into individual lines
     * @return ordered list of speaker turns
     */
    public static List<SpeakerTurn> parse(List<String> lines) {

        List<SpeakerTurn> turns = new ArrayList<>();

        // State for the turn currently being accumulated
        String        curTitle = null;
        String        curName  = null;
        StringBuilder curText  = new StringBuilder();
        int           curStart = -1;
        int           turnSeq  = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // 1. Skip blank lines
            if (line.trim().isEmpty()) continue;

            // 2. Skip table-of-contents lines (dot-leaders)
            if (TOC_DOTS.matcher(line).find()) continue;

            // 3. Check for a speaker transition
            Matcher m = SPEAKER_LINE.matcher(line);
            if (m.matches()) {
                // ── Save the previous turn (if any) ──
                if (curTitle != null && curText.length() > 0) {
                    turnSeq++;
                    turns.add(new SpeakerTurn(
                        curTitle, curName, turnSeq,
                        curText.toString().trim(), curStart));
                }

                // ── Start a new turn ──
                curTitle = m.group(1);
                curName  = m.group(2);
                curStart = i;
                curText  = new StringBuilder();

                String rest = m.group(3).trim();
                if (!rest.isEmpty()) {
                    curText.append(rest);
                }
                continue;
            }

            // 4. Skip standalone annotation lines (not speech)
            if (ANNOTATION_LINE.matcher(line).matches()) continue;

            // 5. Continuation line — append to current turn
            if (curTitle != null) {
                if (curText.length() > 0) {
                    curText.append(" ");
                }
                curText.append(line.trim());
            }
            // (Lines before the first speaker are silently discarded —
            //  they are typically cover-page / TOC material.)
        }

        // ── Save the last turn ──
        if (curTitle != null && curText.length() > 0) {
            turnSeq++;
            turns.add(new SpeakerTurn(
                curTitle, curName, turnSeq,
                curText.toString().trim(), curStart));
        }

        return turns;
    }

    // ── Standalone runner ────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {

        // 1. Determine input
        String text;
        if (args.length > 0) {
            text = new String(Files.readAllBytes(Paths.get(args[0])));
        } else {
            text = getDefaultTestText();
        }

        // 2. Parse
        List<SpeakerTurn> turns = parse(text);

        // 3. Print per-turn summary
        System.out.println("=".repeat(70));
        System.out.println("  Speaker Turn Segmentation");
        System.out.println("=".repeat(70));
        System.out.println();

        for (SpeakerTurn turn : turns) {
            String preview = turn.getText();
            if (preview.length() > 70) {
                preview = preview.substring(0, 67) + "...";
            }
            System.out.printf("[Turn %3d] %-22s | %5d chars | %s%n",
                turn.getTurnNumber(),
                turn.getSpeakerLabel(),
                turn.getText().length(),
                preview);
        }

        // 4. Speaker statistics
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (SpeakerTurn turn : turns) {
            String label = turn.getSpeakerLabel();
            stats.computeIfAbsent(label, k -> new int[]{0, 0});
            stats.get(label)[0]++;                          // turn count
            stats.get(label)[1] += turn.getText().length();  // total chars
        }

        System.out.println();
        System.out.println("-".repeat(70));
        System.out.printf("  %-24s  %6s  %8s%n", "Speaker", "Turns", "Chars");
        System.out.println("-".repeat(70));

        stats.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(e -> System.out.printf("  %-24s  %6d  %8d%n",
                e.getKey(), e.getValue()[0], e.getValue()[1]));

        System.out.println("-".repeat(70));
        System.out.printf("  %-24s  %6d  %8d%n", "TOTAL",
            turns.size(),
            turns.stream().mapToInt(t -> t.getText().length()).sum());
        System.out.println("=".repeat(70));
    }

    // ── Built-in test data ───────────────────────────────────────────────

    /**
     * A small synthetic hearing excerpt for quick testing when no file
     * is provided.  Should produce 4 turns.
     */
    private static String getDefaultTestText() {
        return String.join("\n",
            "                        TABLE OF CONTENTS",
            "  Senator Coburn.................................................   686",
            "",
            "  STATEMENT OF HON. PATRICK LEAHY, A U.S. SENATOR FROM THE",
            "                       STATE OF VERMONT",
            "",
            "    Chairman Leahy. Good morning. We will now hear from Senator",
            "Sessions.",
            "    Senator Sessions. Thank you, Chairman Leahy. I want to welcome",
            "our nominees today. This is an important hearing.",
            "    [Laughter.]",
            "    Chairman Leahy. Thank you, Senator Sessions. We appreciate your",
            "remarks very much.",
            "    Mr. Kappos. Thank you, Chairman. It is an honor to appear before",
            "this Committee, and I look forward to answering your questions."
        );
    }
}
