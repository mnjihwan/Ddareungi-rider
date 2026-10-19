package com.ddareungi.station.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Search equivalences for the Korean two-set keyboard. Never changes the user's input. */
public final class KoreanSearch {
    private static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String[] LEADS = {"r","R","s","e","E","f","a","q","Q","t","T","d","w","W","c","z","x","v","g"};
    private static final String[] VOWELS = {"k","o","i","O","j","p","u","P","h","hk","ho","hl","y","n","nj","np","nl","b","m","ml","l"};
    private static final String[] TAILS = {"","r","R","rt","s","sw","sg","e","f","fr","fa","fq","ft","fx","fv","fg","a","q","qt","t","T","d","w","c","z","x","v","g"};
    private static final String JAMO = "ㄱㄲㄳㄴㄵㄶㄷㄸㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅃㅄㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String[] KEYS = {"r","R","rt","s","sw","sg","e","E","f","fr","fa","fq","ft","fx","fv","fg","a","q","Q","qt","t","T","d","w","W","c","z","x","v","g","k","o","i","O","j","p","u","P","h","hk","ho","hl","y","n","nj","np","nl","b","m","ml","l"};

    private KoreanSearch() {}

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static String keyboard(String value) {
        var result = new StringBuilder();
        for (char c : Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC).toCharArray()) {
            if (c >= '가' && c <= '힣') {
                int offset = c - '가';
                result.append(LEADS[offset / 588]).append(VOWELS[offset % 588 / 28]).append(TAILS[offset % 28]);
            } else {
                int index = JAMO.indexOf(c);
                result.append(index < 0 ? String.valueOf(c) : KEYS[index]);
            }
        }
        // Preserve shifted Korean keys: ㄱ/ㄲ and ㅐ/ㅒ are different letters.
        return result.toString().replaceAll("\\s+", "");
    }

    /** Lower is better; -1 means unrelated. Fuzzy matching needs at least two Hangul syllables. */
    public static int score(String value, String query) {
        String text = normalize(value), term = normalize(query);
        if (term.isEmpty() || text.contains(term)) return 0;
        if (term.length() < 2) return -1;
        if (term.chars().allMatch(c -> INITIALS.indexOf(c) >= 0)) {
            var initials = new StringBuilder();
            for (char c : text.toCharArray()) initials.append(c >= '가' && c <= '힣' ? INITIALS.charAt((c - '가') / 588) : c);
            return initials.toString().contains(term) ? 2 : -1;
        }
        String keys = keyboard(value), queryKeys = keyboard(query);
        if ((term.matches(".*[a-z].*") || text.matches(".*[a-z].*")) && keys.contains(queryKeys)) return 1;
        // Restrict fuzzy matching to Hangul text: numbers, punctuation and English names remain literal.
        if (!term.matches("[가-힣]{2,}")) return -1;
        int limit = term.length() >= 4 ? 2 : 1;
        int best = limit + 1;
        // Compare complete syllable windows so a missing final consonant is one edit,
        // but a fragment of an unrelated syllable cannot become an exact keyboard match.
        for (int start = 0; start < text.length(); start++) {
            for (int length = Math.max(2, term.length() - 1); length <= term.length() + 1 && start + length <= text.length(); length++) {
                String part = text.substring(start, start + length);
                if (!part.matches("[가-힣]+")) continue;
                String candidate = keyboard(part);
                if (Math.abs(candidate.length() - queryKeys.length()) <= limit)
                    best = Math.min(best, distance(candidate, queryKeys));
            }
        }
        return best <= limit ? 3 + best : -1;
    }

    /** All meaningful words may match across name and address, in either order. */
    public static int bestScore(String name, String address, String query) {
        int whole = fieldScore(name, address, query);
        if (whole == 0) return 0;
        String[] terms = query.trim().split("\\s+");
        // Single-character fragments retain the existing whitespace-insensitive phrase behavior.
        if (terms.length < 2 || java.util.Arrays.stream(terms).anyMatch(term -> normalize(term).length() < 2)) return whole;
        int combined = 0;
        for (String term : terms) {
            int part = fieldScore(name, address, term);
            if (part < 0) return whole;
            combined = Math.max(combined, part);
        }
        return whole < 0 ? combined : Math.min(whole, combined);
    }

    private static int fieldScore(String name, String address, String query) {
        int first = score(name, query), second = score(address, query);
        return first < 0 ? second : second < 0 ? first : Math.min(first, second);
    }

    public static boolean matches(String value, String query) { return score(value, query) >= 0; }

    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) for (int j = 1; j <= b.length(); j++) {
            d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
        }
        return d[a.length()][b.length()];
    }

    /** Converts only Latin runs which form complete two-set Hangul syllables; keeps genuine English intact. */
    public static String koreanKeyboardQuery(String query) {
        var matcher = java.util.regex.Pattern.compile("[A-Za-z]+").matcher(query);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(compose(match.group())));
    }

    private static String compose(String keys) {
        var result = new StringBuilder();
        int pos = 0;
        while (pos < keys.length()) {
            int lead = java.util.Arrays.asList(LEADS).indexOf(keys.substring(pos, pos + 1));
            if (lead < 0) return keys;
            pos++;
            int vowel = longest(VOWELS, keys, pos);
            if (vowel < 0) return keys;
            pos += VOWELS[vowel].length();
            int tail = 0;
            // Leave a consonant for the next syllable when followed by a vowel.
            for (int t = 1; t < TAILS.length; t++) {
                String ending = TAILS[t];
                int end = pos + ending.length();
                if (keys.startsWith(ending, pos) && longest(VOWELS, keys, end) < 0 && ending.length() > TAILS[tail].length()) tail = t;
            }
            pos += TAILS[tail].length();
            result.append((char) ('가' + lead * 588 + vowel * 28 + tail));
        }
        return result.toString();
    }

    private static int longest(String[] options, String keys, int pos) {
        int best = -1;
        for (int i = 0; i < options.length; i++)
            if (!options[i].isEmpty() && keys.startsWith(options[i], pos) && (best < 0 || options[i].length() > options[best].length())) best = i;
        return best;
    }
}
