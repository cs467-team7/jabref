package org.jabref.logic.importer.plaincitation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Measures the accuracy of {@link RuleBasedPlainCitationParser} on AnyStyle's
 * hand-curated gold data set (res/parser/gold.xml from
 * https://github.com/inukshuk/anystyle, BSD licence).
 *
 * This is a measurement, not a normal unit test: it runs the parser over every
 * reference in gold.xml and prints per-field accuracy. It only runs when the
 * environment variable RUN_GOLD=true is set, so the normal build stays fast.
 *
 * Failing references are written to build/gold-failures.tsv for inspection.
 */
@EnabledIfEnvironmentVariable(named = "RUN_GOLD", matches = "true")
class RuleBasedPlainCitationParserGoldAccuracyTest {

    private static final String GOLD_RESOURCE = "anystyle/gold.xml";
    private static final Path FAILURE_REPORT = Path.of("build", "gold-failures.tsv");

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(1[5-9]|20)\\d{2}(?!\\d)");

    /** One reference from gold.xml: the plain input string plus the expected values taken from its tags. */
    record GoldCitation(int index, String input, String title, String year, String firstAuthor,
                        String journal, String volume, String pages) {
    }

    @Test
    void measureAccuracyOnGold() throws Exception {
        List<GoldCitation> gold = loadGold();
        RuleBasedPlainCitationParser parser = new RuleBasedPlainCitationParser();

        int withTitle = 0, titleExact = 0, titleLoose = 0;
        int withYear = 0, yearOk = 0;
        int withAuthor = 0, authorOk = 0;
        int withJournal = 0, journalOk = 0;
        int withVolume = 0, volumeOk = 0;
        int withPages = 0, pagesOk = 0;
        int noEntry = 0, crashed = 0;

        List<String> failures = new ArrayList<>();
        failures.add("index\tfield\texpected\tactual\tinput");

        for (GoldCitation g : gold) {
            BibEntry entry;
            try {
                Optional<BibEntry> result = parser.parsePlainCitation(g.input());
                if (result.isEmpty()) {
                    noEntry++;
                }
                entry = result.orElse(new BibEntry());
            } catch (Exception e) {
                crashed++;
                failures.add(row(g, "EXCEPTION", "", e.getClass().getSimpleName(), g.input()));
                continue;
            }

            // Title: only counted for references that actually have a title in gold
            if (g.title() != null) {
                withTitle++;
                String actual = clean(entry.getField(StandardField.TITLE).orElse(""));
                if (actual.equals(g.title())) {
                    titleExact++;
                    titleLoose++;
                } else {
                    if (!actual.isEmpty() && (actual.contains(g.title()) || g.title().contains(actual))) {
                        titleLoose++;
                    }
                    failures.add(row(g, "title", g.title(), actual, g.input()));
                }
            }

            // Year
            if (g.year() != null) {
                withYear++;
                String actual = entry.getField(StandardField.YEAR).orElse("");
                if (actual.equals(g.year())) {
                    yearOk++;
                } else {
                    failures.add(row(g, "year", g.year(), actual, g.input()));
                }
            }

            // Author: parsed author field must contain the first author's surname
            if (g.firstAuthor() != null) {
                withAuthor++;
                String actual = entry.getField(StandardField.AUTHOR).orElse("");
                if (actual.contains(g.firstAuthor())) {
                    authorOk++;
                } else {
                    failures.add(row(g, "author", g.firstAuthor(), actual, g.input()));
                }
            }

            // Journal: only references tagged <journal> in gold (journal articles)
            if (g.journal() != null) {
                withJournal++;
                String actual = clean(entry.getField(StandardField.JOURNAL).orElse(""));
                if (actual.equals(g.journal())) {
                    journalOk++;
                } else {
                    failures.add(row(g, "journal", g.journal(), actual, g.input()));
                }
            }

            // Volume: compare just the number, e.g. "39," -> 39 and "1(1)," -> 1
            if (g.volume() != null) {
                withVolume++;
                String actual = normalizeVolume(entry.getField(StandardField.VOLUME).orElse(""));
                if (actual.equals(g.volume())) {
                    volumeOk++;
                } else {
                    failures.add(row(g, "volume", g.volume(), actual, g.input()));
                }
            }

            // Pages: "pp. 683–687." and "683--687" both become 683--687 before comparing
            if (g.pages() != null) {
                withPages++;
                String actual = normalizePages(entry.getField(StandardField.PAGES).orElse(""));
                if (actual.equals(g.pages())) {
                    pagesOk++;
                } else {
                    failures.add(row(g, "pages", g.pages(), actual, g.input()));
                }
            }
        }

        Files.createDirectories(FAILURE_REPORT.getParent());
        Files.write(FAILURE_REPORT, failures);

        System.out.printf("""
                ===== AnyStyle gold.xml accuracy: RuleBasedPlainCitationParser =====
                references        : %d
                title (exact)     : %5.1f%%  (%d / %d)
                title (loose)     : %5.1f%%  (%d / %d)
                year              : %5.1f%%  (%d / %d)
                first author      : %5.1f%%  (%d / %d)
                journal           : %5.1f%%  (%d / %d)
                volume            : %5.1f%%  (%d / %d)
                pages             : %5.1f%%  (%d / %d)
                no entry returned : %d
                exceptions        : %d
                failures written to %s
                ====================================================================
                """,
                gold.size(),
                pct(titleExact, withTitle), titleExact, withTitle,
                pct(titleLoose, withTitle), titleLoose, withTitle,
                pct(yearOk, withYear), yearOk, withYear,
                pct(authorOk, withAuthor), authorOk, withAuthor,
                pct(journalOk, withJournal), journalOk, withJournal,
                pct(volumeOk, withVolume), volumeOk, withVolume,
                pct(pagesOk, withPages), pagesOk, withPages,
                noEntry, crashed, FAILURE_REPORT.toAbsolutePath());

        // Optional ratchet: once you have a baseline, uncomment and set the numbers
        // so that future changes cannot silently make the parser worse.
        // assertTrue(pct(titleExact, withTitle) >= 40.0, "title accuracy regressed");
        // assertTrue(pct(yearOk, withYear) >= 70.0, "year accuracy regressed");
    }

    // ---------------------------------------------------------------- loading

    private List<GoldCitation> loadGold() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(GOLD_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("gold.xml not found on classpath at "
                        + getClass().getPackageName().replace('.', '/') + "/" + GOLD_RESOURCE);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);

            NodeList sequences = doc.getElementsByTagName("sequence");
            List<GoldCitation> result = new ArrayList<>(sequences.getLength());
            for (int i = 0; i < sequences.getLength(); i++) {
                result.add(toCitation(i, (Element) sequences.item(i)));
            }
            return result;
        }
    }

    private static GoldCitation toCitation(int index, Element sequence) {
        List<String> parts = new ArrayList<>();
        String title = null, year = null, author = null;
        String journal = null, volume = null, pages = null;

        NodeList children = sequence.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
            Node node = children.item(j);
            if (!(node instanceof Element tag)) {
                continue;
            }
            String text = tag.getTextContent().trim().replaceAll("\\s+", " ");
            if (text.isEmpty()) {
                continue;
            }
            parts.add(text); // INPUT: all tag contents in order

            switch (tag.getTagName()) { // EXPECTED: taken from the tag names
                case "title" -> title = (title == null) ? clean(text) : title;
                case "date" -> year = (year == null) ? extractYear(text) : year;
                case "author" -> author = (author == null) ? firstSurname(text) : author;
                case "journal" -> journal = (journal == null) ? clean(text) : journal;
                case "volume" -> volume = (volume == null) ? normalizeVolume(text) : volume;
                case "pages" -> pages = (pages == null) ? normalizePages(text) : pages;
                default -> {
                }
            }
        }
        return new GoldCitation(index, String.join(" ", parts), emptyToNull(title), year, emptyToNull(author),
                emptyToNull(journal), emptyToNull(volume), emptyToNull(pages));
    }

    // ---------------------------------------------------------------- helpers

    /** Strips surrounding quotes, brackets and trailing punctuation that gold keeps, e.g. "Title," -> Title */
    private static String clean(String s) {
        return s.replaceAll("^[\\s\"“”'‘’(\\[]+|[\\s\"“”'‘’.,:;)\\]]+$", "");
    }

    private static String extractYear(String s) {
        Matcher m = YEAR.matcher(s);
        return m.find() ? m.group() : null;
    }

    /**
     * Gold keeps names as written. Handles both styles:
     * "W. H. Enright."               -> Enright   (initials first: surname is the last word)
     * "Gmytrasiewicz, P. J., ..."    -> Gmytrasiewicz (surname first)
     */
    private static String firstSurname(String authors) {
        String first = authors.split(",|\\band\\b|&|;")[0].trim();
        if (authors.trim().matches("^[^\\s,]+,.*") && !first.matches(".*\\s.*")) {
            // "Surname, Initials" style
            return first.replaceAll("[.]$", "");
        }
        String[] tokens = first.replaceAll("[.,]$", "").split("\\s+");
        return tokens[tokens.length - 1].replaceAll("[.,]$", "");
    }

    /**
     * Keeps only the volume number: "39," -> 39, "1(1)," -> 1, "Vol. 12" -> 12.
     * Non-numeric volumes (e.g. Roman numerals) are just cleaned.
     */
    private static String normalizeVolume(String s) {
        Matcher m = Pattern.compile("\\d+").matcher(s);
        return m.find() ? m.group() : clean(s);
    }

    /**
     * Puts page ranges into one form so gold and JabRef can be compared:
     * "pp. 683–687." -> 683--687, "pages 393-397," -> 393--397, "683--687" -> 683--687
     */
    private static String normalizePages(String s) {
        return s.trim()
                .replaceAll("(?i)^(pages?|pp?\\.?)\\s*", "")
                .replaceAll("\\s*[-‐‑‒–—]+\\s*", "--")
                .replaceAll("[\\s.,;:]+$", "");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static double pct(int ok, int total) {
        return total == 0 ? 0.0 : 100.0 * ok / total;
    }

    private static String row(GoldCitation g, String field, String expected, String actual, String input) {
        return g.index() + "\t" + field + "\t" + tsv(expected) + "\t" + tsv(actual) + "\t" + tsv(input);
    }

    private static String tsv(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }
}