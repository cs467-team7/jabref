package org.jabref.logic.importer.plaincitation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.FieldFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Measures the accuracy of [RuleBasedPlainCitationParser] on a 300-citation sample of the `ref_benchmark_clean`
/// config of [RenoBench Clean](https://huggingface.co/datasets/cometadata/renobench-clean) (Apache-2.0),
/// the quality-checked subset of RenoBench.
///
/// The sample holds journal articles and whole books only, the two entry types the parser produces, stratified
/// by language (en, pt, es, fr, de, it). A citation was kept only if its JATS fields also appear in its plain text.
///
/// Each line of `answer-key.jsonl` holds one raw citation (`plaintext`) and the BibTeX fields it should
/// give (`fields`), already cleaned into the form JabRef stores them in. The test only compares: every
/// expected field must equal the parsed field exactly. Fields missing from the answer key are not scored.
///
/// This is a measurement, not a normal unit test: it prints per-field accuracy and writes every
/// mismatch to `build/renobench-failures.tsv`. It only runs when the environment variable
/// `RUN_RENOBENCH=true` is set, so the normal build stays fast.
@NullMarked
@EnabledIfEnvironmentVariable(named = "RUN_RENOBENCH", matches = "true")
class RuleBasedPlainCitationParserRenoBenchAccuracyTest {

    private static final String ANSWER_KEY_RESOURCE = "renobench/answer-key.jsonl";
    private static final Path FAILURE_REPORT = Path.of("build", "renobench-failures.tsv");
    private static final String ENTRY_TYPE = "entrytype";

    /// One line of the answer key.
    record ExpectedCitation(String id, String language, String publicationType, String input,
                            String entryType, Map<String, String> fields) {
    }

    /// How many citations have a field in the answer key and how many of those the parser got right.
    private static final class FieldScore {
        private int total;
        private int correct;

        void add(boolean isCorrect) {
            total++;
            if (isCorrect) {
                correct++;
            }
        }

        double percentage() {
            return total == 0 ? 0.0 : 100.0 * correct / total;
        }
    }

    @Test
    void measureAccuracyOnRenoBench() throws IOException {
        List<ExpectedCitation> citations = loadAnswerKey();
        RuleBasedPlainCitationParser parser = new RuleBasedPlainCitationParser();

        Map<String, FieldScore> scores = new TreeMap<>();
        FieldScore allFields = new FieldScore();
        int noEntry = 0;
        int crashed = 0;

        List<String> failures = new ArrayList<>();
        failures.add("id\tlang\ttype\tfield\texpected\tactual\tinput");

        for (ExpectedCitation citation : citations) {
            Optional<BibEntry> result;
            // The benchmark counts parser crashes instead of aborting, so every runtime failure is caught here.
            try {
                result = parser.parsePlainCitation(citation.input());
            } catch (RuntimeException e) {
                crashed++;
                failures.add(row(citation, "EXCEPTION", "", e.getClass().getSimpleName()));
                continue;
            }
            if (result.isEmpty()) {
                noEntry++;
            }
            BibEntry entry = result.orElseGet(BibEntry::new);

            String actualType = entry.getType().getName().toLowerCase(Locale.ROOT);
            score(scores, allFields, failures, citation, ENTRY_TYPE, citation.entryType(), actualType);
            citation.fields().forEach((field, expected) -> {
                String actual = entry.getField(FieldFactory.parseField(field)).orElse("");
                score(scores, allFields, failures, citation, field, expected, actual);
            });
        }

        Files.createDirectories(FAILURE_REPORT.getParent());
        Files.write(FAILURE_REPORT, failures);

        StringBuilder report = new StringBuilder("===== RenoBench accuracy: RuleBasedPlainCitationParser =====\n");
        report.append("citations         : %d%n".formatted(citations.size()));
        scores.forEach((field, score) -> report.append(line(field, score)));
        report.append(line("ALL FIELDS", allFields))
              .append("no entry returned : %d%n".formatted(noEntry))
              .append("exceptions        : %d%n".formatted(crashed))
              .append("failures written to %s%n".formatted(FAILURE_REPORT.toAbsolutePath()))
              .append("============================================================");
        System.out.println(report);
    }

    private static void score(Map<String, FieldScore> scores, FieldScore allFields, List<String> failures,
                              ExpectedCitation citation, String field, String expected, String actual) {
        boolean correct = expected.equals(actual);
        scores.computeIfAbsent(field, key -> new FieldScore()).add(correct);
        allFields.add(correct);
        if (!correct) {
            failures.add(row(citation, field, expected, actual));
        }
    }

    private List<ExpectedCitation> loadAnswerKey() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(ANSWER_KEY_RESOURCE)) {
            assertNotNull(in, "RenoBench answer key not found on classpath at " + ANSWER_KEY_RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines()
                         .filter(line -> !line.isBlank())
                         .map(RuleBasedPlainCitationParserRenoBenchAccuracyTest::toCitation)
                         .toList();
        }
    }

    private static ExpectedCitation toCitation(String jsonLine) {
        JsonObject json = JsonParser.parseString(jsonLine).getAsJsonObject();
        Map<String, String> fields = new TreeMap<>();
        json.getAsJsonObject("fields").entrySet()
            .forEach(field -> fields.put(field.getKey(), field.getValue().getAsString()));
        return new ExpectedCitation(
                json.get("id").getAsString(),
                json.get("lang").getAsString(),
                json.get("pub_type").getAsString(),
                json.get("plaintext").getAsString(),
                json.get(ENTRY_TYPE).getAsString(),
                fields);
    }

    private static String line(String field, FieldScore score) {
        return "%-18s: %5.1f%%  (%d / %d)%n".formatted(field, score.percentage(), score.correct, score.total);
    }

    private static String row(ExpectedCitation citation, String field, String expected, String actual) {
        return String.join("\t", citation.id(), citation.language(), citation.publicationType(),
                field, tsv(expected), tsv(actual), tsv(citation.input()));
    }

    private static String tsv(String text) {
        return text.replace('\t', ' ').replace('\n', ' ');
    }
}
