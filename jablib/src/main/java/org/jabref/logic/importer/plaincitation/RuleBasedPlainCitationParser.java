package org.jabref.logic.importer.plaincitation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.identifier.DOI;
import org.jabref.model.entry.types.EntryType;
import org.jabref.model.entry.types.StandardEntryType;

/// Parse a plain citation using regex rules.
///
/// TODO: This class is similar to [org.jabref.logic.importer.fileformat.pdf.RuleBasedBibliographyPdfImporter], we need to unify them.
public class RuleBasedPlainCitationParser implements PlainCitationParser {
    private static final String AUTHOR_TAG = "[author_tag]";
    private static final String URL_TAG = "[url_tag]";
    private static final String YEAR_TAG = "[year_tag]";
    private static final String PAGES_TAG = "[pages_tag]";
    private static final String INITIALS_GROUP = "INITIALS";
    private static final String LASTNAME_GROUP = "LASTNAME";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)" +
                    "(([\\w\\-]+\\.)+?([\\w\\-.~]+\\/?)*" +
                    "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern YEAR_PATTERN = Pattern.compile(
            "\\d{4}",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern AUTHOR_PATTERN = Pattern.compile(
            "(?<" + LASTNAME_GROUP + ">\\p{Lu}\\w+),?\\s(?<" + INITIALS_GROUP + ">(\\p{Lu}\\.\\s){1,2})" +
                    "(?:\\s|\\band\\b|,|\\.)*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern AUTHOR_PATTERN_2 = Pattern.compile(
            "(?<" + INITIALS_GROUP + ">(\\p{Lu}\\.\\s){1,2})(?<" + LASTNAME_GROUP + ">\\p{Lu}\\w+)" +
                    "(?:\\s|\\band\\b|,|\\.)*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern PAGES_PATTERN = Pattern.compile(
            "(p.)?\\s?\\d+(-\\d+)?",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private List<String> urls;
    private List<String> authors;
    private String year;
    private String pages;
    private String title;
    private String doi;
    private boolean isArticle;
    private String journalOrPublisher;

    @Override
    public Optional<BibEntry> parsePlainCitation(String text) {
        urls = new ArrayList<>();
        authors = new ArrayList<>();
        year = "";
        pages = "";
        title = "";
        doi = "";
        isArticle = true;
        journalOrPublisher = "";

        // DOI is extracted first so a "doi.org" link is recorded as a DOI
        // rather than being swallowed by the generic URL rule.
        String inputWithoutDoi = findDoi(text);
        String inputWithoutUrls = findUrls(inputWithoutDoi);
        String inputWithoutAuthors = findAuthors(inputWithoutUrls);
        String inputWithoutYear = findYear(inputWithoutAuthors);
        String inputWithoutPages = findPages(inputWithoutYear);
        String nonParsed = findParts(inputWithoutPages);
        return generateEntity(nonParsed);
    }

    private Optional<BibEntry> generateEntity(String input) {
        EntryType type = isArticle ? StandardEntryType.Article : StandardEntryType.Book;
        BibEntry extractedEntity = new BibEntry(type);
        extractedEntity.setField(StandardField.AUTHOR, String.join(" and ", authors));
        extractedEntity.setField(StandardField.URL, String.join(", ", urls));
        extractedEntity.setField(StandardField.YEAR, year);
        extractedEntity.setField(StandardField.PAGES, pages);
        extractedEntity.setField(StandardField.TITLE, title);
        if (!doi.isEmpty()) {
            extractedEntity.setField(StandardField.DOI, doi);
        }
        if (isArticle) {
            extractedEntity.setField(StandardField.JOURNAL, journalOrPublisher);
        } else {
            extractedEntity.setField(StandardField.PUBLISHER, journalOrPublisher);
        }
        extractedEntity.setField(StandardField.COMMENT, input);
        return Optional.of(extractedEntity);
    }

    private String findUrls(String input) {
        Matcher matcher = URL_PATTERN.matcher(input);
        while (matcher.find()) {
            urls.add(input.substring(matcher.start(1), matcher.end()));
        }
        return fixSpaces(matcher.replaceAll(URL_TAG));
    }

    /// Extracts the first DOI found in `input`, stores its canonical form in [#doi],
    /// and returns `input` with the matched DOI (including any leading `doi:` or
    /// `https://doi.org/` prefix) removed so the remaining rules do not re-parse it as a
    /// URL or title.
    ///
    /// The DOI is replaced with an empty string rather than a placeholder token: [#findParts]
    /// later infers the title and journal/publisher from the count of non-numeric segments, and a
    /// placeholder would skew that count because DOIs typically contain digits while a token like
    /// `[doi_tag]` would not.
    ///
    /// Called before [#findUrls] so a `doi.org` link is recorded as a DOI rather
    /// than being swallowed by the generic URL rule.
    ///
    /// @param input raw citation text
    /// @return the input with the DOI stripped, or the original input if no DOI was found
    private String findDoi(String input) {
        return DOI.findInText(input)
                  .map(parsed -> {
                      doi = parsed.asString();
                      return fixSpaces(DOI.replaceInText(input, ""));
                  })
                  .orElse(input);
    }

    private String findYear(String input) {
        Matcher matcher = YEAR_PATTERN.matcher(input);
        while (matcher.find()) {
            String yearCandidate = input.substring(matcher.start(), matcher.end());
            int intYearCandidate = Integer.parseInt(yearCandidate);
            if ((intYearCandidate > 1700) && (intYearCandidate <= Calendar.getInstance().get(Calendar.YEAR))) {
                year = yearCandidate;
                return fixSpaces(input.replace(year, YEAR_TAG));
            }
        }
        return input;
    }

    private String findAuthors(String input) {
        int start = 0;
        while (start < input.length() && !Character.isLetter(input.charAt(start))) {
            start++; // skip things like "[1] " or "1. "
        }
        int pos = start;
        Matcher surnameFirst = AUTHOR_PATTERN.matcher(input);
        Matcher initialsFirst = AUTHOR_PATTERN_2.matcher(input);
        while (pos < input.length()) {
            surnameFirst.region(pos, input.length());
            initialsFirst.region(pos, input.length());
            if (surnameFirst.lookingAt()) {
                authors.add(generateAuthor(surnameFirst.group(LASTNAME_GROUP), surnameFirst.group(INITIALS_GROUP)));
                pos = surnameFirst.end();
            } else if (initialsFirst.lookingAt()) {
                authors.add(generateAuthor(initialsFirst.group(LASTNAME_GROUP), initialsFirst.group(INITIALS_GROUP)));
                pos = initialsFirst.end();
            } else {
                break;
            }
        }
        if (pos == start) {
            return input;
        }
        return fixSpaces(input.substring(0, start) + AUTHOR_TAG + " " + input.substring(pos));
    }

    private String generateAuthor(String lastName, String initials) {
        return lastName + ", " + initials.trim();
    }

    private String findPages(String input) {
        Matcher matcher = PAGES_PATTERN.matcher(input);
        if (matcher.find()) {
            pages = matcher.group().trim();
        }
        return fixSpaces(matcher.replaceFirst(" " + PAGES_TAG));
    }

    private String fixSpaces(String input) {
        return input.replaceAll("[,.!?;:]", "$0 ")
                    .replaceAll("\\p{Lt}", " $0")
                    .replaceAll("\\s+", " ").trim();
    }

    private boolean containsTag(String s) {
        return s.contains(YEAR_TAG) || s.contains(PAGES_TAG) || s.contains(URL_TAG);
    }

    private String stripTags(String s) {
        return s.replace(YEAR_TAG, "").replace(PAGES_TAG, "").replace(URL_TAG, "");
    }

    private String findParts(String input) {
        int afterAuthorsIndex = input.lastIndexOf(AUTHOR_TAG);
        if (afterAuthorsIndex == -1) {
            return input;
        }
        afterAuthorsIndex += AUTHOR_TAG.length();

        List<String> rawParts = new ArrayList<>();
        int delimiterIndex = input.lastIndexOf("//");
        if (delimiterIndex != -1) {
            rawParts.add(input.substring(afterAuthorsIndex, delimiterIndex));
            rawParts.addAll(Arrays.asList(input.substring(delimiterIndex + 2).split(",|\\.")));
        } else {
            rawParts.addAll(Arrays.asList(input.substring(afterAuthorsIndex).split(",|\\.")));
        }
        boolean hasDelimiter = delimiterIndex != -1;
        List<String> textParts = new ArrayList<>();
        for (int i = 0; i < rawParts.size(); i++) {
            String part = rawParts.get(i);
            boolean beforeDelimiter = hasDelimiter && i == 0;
            boolean hasTag = containsTag(part) && !beforeDelimiter;
            String cleaned = stripTags(part).trim();
            if (cleaned.isEmpty()) {
                if (hasTag) {
                    break;      // pure metadata segment like "[year_tag]"
                }
                continue;       // blank segment
            }
            if (containsDigit(cleaned)) {
                break;
            }
            textParts.add(cleaned);
            if (hasTag) {
                break;          // "Journal Name [pages_tag]" -> text, then metadata starts
            }
        }

        if (!textParts.isEmpty()) {
            title = textParts.get(0);
        }
        if (textParts.size() > 1) {
            journalOrPublisher = textParts.get(1);
        }
        if (textParts.size() > 2) {
            isArticle = false;
        }
        return fixSpaces(input);
    }

    /// Checks whether `input` contains at least one digit. Used instead of a
    /// `".*\\d.*"` regex on user-provided text: a direct scan is linear time and
    /// not susceptible to regex backtracking on adversarial input.
    private static boolean containsDigit(String input) {
        return input.codePoints().anyMatch(Character::isDigit);
    }
}
