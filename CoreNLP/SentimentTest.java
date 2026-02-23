import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;

import java.util.*;
import java.io.*;
import java.nio.file.*;

/**
 * SentimentTest.java
 *
 * Minimal smoke-test for Stanford CoreNLP sentiment analysis.
 * Processes a plain-text input sentence-by-sentence and prints:
 *   - the sentence text
 *   - the 5-class sentiment label (Very Negative to Very Positive)
 *   - a numeric score mapped to [-2, +2]
 *
 * Compile / Run: see run.ps1
 *
 * Usage:
 *   java SentimentTest                 # uses built-in sample text
 *   java SentimentTest myfile.txt      # processes a plain-text file
 */
public class SentimentTest {

    /** Maps CoreNLP's 0-4 ordinal to a readable label. */
    private static final String[] LABELS = {
        "Very Negative", "Negative", "Neutral", "Positive", "Very Positive"
    };

    /**
     * Maps CoreNLP's 0-4 ordinal to a [-2, +2] integer score.
     * Useful for averaging across a speaker's turn later.
     */
    private static int toScore(int classIndex) {
        return classIndex - 2;
    }

    public static void main(String[] args) throws IOException {

        // 1. Determine input text
        String text;
        if (args.length > 0) {
            text = new String(Files.readAllBytes(Paths.get(args[0])));
        } else {
            text = readDefaultInput();
        }

        System.out.println("=".repeat(70));
        System.out.println("  Stanford CoreNLP Sentiment Analysis");
        System.out.println("=".repeat(70));
        System.out.println();

        // 2. Build the CoreNLP pipeline
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize,ssplit,parse,sentiment");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        // 3. Annotate
        Annotation annotation = new Annotation(text);
        pipeline.annotate(annotation);

        // 4. Iterate sentences
        List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);

        int sentNum = 1;
        int totalScore = 0;

        for (CoreMap sentence : sentences) {
            Tree tree = sentence.get(SentimentCoreAnnotations.SentimentAnnotatedTree.class);
            int classIdx = RNNCoreAnnotations.getPredictedClass(tree);
            String label  = LABELS[classIdx];
            int score     = toScore(classIdx);
            totalScore   += score;

            System.out.printf("[%2d] Score: %+d  Label: %-14s | %s%n",
                sentNum, score, label,
                sentence.get(CoreAnnotations.TextAnnotation.class));
            sentNum++;
        }

        // 5. Summary
        int n = sentences.size();
        System.out.println();
        System.out.println("-".repeat(70));
        System.out.printf("  Sentences: %d   |   Total score: %+d   |   Avg score: %.2f%n",
            n, totalScore, (n > 0 ? (double) totalScore / n : 0.0));
        System.out.println("=".repeat(70));
    }

    /** Built-in sample text used when no file argument is provided. */
    private static String readDefaultInput() {
        return "The witness provided clear and compelling testimony. "
             + "The senator was evasive and unhelpful throughout questioning. "
             + "Several important points were raised regarding public safety. "
             + "The committee expressed disappointment with the agency response. "
             + "Overall, the hearing addressed some critical concerns effectively.";
    }
}