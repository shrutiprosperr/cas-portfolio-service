package com.prosperr.cas.parse;

/** ISO 6166 check digit. A code that fails is flagged, not rewritten. */
public final class IsinCheck {
    private IsinCheck() {}

    public static boolean isValid(String isin) {
        if (isin == null || !isin.matches("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")) {
            return false;
        }
        StringBuilder expanded = new StringBuilder();
        for (char ch : isin.toCharArray()) {
            expanded.append(Character.isLetter(ch) ? Integer.toString(ch - 55) : ch);
        }
        int sum = 0;
        String digits = expanded.toString();
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}
