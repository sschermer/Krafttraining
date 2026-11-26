package de.trainingsapp;

/** CSV intended for opening in spreadsheet software. XLSX retains exact text. */
public final class CsvEncoding {
    private CsvEncoding() {}

    public static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        if (value instanceof CharSequence && formulaLike(text)) {
            // Inside the quoted cell, a leading tab makes Excel treat this as text.
            // This intentionally changes only CSV's dangerous text values; the
            // original database, JSON and explicit-string XLSX cells stay intact.
            text = "\t" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static boolean formulaLike(String value) {
        int offset = 0;
        while (offset < value.length()) {
            int cp = value.codePointAt(offset);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || Character.getType(cp) == Character.FORMAT || Character.isISOControl(cp)) {
                offset += Character.charCount(cp);
            } else break;
        }
        if (offset == value.length()) return false;
        char first = value.charAt(offset);
        return first == '=' || first == '+' || first == '-' || first == '@'
            || first == '\uFF1D' || first == '\uFF0B' || first == '\uFF0D' || first == '\uFF20';
    }
}
