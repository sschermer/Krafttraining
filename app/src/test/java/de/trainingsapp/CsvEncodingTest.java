package de.trainingsapp;

import static org.junit.Assert.*;
import org.junit.Test;

public class CsvEncodingTest {
    @Test public void neutralizesFormulaPrefixesIncludingLeadingWhitespace() {
        for (String text : new String[]{"=1+1", "+SUM(A1:A2)", "-1+1", "@SUM(A1)",
            "  =1+1", "\r\n=1+1", "\t=1+1", "\uFEFF=1+1", "＝1+1", "＋1+1", "－1+1", "＠SUM(A1)"}) {
            assertTrue("Not neutralized: " + text, CsvEncoding.cell(text).startsWith("\"\t"));
        }
    }

    @Test public void preservesGenuineNumericValuesIncludingNegativeNumbers() {
        assertEquals("\"-2.5\"", CsvEncoding.cell(-2.5));
        assertEquals("\"30\"", CsvEncoding.cell(30));
        assertEquals("\"\"", CsvEncoding.cell(null));
    }

    @Test public void escapesDelimitersQuotesAndMultilineTextWithoutCreatingCells() {
        assertEquals("\"Notiz; \"\"gut\"\"\nzweite Zeile\"", CsvEncoding.cell("Notiz; \"gut\"\nzweite Zeile"));
        assertEquals("\"Rudern: 30 kg\"", CsvEncoding.cell("Rudern: 30 kg"));
        assertEquals("\"a=1+1\"", CsvEncoding.cell("a=1+1"));
    }
}
