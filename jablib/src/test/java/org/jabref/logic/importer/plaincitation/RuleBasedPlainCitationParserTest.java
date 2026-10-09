package org.jabref.logic.importer.plaincitation;

import java.util.Optional;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleBasedPlainCitationParserTest {
    private final RuleBasedPlainCitationParser parser = new RuleBasedPlainCitationParser();

    @Test
    void doesNotExtractAbbreviationSuffixesAsAuthors() {
        String citation = "Doe, J. Reliable Software. Softw. Belgique. Bull.Ann.R.Soc. Belg. Entomol. 2020, 12-20.";

        Optional<BibEntry> result = parser.parsePlainCitation(citation);

        assertEquals("Doe, J.", result.orElseThrow().getField(StandardField.AUTHOR).orElseThrow());
    }

    @Test
    void doesNotShiftTheTitleBoundaryPastTheVenueAbbreviations() {
        String citation = "Doe, J. Reliable Software. Softw. Belgique. Bull.Ann.R.Soc. Belg. Entomol. 2020, 12-20.";

        Optional<BibEntry> result = parser.parsePlainCitation(citation);

        assertEquals("Reliable Software", result.orElseThrow().getField(StandardField.TITLE).orElseThrow());
    }

    @Test
    void doesNotLeakPagesPlaceholderIntoJournal() {
        String citation = "Doe, J. \"Parser regression examples\". Journal Name 12-20 2020";

        Optional<BibEntry> result = parser.parsePlainCitation(citation);

        assertEquals("Journal Name", result.orElseThrow().getField(StandardField.JOURNAL).orElseThrow());
    }

    @Test
    void readsJournalAfterDoubleSlashDelimiter() {
        String citation = "Doe, J. Reliable Software 2015 // Journal Name, 12-20.";

        BibEntry entry = parser.parsePlainCitation(citation).orElseThrow();

        assertEquals(Optional.of("Reliable Software"), entry.getField(StandardField.TITLE));
        assertEquals(Optional.of("Journal Name"), entry.getField(StandardField.JOURNAL));
        assertEquals(Optional.of("2015"), entry.getField(StandardField.YEAR));
        assertEquals(Optional.of("12-20"), entry.getField(StandardField.PAGES));
        assertEquals(StandardEntryType.Article, entry.getType());
    }
}
