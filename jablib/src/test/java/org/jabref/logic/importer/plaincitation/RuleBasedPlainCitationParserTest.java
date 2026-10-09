package org.jabref.logic.importer.plaincitation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.jabref.logic.importer.FetcherException;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.StandardField;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for [RuleBasedPlainCitationParser], see [#12893](https://github.com/JabRef/jabref/issues/12893).
///
/// The citations are taken from the issue and from the benchmark of [AnyStyle](https://github.com/inukshuk/anystyle/blob/main/spec/benchmark.rb).
class RuleBasedPlainCitationParserTest {

    // Citations from the issue

    private static final String TOTH = "Toth, C., & Barber, J. (1993). Lights, bats, and buildings: investigating the factors influencing roosting sites and habitat use by bats in Grand Teton National Park. The UW National Parks Service Research Station Annual Reports, 41, 90–97. https://doi.org/10.13001/uwnpsrc.2018.5659";
    private static final String SHAPIRO = "Shapiro, M., & Horwitz, S. (1997). Fast and accurate flow-insensitive points-to analysis. Proceedings of the 24th Annual ACM Symposium on Principles of Programming Languages, 12(1), 15-28.";
    private static final String ENRIGHT = "Enright, W. H. (1978). Improving the efficiency of matrix operations in the numerical solution of stiff ordinary differential equations. ACM Trans. Math. Softw., 4(2), 127-136.";

    // Citations from the AnyStyle benchmark (without the XML markup)

    private static final String CAU = "A. Cau, R. Kuiper, and W.-P. de Roever. Formalising Dijkstra's development strategy within Stark's formalism. In C. B. Jones, R. C. Shaw, and T. Denvir, editors, Proc. 5th. BCS-FACS Refinement Workshop, 1992.";
    private static final String KITSUREGAWA = "M. Kitsuregawa, H. Tanaka, and T. Moto-oka. Application of hash to data base machine and its architecture. New Generation Computing, 1(1), 1983.";
    private static final String VRCHOTICKY = "Alexander Vrchoticky. Modula/R language definition. Technical Report TU Wien rr-02-92, version 2.0, Dept. for Real-Time Systems, Technical University of Vienna, May 1993.";
    private static final String SHAPIRO_FULL_NAMES = "Marc Shapiro and Susan Horwitz. Fast and accurate flow-insensitive points-to analysis. In Proceedings of the 24th Annual ACM Symposium on Principles of Programming Languages, January 1997.";
    private static final String LANDI = "W. Landi and B. G. Ryder. Aliasing with and without pointers: A problem taxonomy. Center for Computer Aids for Industrial Productivity Technical Report CAIP-TR-125, Rutgers University, September 1990.";
    private static final String ENRIGHT_ANYSTYLE = "W. H. Enright. Improving the efficiency of matrix operations in the numerical solution of stiff ordinary differential equations. ACM Trans. Math. Softw., 4(2), 127-136, June 1978.";
    private static final String GMYTRASIEWICZ = "Gmytrasiewicz, P. J., Durfee, E. H., & Wehe, D. K. (1991a). A decision theoretic approach to coordinating multiagent interaction. In Proceedings of the Twelfth International Joint Conference on Artificial Intelligence, pp. 62-68 Sydney, Australia.";
    private static final String BOOKSTEIN = "A. Bookstein and S. T. Klein, Detecting content-bearing words by serial clustering, Proceedings of the Nineteenth Annual International ACM SIGIR Conference on Research and Development in Information Retrieval, pp. 319327, 1995.";
    private static final String DAYAL = "U. Dayal, H. Garcia-Molina, M. Hsu, B. Kao, and M.- C. Shan. Third generation TP monitors: A database challenge. In ACM SIGMOD Conference on Management of Data, pages 393-397, Washington, D. C., May 1993.";
    private static final String QIAO = "C. Qiao and R. Melhem, \"Reducing Communication Latency with Path Multiplexing in Optically Interconnected Multiprocessor Systems\", Proc. of HPCA-1, 1995.";

    // Own regression case: an uppercase initial-like fragment in the title ("Park. The") must not become an author

    private static final String DOE = "Doe, J. (2001). Notes on Park. The Archive. Journal of Things, 3, 1-2.";

    // Own regression case: a title that looks like a name ("Machine Learning Basics.") must not become an author
    // as long as the real authors are found

    private static final String DOE_ROE = "Doe, J. and Roe, K. (2000). Machine Learning Basics. Springer Press, 2000.";

    // Own case: full first names, three authors, with middle initial and name particle

    private static final String THREE_FULL_NAMES = "Marc J. Shapiro, Susan Horwitz, and Maarten de Roever. A Study of Things. Journal of Things, 3(2), 10-20, 2001.";

    private final RuleBasedPlainCitationParser parser = new RuleBasedPlainCitationParser();

    static Stream<Arguments> citationsFromTheIssue() {
        return Stream.of(
                Arguments.of("Toth & Barber (APA, with DOI and en dash)", TOTH, Map.of(
                        StandardField.AUTHOR, "Toth, C. and Barber, J.",
                        StandardField.TITLE, "Lights, bats, and buildings: investigating the factors influencing roosting sites and habitat use by bats in Grand Teton National Park",
                        StandardField.YEAR, "1993",
                        StandardField.JOURNAL, "The UW National Parks Service Research Station Annual Reports",
                        StandardField.PAGES, "90--97",
                        StandardField.DOI, "10.13001/uwnpsrc.2018.5659")),
                Arguments.of("Shapiro & Horwitz (APA, proceedings)", SHAPIRO, Map.of(
                        StandardField.AUTHOR, "Shapiro, M. and Horwitz, S.",
                        StandardField.TITLE, "Fast and accurate flow-insensitive points-to analysis",
                        StandardField.YEAR, "1997",
                        StandardField.JOURNAL, "Proceedings of the 24th Annual ACM Symposium on Principles of Programming Languages",
                        StandardField.PAGES, "15--28")),
                Arguments.of("Enright (APA, journal with abbreviations)", ENRIGHT, Map.of(
                        StandardField.AUTHOR, "Enright, W. H.",
                        StandardField.TITLE, "Improving the efficiency of matrix operations in the numerical solution of stiff ordinary differential equations",
                        StandardField.YEAR, "1978",
                        StandardField.JOURNAL, "ACM Trans. Math. Softw.",
                        StandardField.PAGES, "127--136")));
    }

    static Stream<Arguments> citationsFromAnyStyle() {
        return Stream.of(
                Arguments.of("Cau et al. (initials first, name particle, \"In\" venue)", CAU, Map.of(
                        StandardField.AUTHOR, "Cau, A. and Kuiper, R. and de Roever, W.-P.",
                        StandardField.TITLE, "Formalising Dijkstra's development strategy within Stark's formalism",
                        StandardField.YEAR, "1992")),
                Arguments.of("Kitsuregawa et al. (hyphenated family name)", KITSUREGAWA, Map.of(
                        StandardField.AUTHOR, "Kitsuregawa, M. and Tanaka, H. and Moto-oka, T.",
                        StandardField.TITLE, "Application of hash to data base machine and its architecture",
                        StandardField.YEAR, "1983",
                        StandardField.JOURNAL, "New Generation Computing")),
                Arguments.of("Landi & Ryder (technical report)", LANDI, Map.of(
                        StandardField.AUTHOR, "Landi, W. and Ryder, B. G.",
                        StandardField.TITLE, "Aliasing with and without pointers: A problem taxonomy",
                        StandardField.YEAR, "1990")),
                Arguments.of("Enright (initials first, year at the end)", ENRIGHT_ANYSTYLE, Map.of(
                        StandardField.AUTHOR, "Enright, W. H.",
                        StandardField.TITLE, "Improving the efficiency of matrix operations in the numerical solution of stiff ordinary differential equations",
                        StandardField.YEAR, "1978",
                        StandardField.JOURNAL, "ACM Trans. Math. Softw.",
                        StandardField.PAGES, "127--136")),
                Arguments.of("Gmytrasiewicz et al. (year with letter, pp.)", GMYTRASIEWICZ, Map.of(
                        StandardField.AUTHOR, "Gmytrasiewicz, P. J. and Durfee, E. H. and Wehe, D. K.",
                        StandardField.TITLE, "A decision theoretic approach to coordinating multiagent interaction",
                        StandardField.YEAR, "1991",
                        StandardField.JOURNAL, "Proceedings of the Twelfth International Joint Conference on Artificial Intelligence",
                        StandardField.PAGES, "62--68")),
                Arguments.of("Bookstein & Klein (no sentence end, single page number)", BOOKSTEIN, Map.of(
                        StandardField.AUTHOR, "Bookstein, A. and Klein, S. T.",
                        StandardField.TITLE, "Detecting content-bearing words by serial clustering",
                        StandardField.YEAR, "1995",
                        StandardField.JOURNAL, "Proceedings of the Nineteenth Annual International ACM SIGIR Conference on Research and Development in Information Retrieval")),
                Arguments.of("Dayal et al. (hyphenated initials, pages)", DAYAL, Map.of(
                        StandardField.AUTHOR, "Dayal, U. and Garcia-Molina, H. and Hsu, M. and Kao, B. and Shan, M.-C.",
                        StandardField.TITLE, "Third generation TP monitors: A database challenge",
                        StandardField.YEAR, "1993",
                        StandardField.JOURNAL, "ACM SIGMOD Conference on Management of Data",
                        StandardField.PAGES, "393--397")),
                Arguments.of("Qiao & Melhem (quoted title)", QIAO, Map.of(
                        StandardField.AUTHOR, "Qiao, C. and Melhem, R.",
                        StandardField.TITLE, "Reducing Communication Latency with Path Multiplexing in Optically Interconnected Multiprocessor Systems",
                        StandardField.YEAR, "1995",
                        StandardField.JOURNAL, "Proc. of HPCA-1")));
    }

    static Stream<Arguments> authorsAreOnlyTakenFromTheAuthorBlock() {
        return Stream.of(
                Arguments.of("Fragment of the title looks like \"initial. Lastname\"", DOE, Map.of(
                        StandardField.AUTHOR, "Doe, J.",
                        StandardField.TITLE, "Notes on Park",
                        StandardField.YEAR, "2001",
                        StandardField.PAGES, "1--2")),
                Arguments.of("Title that looks like a name (\"Machine Learning Basics.\")", DOE_ROE, Map.of(
                        StandardField.AUTHOR, "Doe, J. and Roe, K.",
                        StandardField.TITLE, "Machine Learning Basics",
                        StandardField.YEAR, "2000")));
    }

    /// Citations where the author names are given with the full first name
    static Stream<Arguments> citationsWithFullFirstNames() {
        return Stream.of(
                Arguments.of("Vrchoticky (single author, full first name)", VRCHOTICKY, Map.of(
                        StandardField.AUTHOR, "Vrchoticky, Alexander",
                        StandardField.TITLE, "Modula/R language definition",
                        StandardField.YEAR, "1993")),
                Arguments.of("Shapiro & Horwitz (full first names)", SHAPIRO_FULL_NAMES, Map.of(
                        StandardField.AUTHOR, "Shapiro, Marc and Horwitz, Susan",
                        StandardField.TITLE, "Fast and accurate flow-insensitive points-to analysis",
                        StandardField.YEAR, "1997")),
                Arguments.of("Three authors (middle initial, name particle, \"and\" after the comma)", THREE_FULL_NAMES, Map.of(
                        StandardField.AUTHOR, "Shapiro, Marc J. and Horwitz, Susan and de Roever, Maarten",
                        StandardField.TITLE, "A Study of Things",
                        StandardField.YEAR, "2001",
                        StandardField.JOURNAL, "Journal of Things",
                        StandardField.PAGES, "10--20")));
    }

    static Stream<Arguments> allCitations() {
        return Stream.of(TOTH, SHAPIRO, ENRIGHT, CAU, KITSUREGAWA, VRCHOTICKY, SHAPIRO_FULL_NAMES, LANDI, ENRIGHT_ANYSTYLE, GMYTRASIEWICZ, BOOKSTEIN, DAYAL, QIAO, DOE, DOE_ROE, THREE_FULL_NAMES)
                     .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("citationsFromTheIssue")
    void parsesCitationsFromTheIssue(String description, String citation, Map<Field, String> expectedFields) {
        assertFields(description, citation, expectedFields);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("citationsFromAnyStyle")
    void parsesCitationsFromAnyStyleBenchmark(String description, String citation, Map<Field, String> expectedFields) {
        assertFields(description, citation, expectedFields);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorsAreOnlyTakenFromTheAuthorBlock")
    void authorsAreOnlyTakenFromTheAuthorBlock(String description, String citation, Map<Field, String> expectedFields) {
        assertFields(description, citation, expectedFields);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("citationsWithFullFirstNames")
    void parsesCitationsWithFullFirstNames(String description, String citation, Map<Field, String> expectedFields) {
        assertFields(description, citation, expectedFields);
    }

    @ParameterizedTest
    @MethodSource("allCitations")
    void noPlaceholderTokenEndsUpInAnyField(String citation) {
        BibEntry entry = parser.parsePlainCitation(citation).orElseThrow();
        for (Field field : entry.getFields()) {
            String value = entry.getField(field).orElse("");
            assertFalse(value.contains("_tag]"), "Field " + field.getName() + " contains a placeholder token: " + value);
        }
    }

    @Test
    void commentContainsTheOriginalCitation() {
        BibEntry entry = parser.parsePlainCitation(TOTH).orElseThrow();
        assertEquals(Optional.of(TOTH), entry.getField(StandardField.COMMENT));
    }

    @Test
    void severalCitationsDoNotInfluenceEachOther() throws FetcherException {
        List<BibEntry> entries = parser.parseMultiplePlainCitations(TOTH + "\n\n" + SHAPIRO + "\n\n" + ENRIGHT);

        assertEquals(
                List.of("Toth, C. and Barber, J.", "Shapiro, M. and Horwitz, S.", "Enright, W. H."),
                entries.stream().map(entry -> entry.getField(StandardField.AUTHOR).orElse("")).toList());
    }

    private void assertFields(String description, String citation, Map<Field, String> expectedFields) {
        Optional<BibEntry> entry = parser.parsePlainCitation(citation);
        assertTrue(entry.isPresent(), "No entry for: " + description);
        expectedFields.forEach((field, expectedValue) ->
                assertEquals(
                        Optional.of(expectedValue),
                        entry.get().getField(field),
                        "Field " + field.getName() + " of \"" + description + "\""));
    }
}