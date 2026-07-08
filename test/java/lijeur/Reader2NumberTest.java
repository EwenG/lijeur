package lijeur;

import clojure.lang.RT;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link Reader2}'s number reading against the actual Clojure 1.12.5 reader
 * ({@link clojure.lang.RT#readString}). Every input is checked at several buffer chunk
 * sizes so the tokenizer's refill / compaction / growth paths are exercised too.
 */
public class Reader2NumberTest {

  private static final int[] CHUNK_SIZES = {1, 2, 3, 7, 4096};

  // Reads one form with Reader2, capturing either the value or the thrown exception.
  private static Object reader2Read(String input, int chunkSize) {
    try {
      return new Reader2(new StringReader(input), chunkSize).read();
    } catch (Throwable t) {
      return t;
    }
  }

  private static Object clojureRead(String input) {
    try {
      return RT.readString(input);
    } catch (Throwable t) {
      return t;
    }
  }

  /** Asserts Reader2 matches Clojure for `input`, across all chunk sizes. */
  private static void assertMatchesClojure(String input) {
    Object expected = clojureRead(input);
    boolean clojureThrew = expected instanceof Throwable;

    for (int chunk : CHUNK_SIZES) {
      Object actual = reader2Read(input, chunk);

      if (clojureThrew) {
        // Reader2 returns its EOF sentinel for a formless top-level input (e.g. a bare
        // comment) where Clojure's read-string throws EOF — an intentional API difference.
        assertTrue(actual instanceof Throwable || actual == Reader2.EOF,
            "Clojure threw " + expected.getClass().getSimpleName()
                + " but Reader2 returned " + actual + " for \"" + input + "\" (chunk=" + chunk + ")");
        continue;
      }

      assertFalse(actual instanceof Throwable,
          "Clojure read " + expected + " but Reader2 threw " + actual + " for \"" + input + "\" (chunk=" + chunk + ")");
      if (expected == null || actual == null) { // nil
        assertEquals(expected, actual, "nil mismatch for \"" + input + "\" (chunk=" + chunk + ")");
        continue;
      }
      assertEquals(expected.getClass(), actual.getClass(),
          "type mismatch for \"" + input + "\" (chunk=" + chunk + ")");
      assertEquals(expected, actual,
          "value mismatch for \"" + input + "\" (chunk=" + chunk + ")");
    }
  }

  private static void assertAllMatchClojure(String... inputs) {
    for (String in : inputs) assertMatchesClojure(in);
  }

  @Test
  public void testLongs() {
    assertAllMatchClojure("0", "1", "123", "-123", "+123", "42", "-1", "+1",
        "1000000", "-999", "9223372036854775807", "-9223372036854775808");
  }

  @Test
  public void testBigInts() {
    assertAllMatchClojure("9223372036854775808", "-9223372036854775809",
        "0N", "-0N", "+0N", "10N", "-10N", "123456789012345678901234567890",
        "-123456789012345678901234567890", "99999999999999999999N");
  }

  @Test
  public void testZeroForms() {
    assertAllMatchClojure("0", "-0", "+0", "00", "000", "0N", "-0N");
  }

  @Test
  public void testOctal() {
    assertAllMatchClojure("007", "0777", "010", "-0777", "+010", "0777N",
        "0777777777777777777777", "01777777777777777777777", "02000000000000000000000");
  }

  @Test
  public void testInvalidOctal() {
    // Leading-zero decimals that aren't valid octal are rejected by Clojure.
    assertAllMatchClojure("08", "09", "0778", "019");
  }

  @Test
  public void testHex() {
    assertAllMatchClojure("0x1F", "0X1f", "0xff", "0xffN", "-0xFF", "0x0", "0xdeadbeef",
        "+0x10", "0x7fffffffffffffff", "0x8000000000000000", "0xffffffffffffffff",
        "-0x8000000000000000", "0xG", "0x", "0xffffffffffffffffffN");
  }

  @Test
  public void testRadix() {
    assertAllMatchClojure("2r1010", "16rff", "36rZ", "-2r111", "8r777", "10r123", "2r0");
  }

  @Test
  public void testRadixOutOfRange() {
    assertAllMatchClojure("1r0", "37rZ", "99rZ");
  }

  @Test
  public void testDoubles() {
    assertAllMatchClojure("1.0", "1.", "-1.5", "3.14", "1e3", "1E3", "1.5e-2",
        "1e10", "10.", "-0.0", "0.0", "1.5e+3", "123.456",
        // leading-zero floats: intPat does NOT claim these, so they are doubles
        "0.5", "08.5", "0e3", "00.5", "0.0e0", "-0.25", "+1.5", "0.", "07.0");
  }

  @Test
  public void testBigDecimals() {
    assertAllMatchClojure("1M", "1.5M", "-2.5M", "0M", "3.14159M", "100M", "1e10M");
  }

  @Test
  public void testRatios() {
    assertAllMatchClojure("1/2", "4/2", "6/4", "-3/4", "+3/6", "10/5", "-6/3", "22/7");
  }

  @Test
  public void testRatioDivideByZero() {
    // Clojure throws ArithmeticException; Reader2 does too (via Numbers.divide).
    assertMatchesClojure("1/0");
  }

  @Test
  public void testInvalidNumbers() {
    assertAllMatchClojure("1x", "12abc", "1.2.3", "1/2/3", "0xG", "2r2", "1e", "1..0");
  }

  @Test
  public void testNumberFollowedByDelimiter() {
    // Reader2 and RT.readString both read a single leading form.
    assertAllMatchClojure("123)", "1 2 3", "42]", "7}", "5;comment", "9 ", "  8  ");
  }

  @Test
  public void testEofReturnsSentinel() throws java.io.IOException {
    assertSame(Reader2.EOF, new Reader2(new StringReader("")).read());
    assertSame(Reader2.EOF, new Reader2(new StringReader("   ")).read());
    assertSame(Reader2.EOF, new Reader2(new StringReader(" , \n ")).read());
  }

  @Test
  public void testFuzzAgainstClojure() {
    // Generate number-ish tokens from a numeric alphabet and diff against Clojure.
    // Only tokens that start like a number (digit, or sign+digit) are in scope.
    char[] alphabet = "0123456789.eExXrRMN/+-abcdefABCDEF".toCharArray();
    java.util.Random rnd = new java.util.Random(20260708L); // fixed seed: reproducible
    int checked = 0;
    for (int t = 0; t < 20000; t++) {
      int len = 1 + rnd.nextInt(8);
      StringBuilder sb = new StringBuilder(len);
      for (int i = 0; i < len; i++) sb.append(alphabet[rnd.nextInt(alphabet.length)]);
      String tok = sb.toString();

      char c0 = tok.charAt(0);
      boolean numberStart = Character.isDigit(c0)
          || ((c0 == '+' || c0 == '-') && tok.length() > 1 && Character.isDigit(tok.charAt(1)));
      if (!numberStart) continue; // Clojure would read a symbol; out of scope

      assertMatchesClojure(tok);
      checked++;
    }
    assertTrue(checked > 500, "expected a meaningful number of fuzz cases, got " + checked);
  }

  @Test
  public void testSymbols() {
    assertAllMatchClojure("foo", "foo-bar", "foo/bar", "clojure.core/x", "+", "-", "/",
        "*", "name", ".", "..", "...", "->", "foo?", "foo!", "*ns*", "a.b.c", "a.b/c-d",
        "foo#", "foo'", "a'b", "foo//", "foo:bar", "a/b/c", "foo/bar/baz",
        "x#y%z", "clojure.string/join", ".5", "-foo", "+foo", "->>", "<=");
  }

  @Test
  public void testKeywords() {
    assertAllMatchClojure(":foo", ":foo/bar", ":a.b/c", ":foo:bar", ":123", ":1a", ":/",
        ":clojure.core/x", ":-", ":+", ":*ns*", ":a/b/c", ":x.y.z/w");
  }

  @Test
  public void testAutoResolvedKeywords() {
    // ::foo resolves against clojure.core/*ns* — same var Reader2 and RT.readString read.
    // ::ns/name resolves ns as an ALIAS only, so an unaliased (even real) namespace throws.
    assertAllMatchClojure("::foo", "::bar", "::x",
        "::clojure.core/bar", "::clojure.core/x", "::nope/y", "::foo/bar");
  }

  @Test
  public void testArrayClassSymbols() {
    // Clojure 1.12 array-class syntax: ns/N with N a single 1-9.
    assertAllMatchClojure("int/1", "String/2", "a/8", "java.lang.String/3", "foo/9",
        "a/0", "a/10", "a/8b", "foo/1bar", "&X_/8");
  }

  @Test
  public void testNilTrueFalse() {
    assertAllMatchClojure("nil", "true", "false");
  }

  @Test
  public void testInvalidTokens() {
    assertAllMatchClojure("foo:", "//", ":", "::", ":::", "foo/", "/foo", ":foo/",
        "a::b", "::foo/bar", "::nope/x", "x:");
  }

  @Test
  public void testTokenFollowedByDelimiter() {
    assertAllMatchClojure("foo)", "foo bar", ":kw]", "nil,", "true}", "sym;comment",
        "foo\"str", "a\\b");
  }

  @Test
  public void testStrings() {
    assertAllMatchClojure(
        "\"hello\"", "\"\"", "\"a b c\"", "\"123\"", "\"with,comma\"",
        "\"multi word string\"", "\"tab\tliteral\"", "\"unicodeéhere\"");
  }

  @Test
  public void testStringEscapes() {
    assertAllMatchClojure(
        "\"a\\nb\"", "\"a\\tb\"", "\"a\\rb\"", "\"a\\\\b\"", "\"a\\\"b\"",
        "\"\\b\\f\\n\\r\\t\"", "\"q:\\\"end\"", "\"back:\\\\end\"", "\"\\n\\n\\n\"");
  }

  @Test
  public void testStringUnicodeAndOctal() {
    assertAllMatchClojure(
        "\"\\u0041\"", "\"\\u00e9\"", "\"\\uABCD\"", "\"\\101\"", "\"\\0\"",
        "\"\\377\"", "\"pre\\u0041post\"", "\"\\41x\"", "\"\\7\"");
  }

  @Test
  public void testStringErrors() {
    assertAllMatchClojure(
        "\"unterminated", "\"bad\\xescape\"", "\"\\u12\"", "\"\\uZZZZ\"",
        "\"\\400\"", "\"trailing\\", "\"\\u\"");
  }

  @Test
  public void testCharacters() {
    assertAllMatchClojure(
        "\\a", "\\A", "\\1", "\\(", "\\)", "\\\"", "\\\\", "\\;", "\\/", "\\+", "\\ ");
  }

  @Test
  public void testNamedCharacters() {
    assertAllMatchClojure(
        "\\newline", "\\space", "\\tab", "\\backspace", "\\formfeed", "\\return");
  }

  @Test
  public void testCharacterUnicodeAndOctal() {
    assertAllMatchClojure(
        "\\u0041", "\\u00e9", "\\uABCD", "\\o101", "\\o0", "\\o377", "\\o7");
  }

  @Test
  public void testCharacterErrors() {
    assertAllMatchClojure(
        "\\uD800", "\\u123", "\\uXYZW", "\\o777", "\\o1234", "\\foo", "\\newlin", "\\");
  }

  @Test
  public void testCharacterFollowedByDelimiter() {
    assertAllMatchClojure("\\a)", "\\a b", "\\newline;c", "\\space]", "\\1 2", "\\( x");
  }

  @Test
  public void testLists() {
    assertAllMatchClojure("(1 2 3)", "()", "(a b c)", "( 1  2 )", "(1,2,3)",
        "(+ 1 2)", "(nil true false)", "(())", "(1 (2 (3)))", "(:a :b)", "(1 2 . 3)");
  }

  @Test
  public void testVectors() {
    assertAllMatchClojure("[1 2 3]", "[]", "[a b c]", "[[1] [2]]", "[1,2,3]",
        "[nil true]", "[[[]]]", "[:x :y :z]", "[\"s\" \\c 1]");
  }

  @Test
  public void testMaps() {
    assertAllMatchClojure("{:a 1 :b 2}", "{}", "{\"k\" \"v\"}", "{1 2 3 4}",
        "{:a {:b 1}}", "{:x [1 2]}", "{:a 1, :b 2, :c 3}");
  }

  @Test
  public void testSets() {
    assertAllMatchClojure("#{1 2 3}", "#{}", "#{:a :b}", "#{\"x\" \"y\"}",
        "#{1 [2] :three}", "#{nil true false}");
  }

  @Test
  public void testNestedCollections() {
    assertAllMatchClojure("(1 [2 3] {:x 4})", "[{:a #{1 2}} (3 4)]",
        "{:list (1 2 3) :vec [4 5]}", "(((1)))", "[() [] {} #{}]", "{[1 2] #{3 4}}");
  }

  @Test
  public void testCollectionErrors() {
    assertAllMatchClojure(")", "]", "}", "(1 2", "[1 2", "{:a 1", "#{1 2",
        "(1 ] )", "[1 2 3)", "{:a}", "{:a 1 :a 2}", "#{1 1}", "{:a 1 :b 2 :c}");
  }

  @Test
  public void testComments() {
    assertAllMatchClojure("; comment\n42", "(1 ; c\n 2)", "#! shebang\n7",
        "42 ; trailing", "[1 ;; c\n 2 3]", "; only-first\n(a b)");
  }

  @Test
  public void testDiscard() {
    assertAllMatchClojure("#_ 1 2", "(1 #_2 3)", "[#_#_1 2 3]", "#_(1 2) 99",
        "{:a #_:skip 1}", "#_#_1 2 3", "(#_1)", "[1 #_ ; c\n 2 3]");
  }

  @Test
  public void testCollectionFuzzAgainstClojure() {
    // Random s-expressions built from the reader macros we support, plus atoms.
    String[] atoms = {"1", "-2", "3.5", "foo", ":kw", "a/b", "\"s\"", "\\c", "nil",
        "true", "1/2", "0xff", "#_9", ";x\n"};
    String[] open = {"(", "[", "{", "#{"};
    String[] close = {")", "]", "}", "}"};
    java.util.Random rnd = new java.util.Random(0xDEADBEEFL);
    for (int t = 0; t < 20000; t++) {
      StringBuilder sb = new StringBuilder();
      java.util.Deque<String> stack = new java.util.ArrayDeque<>();
      int tokens = 1 + rnd.nextInt(14);
      for (int i = 0; i < tokens; i++) {
        int r = rnd.nextInt(10);
        if (r < 3 && stack.size() < 5) { int k = rnd.nextInt(open.length); sb.append(open[k]).append(' '); stack.push(close[k]); }
        else if (r < 4 && !stack.isEmpty()) { sb.append(stack.pop()).append(' '); }
        else { sb.append(atoms[rnd.nextInt(atoms.length)]).append(' '); }
      }
      while (!stack.isEmpty()) sb.append(stack.pop()).append(' ');   // usually balanced
      assertMatchesClojure(sb.toString().trim());
    }
  }

  @Test
  public void testStringAndCharFuzzAgainstClojure() {
    // Random string bodies (with backslashes/quotes) and character forms vs Clojure.
    char[] body = "abc \\\"nrtufo019AF{}[]:;/".toCharArray();
    java.util.Random rnd = new java.util.Random(0xC0FFEEL);
    for (int t = 0; t < 20000; t++) {
      int len = rnd.nextInt(8);
      StringBuilder sb = new StringBuilder("\"");
      for (int i = 0; i < len; i++) sb.append(body[rnd.nextInt(body.length)]);
      sb.append('"');
      assertMatchesClojure(sb.toString());
    }
    char[] cbody = "abcnewliuo0139AF() \\".toCharArray();
    for (int t = 0; t < 20000; t++) {
      int len = 1 + rnd.nextInt(6);
      StringBuilder sb = new StringBuilder("\\");
      for (int i = 0; i < len; i++) sb.append(cbody[rnd.nextInt(cbody.length)]);
      assertMatchesClojure(sb.toString());
    }
  }

  @Test
  public void testSymbolFuzzAgainstClojure() {
    // Tokens that reach readToken in Clojure (first char is not whitespace, digit, or a
    // reader-macro char). Embedded terminators are fine: both readers stop at the same spot.
    char[] alphabet = "abcXYZ+-*/.:!?_'#%<>=&$.0123456789".toCharArray();
    java.util.Random rnd = new java.util.Random(19731129L); // fixed seed
    int checked = 0;
    for (int t = 0; t < 20000; t++) {
      int len = 1 + rnd.nextInt(8);
      StringBuilder sb = new StringBuilder(len);
      for (int i = 0; i < len; i++) sb.append(alphabet[rnd.nextInt(alphabet.length)]);
      String tok = sb.toString();

      char c0 = tok.charAt(0);
      if (Character.isDigit(c0) || isWhitespaceCh(c0) || isMacroCh(c0)) continue;
      assertMatchesClojure(tok);
      checked++;
    }
    assertTrue(checked > 500, "expected a meaningful number of fuzz cases, got " + checked);
  }

  private static boolean isWhitespaceCh(char c) {
    return c == ',' || Character.isWhitespace(c);
  }

  private static boolean isMacroCh(char c) {
    return "\";'@^`~()[]{}\\%#".indexOf(c) >= 0;
  }
}
