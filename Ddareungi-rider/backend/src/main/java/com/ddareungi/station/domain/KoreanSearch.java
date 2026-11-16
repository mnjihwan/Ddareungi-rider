package com.ddareungi.station.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search equivalences for the Korean two-set keyboard. Never changes the user's input.
 *
 * <p>The {@code String} methods are the reference API. {@link #prepare(String)} and {@link #prepareQuery(String)}
 * compute the per-value work (normalised text, keyboard keys, initials, syllable keys) once so a catalog can rank
 * thousands of stations per keystroke; {@link #score(Prepared, Query)} and
 * {@link #bestScore(Prepared, Prepared, Query)} return exactly what the {@code String} overloads return (the
 * {@code String} overloads delegate to them).
 */
public final class KoreanSearch {
    private static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String[] LEADS = {"r","R","s","e","E","f","a","q","Q","t","T","d","w","W","c","z","x","v","g"};
    private static final List<String> LEAD_LIST = List.of(LEADS);
    private static final String[] VOWELS = {"k","o","i","O","j","p","u","P","h","hk","ho","hl","y","n","nj","np","nl","b","m","ml","l"};
    private static final String[] TAILS = {"","r","R","rt","s","sw","sg","e","f","fr","fa","fq","ft","fx","fv","fg","a","q","qt","t","T","d","w","c","z","x","v","g"};
    private static final String JAMO = "ㄱㄲㄳㄴㄵㄶㄷㄸㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅃㅄㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String[] KEYS = {"r","R","rt","s","sw","sg","e","E","f","fr","fa","fq","ft","fx","fv","fg","a","q","Q","qt","t","T","d","w","W","c","z","x","v","g","k","o","i","O","j","p","u","P","h","hk","ho","hl","y","n","nj","np","nl","b","m","ml","l"};

    // Precompiled forms of the expressions previously passed to String.matches/replaceAll/split (same semantics).
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HAS_LATIN_LOWER = Pattern.compile(".*[a-z].*");
    private static final Pattern HANGUL_WORD = Pattern.compile("[가-힣]{2,}");
    private static final Pattern LATIN_RUN = Pattern.compile("[A-Za-z]+");

    private KoreanSearch() {}

    public static String normalize(String value) {
        return WHITESPACE.matcher(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    public static String keyboard(String value) {
        var result = new StringBuilder();
        for (char c : Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC).toCharArray()) {
            if (isSyllable(c)) {
                result.append(syllableKeys(c));
            } else {
                int index = JAMO.indexOf(c);
                result.append(index < 0 ? String.valueOf(c) : KEYS[index]);
            }
        }
        // Preserve shifted Korean keys: ㄱ/ㄲ and ㅐ/ㅒ are different letters.
        return WHITESPACE.matcher(result).replaceAll("");
    }

    /** Lower is better; -1 means unrelated. Fuzzy matching needs at least two Hangul syllables. */
    public static int score(String value, String query) {
        return score(prepare(value), Query.term(query));
    }

    /** Same result as {@link #score(String, String)} for the value and query that were prepared. */
    public static int score(Prepared value, Query query) {
        return score(value, query.whole);
    }

    private static int score(Prepared value, Term query) {
        String text = value.text, term = query.text;
        if (term.isEmpty() || text.contains(term)) return 0;
        if (term.length() < 2) return -1;
        if (query.allInitials) return value.initials.contains(term) ? 2 : -1;
        String queryKeys = query.keys;
        if ((query.latin || value.latin) && value.keys.contains(queryKeys)) return 1;
        // Restrict fuzzy matching to Hangul text: numbers, punctuation and English names remain literal.
        if (!query.hangulWord) return -1;
        int limit = term.length() >= 4 ? 2 : 1;
        int best = limit + 1;
        // Compare complete syllable windows so a missing final consonant is one edit,
        // but a fragment of an unrelated syllable cannot become an exact keyboard match.
        for (int start = 0; start < text.length(); start++) {
            for (int length = Math.max(2, term.length() - 1); length <= term.length() + 1 && start + length <= text.length(); length++) {
                int end = start + length;
                if (value.nonHangul[end] != value.nonHangul[start]) continue; // window is not all Hangul syllables
                int from = value.offsets[start], to = value.offsets[end];
                if (Math.abs((to - from) - queryKeys.length()) <= limit)
                    best = Math.min(best, distance(value.syllableKeys.substring(from, to), queryKeys));
            }
        }
        return best <= limit ? 3 + best : -1;
    }

    /** All meaningful words may match across name and address, in either order. */
    public static int bestScore(String name, String address, String query) {
        return bestScore(prepare(name), prepare(address), prepareQuery(query));
    }

    /** Same result as {@link #bestScore(String, String, String)} for the prepared name, address and query. */
    public static int bestScore(Prepared name, Prepared address, Query query) {
        int whole = fieldScore(name, address, query.whole);
        if (whole == 0) return 0;
        // Single-character fragments retain the existing whitespace-insensitive phrase behavior.
        if (query.parts == null) return whole;
        int combined = 0;
        for (Term term : query.parts) {
            int part = fieldScore(name, address, term);
            if (part < 0) return whole;
            combined = Math.max(combined, part);
        }
        return whole < 0 ? combined : Math.min(whole, combined);
    }

    private static int fieldScore(Prepared name, Prepared address, Term query) {
        int first = score(name, query), second = score(address, query);
        return first < 0 ? second : second < 0 ? first : Math.min(first, second);
    }

    public static boolean matches(String value, String query) { return score(value, query) >= 0; }

    /** Precomputes everything {@link #score(Prepared, Query)} needs from one searchable value (name or address). */
    public static Prepared prepare(String value) {
        return new Prepared(value);
    }

    /** Precomputes a user query once per request, including its word split used by {@link #bestScore}. */
    public static Query prepareQuery(String query) {
        Term whole = new Term(query);
        String[] terms = WHITESPACE.split(query.trim());
        List<Term> parts = null;
        if (terms.length >= 2 && Arrays.stream(terms).noneMatch(term -> normalize(term).length() < 2)) {
            parts = new ArrayList<>(terms.length);
            for (String term : terms) parts.add(new Term(term));
        }
        return new Query(whole, parts == null ? null : List.copyOf(parts));
    }

    /** A searchable value with its normalised text, keyboard keys, initials and per-syllable keys precomputed. */
    public static final class Prepared {
        private final String text;
        private final String keys;
        private final String initials;
        private final boolean latin;
        /** Concatenated two-set keys of every Hangul syllable of {@code text} (other characters contribute nothing). */
        private final String syllableKeys;
        /** {@code offsets[i]}: start of {@code text.charAt(i)}'s keys in {@link #syllableKeys}; length text+1. */
        private final int[] offsets;
        /** {@code nonHangul[i]}: number of non-syllable characters in {@code text[0, i)}; length text+1. */
        private final int[] nonHangul;

        private Prepared(String value) {
            this.text = normalize(value);
            this.keys = keyboard(value);
            this.latin = HAS_LATIN_LOWER.matcher(text).matches();
            var initialsBuilder = new StringBuilder(text.length());
            var syllables = new StringBuilder(text.length() * 3);
            this.offsets = new int[text.length() + 1];
            this.nonHangul = new int[text.length() + 1];
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                offsets[i] = syllables.length();
                if (isSyllable(c)) {
                    initialsBuilder.append(INITIALS.charAt((c - '가') / 588));
                    syllables.append(syllableKeys(c));
                    nonHangul[i + 1] = nonHangul[i];
                } else {
                    initialsBuilder.append(c);
                    nonHangul[i + 1] = nonHangul[i] + 1;
                }
            }
            offsets[text.length()] = syllables.length();
            this.initials = initialsBuilder.toString();
            this.syllableKeys = syllables.toString();
        }

        /** The normalised text ({@link #normalize(String)}). */
        public String text() { return text; }
    }

    /** A prepared user query: the whole query plus, when every word has at least two characters, each word. */
    public static final class Query {
        private final Term whole;
        private final List<Term> parts;

        private Query(Term whole, List<Term> parts) {
            this.whole = whole;
            this.parts = parts;
        }

        private static Term term(String query) { return new Term(query); }

        /** The normalised query text. */
        public String text() { return whole.text; }
    }

    private static final class Term {
        final String text;
        final String keys;
        final boolean allInitials;
        final boolean latin;
        final boolean hangulWord;

        Term(String query) {
            this.text = normalize(query);
            this.keys = keyboard(query);
            this.allInitials = text.chars().allMatch(c -> INITIALS.indexOf(c) >= 0);
            this.latin = HAS_LATIN_LOWER.matcher(text).matches();
            this.hangulWord = HANGUL_WORD.matcher(text).matches();
        }
    }

    private static boolean isSyllable(char c) { return c >= '가' && c <= '힣'; }

    private static String syllableKeys(char c) {
        int offset = c - '가';
        return LEADS[offset / 588] + VOWELS[offset % 588 / 28] + TAILS[offset % 28];
    }

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
        return LATIN_RUN.matcher(query).replaceAll(match -> Matcher.quoteReplacement(compose(match.group())));
    }

    private static String compose(String keys) {
        var result = new StringBuilder();
        int pos = 0;
        while (pos < keys.length()) {
            int lead = LEAD_LIST.indexOf(keys.substring(pos, pos + 1));
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
