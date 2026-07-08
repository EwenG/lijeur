package lijeur;

import clojure.lang.BigInt;
import clojure.lang.IFn;
import clojure.lang.IMapEntry;
import clojure.lang.IMeta;
import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.IReference;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.LazilyPersistentVector;
import clojure.lang.Namespace;
import clojure.lang.Numbers;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentList;
import clojure.lang.RT;
import clojure.lang.Symbol;

import java.io.IOException;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fast Clojure/EDN reader built on {@link Buffer}. Behaviour matches Clojure
 * 1.12.5's {@code LispReader}.
 *
 * <p>Scope so far: numbers, symbols, keywords, strings, characters, the literals
 * {@code nil} / {@code true} / {@code false}, collections ({@code (...)}, {@code [...]},
 * {@code {...}}, {@code #{...}}), and comments ({@code ;}, {@code #!}, {@code #_}).
 * quote ({@code '}), deref ({@code @}), var ({@code #'}), unquote ({@code ~}/{@code ~@}),
 * metadata ({@code ^}), symbolic values ({@code ##Inf}/{@code ##-Inf}/{@code ##NaN}),
 * regex ({@code #"..."}), tagged literals ({@code #inst}/{@code #uuid}/data readers), and
 * namespaced maps ({@code #:ns{...}} / {@code #::{...}}). {@link #read()} skips leading
 * whitespace and reads a single form; a not-yet-supported macro (syntax-quote {@code `},
 * anonymous fn {@code #(}) throws {@link UnsupportedOperationException}.
 *
 * <p>{@code *read-eval*} is honoured: {@code :unknown} disallows all reading, and the
 * {@code #=} eval reader throws when {@code *read-eval*} is {@code false}/{@code nil}.
 * Actually evaluating {@code #=} forms is out of scope (it needs the compiler), so a
 * {@code #=} form with eval enabled throws {@link UnsupportedOperationException}.
 *
 * <p>Tokens are scanned directly over the {@link Buffer} backing array (no per-character
 * method dispatch). The common number case — a plain decimal {@code long} — plus hex,
 * octal, and doubles are parsed inline without regex; the rarer number forms and all
 * symbol/keyword interpretation are delegated to faithful ports of
 * {@code LispReader.matchNumber} / {@code matchSymbol}, so results (and exceptions) are
 * identical to Clojure's.
 */
public class Reader2 {

  /** Returned by {@link #read()} at end of input. */
  public static final Object EOF = new Object();

  public static final int DEFAULT_CHUNK_SIZE = 4096;

  private final Buffer buffer;

  // Macro characters, matching clojure.lang.LispReader's `macros` table (all ASCII).
  private static final boolean[] MACRO = new boolean[128];
  static {
    for (char c : new char[]{'"', ';', '\'', '@', '^', '`', '~',
                             '(', ')', '[', ']', '{', '}', '\\', '%', '#'}) {
      MACRO[c] = true;
    }
  }

  public Reader2(java.io.Reader r, int chunkSize) {
    this.buffer = new Buffer(r, chunkSize);
  }

  public Reader2(java.io.Reader r) {
    this(r, DEFAULT_CHUNK_SIZE);
  }

  // Internal control-flow sentinels, mirroring LispReader's read loop.
  private static final Object READ_EOF = new Object();       // end of input
  private static final Object READ_FINISHED = new Object();  // hit the expected closing delimiter
  private static final Object SKIP = new Object();           // no value (comment / discard); continue

  // *read-eval* gating, mirroring LispReader. :unknown disallows all reading; false/nil
  // additionally disables the #= eval reader.
  private static final clojure.lang.Var READ_EVAL = RT.var("clojure.core", "*read-eval*");
  private static final Keyword UNKNOWN = Keyword.intern(null, "unknown");

  /** Reads one form, or returns {@link #EOF} at end of input. */
  public Object read() throws IOException {
    // LispReader guards the top of read() the same way: :unknown blocks everything.
    if (READ_EVAL.deref() == UNKNOWN)
      throw new RuntimeException("Reading disallowed - *read-eval* bound to :unknown");
    Object o = read0(0);
    return o == READ_EOF ? EOF : o;
  }

  // Reads one form. `returnOn` is the closing delimiter to stop on (0 = none): on it, consumes
  // the delimiter and returns READ_FINISHED. Returns READ_EOF at end of input. Loops over
  // comments and #_ discards (which produce no value), matching LispReader's read loop.
  private Object read0(int returnOn) throws IOException {
    while (true) {
      int c1 = skipWhitespace();
      if (c1 == -1) return READ_EOF;
      if (returnOn != 0 && c1 == returnOn) { buffer.read(); return READ_FINISHED; }
      if (Character.isDigit(c1)) return readNumber();
      switch (c1) {
        case '"':  buffer.read(); return readStringForm();
        case '\\': buffer.read(); return readCharacterForm();
        case '(':  buffer.read(); return readList();
        case '[':  buffer.read(); return readVector();
        case '{':  buffer.read(); return readMap();
        case ')': case ']': case '}':
          throw new RuntimeException("Unmatched delimiter: " + (char) c1);
        case ';':  buffer.read(); skipLine(); continue;         // line comment
        case '#':  { Object o = readDispatch(); if (o == SKIP) continue; return o; }
        case '\'': buffer.read(); return RT.list(QUOTE, readForm());              // 'x
        case '@':  buffer.read(); return RT.list(DEREF, readForm());             // @x
        case '~':  buffer.read(); return readUnquote();                          // ~x / ~@x
        case '^':  buffer.read(); return readMeta();                             // ^meta form
        case '%':  return readToken();                                           // % is a symbol outside #()
        case '`':  throw new UnsupportedOperationException(
            "Reader2 does not yet support syntax-quote (`)");
        default:   break;
      }
      if (isMacro(c1)) throw new UnsupportedOperationException(
          "Reader2 does not yet support the reader macro '" + (char) c1 + "'");
      if ((c1 == '+' || c1 == '-') && Character.isDigit(peekAt(1))) return readNumber();
      return readToken();   // symbols, keywords, nil / true / false
    }
  }

  // Reads forms until the closing `delim`, collecting them. Port of readDelimitedList.
  private ArrayList<Object> readDelimitedList(int delim) throws IOException {
    ArrayList<Object> acc = new ArrayList<>();
    while (true) {
      Object form = read0(delim);
      if (form == READ_EOF) throw new RuntimeException("EOF while reading");
      if (form == READ_FINISHED) return acc;
      acc.add(form);
    }
  }

  private Object readList() throws IOException {
    ArrayList<Object> a = readDelimitedList(')');
    return a.isEmpty() ? PersistentList.EMPTY : PersistentList.create(a);
  }

  private Object readVector() throws IOException {
    return LazilyPersistentVector.create(readDelimitedList(']'));
  }

  private Object readMap() throws IOException {
    Object[] a = readDelimitedList('}').toArray();
    if ((a.length & 1) == 1)
      throw new RuntimeException("Map literal must contain an even number of forms");
    return RT.map(a);                       // RT.map does the duplicate-key check
  }

  private Object readSet() throws IOException {
    return PersistentHashSet.createWithCheck(readDelimitedList('}'));
  }

  // Symbols used by the wrapping macros, matching LispReader.
  private static final Symbol QUOTE = Symbol.intern("quote");
  private static final Symbol THE_VAR = Symbol.intern("var");
  private static final Symbol DEREF = Symbol.intern("clojure.core", "deref");
  private static final Symbol UNQUOTE = Symbol.intern("clojure.core", "unquote");
  private static final Symbol UNQUOTE_SPLICING = Symbol.intern("clojure.core", "unquote-splicing");
  private static final Symbol SYM_INF = Symbol.intern("Inf");
  private static final Symbol SYM_NEG_INF = Symbol.intern("-Inf");
  private static final Symbol SYM_NAN = Symbol.intern("NaN");

  // Reads a required form (end of input is an error), as the reader macros do.
  private Object readForm() throws IOException {
    Object o = read0(0);
    if (o == READ_EOF) throw new RuntimeException("EOF while reading");
    return o;
  }

  // '~' just read: ~@form -> (unquote-splicing form), ~form -> (unquote form).
  private Object readUnquote() throws IOException {
    if (buffer.peek() == '@') { buffer.read(); return RT.list(UNQUOTE_SPLICING, readForm()); }
    return RT.list(UNQUOTE, readForm());
  }

  // Handles a form beginning with '#'. The leading '#' has NOT been consumed. Returns the
  // form, or SKIP for a no-value dispatch (#_, #!).
  private Object readDispatch() throws IOException {
    buffer.read();                          // consume '#'
    int ch = buffer.peek();                 // dispatch char (consumed below, except for tags)
    if (ch == -1) throw new RuntimeException("EOF while reading character");
    switch (ch) {
      case '{': buffer.read(); return readSet();
      case '_': {                           // discard the next form
        buffer.read();
        Object discarded = read0(0);
        if (discarded == READ_EOF) throw new RuntimeException("EOF while reading");
        return SKIP;
      }
      case '!': buffer.read(); skipLine(); return SKIP;                 // shebang line comment
      case '\'': buffer.read(); return RT.list(THE_VAR, readForm());    // #'x -> (var x)
      case '"': buffer.read(); return readRegex();                      // #"..." regex
      case '#': buffer.read(); return readSymbolicValue();              // ##Inf / ##-Inf / ##NaN
      case '<': buffer.read(); throw new RuntimeException("Unreadable form");
      case '?': buffer.read(); throw new RuntimeException("Conditional read not allowed");
      case '=': {                             // #= read-eval
        buffer.read();
        // EvalReader checks *read-eval* before reading its form; false/nil throws exactly this.
        if (!RT.booleanCast(READ_EVAL.deref()))
          throw new RuntimeException("EvalReader not allowed when *read-eval* is false.");
        // Evaluating would require the compiler; that is intentionally out of scope. The
        // *read-eval* gating above is the security-relevant behaviour and matches LispReader.
        throw new UnsupportedOperationException("Reader2 does not support #= read-eval");
      }
      case ':': buffer.read(); return readNamespaceMap();               // #:ns{...} / #::{...}
      default:
        if (Character.isLetter(ch)) return readTagged();               // #tag form (leave the letter)
        throw new UnsupportedOperationException(
            "Reader2 does not yet support the dispatch macro '#" + (char) ch + "'");
    }
  }

  // #"..." — chars up to the closing quote; a backslash keeps the next char literally (so \d
  // stays \d for Pattern.compile). Port of LispReader.RegexReader.
  private Object readRegex() throws IOException {
    Buffer b = buffer;
    StringBuilder sb = new StringBuilder();
    while (true) {
      int ch = b.read();
      if (ch == '"') break;
      if (ch == -1) throw new RuntimeException("EOF while reading regex");
      sb.append((char) ch);
      if (ch == '\\') {
        int ch2 = b.read();
        if (ch2 == -1) throw new RuntimeException("EOF while reading regex");
        sb.append((char) ch2);
      }
    }
    return java.util.regex.Pattern.compile(sb.toString());
  }

  // ## symbolic values. Port of LispReader.SymbolicValueReader.
  private Object readSymbolicValue() throws IOException {
    Object form = readForm();
    if (!(form instanceof Symbol)) throw new RuntimeException("Invalid token: ##" + form);
    if (form.equals(SYM_INF)) return Double.POSITIVE_INFINITY;
    if (form.equals(SYM_NEG_INF)) return Double.NEGATIVE_INFINITY;
    if (form.equals(SYM_NAN)) return Double.NaN;
    throw new RuntimeException("Unknown symbolic value: ##" + form);
  }

  // #tag form — reads the tag symbol and a form, then applies the matching data reader.
  private Object readTagged() throws IOException {
    Object tag = readForm();
    if (!(tag instanceof Symbol)) throw new RuntimeException("Reader tag must be a symbol");
    Object form = readForm();
    IFn reader = dataReaderFor((Symbol) tag);
    if (reader != null) return reader.invoke(form);
    throw new RuntimeException("No reader function for tag " + tag);
  }

  // #:ns{...} / #::{...} / #::alias{...} — the leading "#:" has been consumed. Port of
  // LispReader.NamespaceMapReader. Unqualified keys get the namespace; keys with the "_"
  // namespace become unqualified; already-qualified keys are left alone.
  private Object readNamespaceMap() throws IOException {
    Buffer b = buffer;
    boolean auto = false;
    if (b.peek() == ':') { b.read(); auto = true; }   // #::

    Object osym = null;
    int nc = b.peek();
    if (nc == '{') {
      // no namespace symbol before the map (osym stays null)
    } else if (nc != -1 && isWhitespace(nc)) {
      int c = skipWhitespace();
      if (!(auto && c == '{'))
        throw new RuntimeException("Namespaced map must specify a namespace");
    } else {
      osym = readForm();                              // the namespace symbol (or EOF -> error)
      if (skipWhitespace() != '{')
        throw new RuntimeException("Namespaced map must specify a map");
    }

    String nsname;
    if (auto) {
      if (osym == null) {
        nsname = currentNS().getName().getName();
      } else if (osym instanceof Symbol && ((Symbol) osym).getNamespace() == null) {
        Namespace resolved = currentNS().lookupAlias(Symbol.intern(((Symbol) osym).getName()));
        if (resolved == null)
          throw new RuntimeException("Unknown auto-resolved namespace alias: " + osym);
        nsname = resolved.getName().getName();
      } else {
        throw new RuntimeException("Namespaced map must specify a valid namespace: " + osym);
      }
    } else if (osym instanceof Symbol && ((Symbol) osym).getNamespace() == null) {
      nsname = ((Symbol) osym).getName();
    } else {
      throw new RuntimeException("Namespaced map must specify a valid namespace: " + osym);
    }

    b.read();                                          // consume '{'
    ArrayList<Object> kvs = readDelimitedList('}');
    if ((kvs.size() & 1) == 1)
      throw new RuntimeException("Namespaced map literal must contain an even number of forms");
    Object[] out = new Object[kvs.size()];
    for (int i = 0; i < kvs.size(); i += 2) {
      out[i] = qualifyKey(kvs.get(i), nsname);
      out[i + 1] = kvs.get(i + 1);
    }
    return RT.map(out);                                // RT.map does the duplicate-key check
  }

  private static Object qualifyKey(Object key, String nsname) {
    if (key instanceof Keyword) {
      Keyword kw = (Keyword) key;
      if (kw.getNamespace() == null) return Keyword.intern(nsname, kw.getName());
      if (kw.getNamespace().equals("_")) return Keyword.intern(null, kw.getName());
      return key;
    }
    if (key instanceof Symbol) {
      Symbol sym = (Symbol) key;
      if (sym.getNamespace() == null) return Symbol.intern(nsname, sym.getName());
      if (sym.getNamespace().equals("_")) return Symbol.intern(null, sym.getName());
      return key;
    }
    return key;
  }

  // Looks up a data reader: *data-readers* first, then default-data-readers (#inst, #uuid).
  private static IFn dataReaderFor(Symbol tag) {
    Object r = RT.get(RT.var("clojure.core", "*data-readers*").deref(), tag);
    if (r == null) r = RT.get(RT.var("clojure.core", "default-data-readers").deref(), tag);
    return (IFn) r;
  }

  // ^meta form. Port of LispReader.MetaReader (without source line/column, which RT.readString
  // also omits for a non-line-numbering reader).
  private static final Keyword TAG_KEY = Keyword.intern(null, "tag");
  private static final Keyword PARAM_TAGS_KEY = Keyword.intern(null, "param-tags");

  private Object readMeta() throws IOException {
    Object meta = readForm();
    if (meta instanceof Symbol || meta instanceof String)
      meta = RT.map(TAG_KEY, meta);
    else if (meta instanceof Keyword)
      meta = RT.map(meta, Boolean.TRUE);
    else if (meta instanceof IPersistentVector)
      meta = RT.map(PARAM_TAGS_KEY, meta);
    else if (!(meta instanceof IPersistentMap))
      throw new IllegalArgumentException("Metadata must be Symbol,Keyword,String,Vector or Map");

    Object o = readForm();
    if (!(o instanceof IMeta))
      throw new IllegalArgumentException("Metadata can only be applied to IMetas");
    if (o instanceof IReference) {
      ((IReference) o).resetMeta((IPersistentMap) meta);
      return o;
    }
    IPersistentMap ometa = RT.meta(o);
    for (ISeq s = RT.seq(meta); s != null; s = s.next()) {
      IMapEntry e = (IMapEntry) s.first();
      ometa = (IPersistentMap) RT.assoc(ometa, e.getKey(), e.getValue());
    }
    return ((IObj) o).withMeta(ometa);
  }

  private void skipLine() throws IOException {
    Buffer b = buffer;
    while (true) {
      int c = b.read();
      if (c == -1 || c == '\n' || c == '\r') return;
    }
  }

  // ASCII whitespace (plus comma) lookup for the hot path; matches `ch == ',' ||
  // Character.isWhitespace(ch)` for ch < 128.
  private static final boolean[] WS = new boolean[128];
  static {
    WS[','] = true;
    for (int i = 0; i < 128; i++) if (Character.isWhitespace(i)) WS[i] = true;
  }

  private static boolean isWhitespace(int ch) {
    return ch < 128 ? WS[ch] : Character.isWhitespace(ch);
  }

  private static boolean isMacro(int ch) {
    return ch < 128 && MACRO[ch];
  }

  // Terminating macros are all macros except #, ' and % (matching LispReader): those three
  // may appear inside a token, so they don't terminate one.
  private static boolean isTerminatingMacro(int ch) {
    return ch != '#' && ch != '\'' && ch != '%' && isMacro(ch);
  }

  // Consumes leading whitespace; returns the next non-whitespace char (not consumed),
  // or -1 at end of input. Scans the backing array directly to avoid per-char peek/read.
  private int skipWhitespace() throws IOException {
    Buffer b = buffer;
    while (true) {
      char[] a = b.buffer;
      int p = b.pos, end = b.posEnd;
      while (p < end) {
        char c = a[p];
        if (!isWhitespace(c)) { b.pos = p; return c; }
        p++;
      }
      b.pos = p;
      if (!b.refill()) return -1;
    }
  }

  // Looks ahead `offset` characters past the current position without consuming.
  private int peekAt(int offset) throws IOException {
    Buffer b = buffer;
    while (b.pos + offset >= b.posEnd) {
      if (!b.refill()) return -1;
    }
    return b.buffer[b.pos + offset];
  }

  private Object readNumber() throws IOException {
    Buffer b = buffer;
    b.startNewToken();
    // Scan the token directly over the backing array, stopping at whitespace, a macro
    // character, or end of input (matching LispReader.readNumber's termination).
    int p = b.pos;
    char[] a = b.buffer;
    while (true) {
      char c = a[p];
      if (c == Buffer.SENTINEL && p == b.posEnd) {   // maybe end of buffered input
        b.pos = p;
        if (!b.refill()) { a = b.buffer; p = b.posEnd; break; }  // EOF (refill may have compacted)
        a = b.buffer;                                 // may have grown
        p = b.pos;                                    // may have shifted (compaction)
        continue;
      }
      if (isWhitespace(c) || isMacro(c)) break;
      p++;
    }
    b.pos = p;

    int start = b.getTokenStart();
    int end = p;
    Object n = parseNumber(a, start, end);
    if (n == null) {
      throw new NumberFormatException("Invalid number: " + new String(a, start, end - start));
    }
    return n;
  }

  private Object readToken() throws IOException {
    Buffer b = buffer;
    b.startNewToken();
    // Scan the token directly, stopping at whitespace, a terminating macro, or end of
    // input (matching LispReader.readToken). Note: #, ' and % do NOT terminate a token.
    // Along the way, flag whether the token has a '/' or a non-leading ':' — if not, it is
    // a plain symbol/keyword and can skip the full matchSymbol machinery.
    int ts = b.getTokenStart();
    int p = b.pos;
    char[] a = b.buffer;
    boolean special = false;
    while (true) {
      char c = a[p];
      if (c == Buffer.SENTINEL && p == b.posEnd) {
        b.pos = p;
        if (!b.refill()) { a = b.buffer; ts = b.getTokenStart(); p = b.posEnd; break; }
        a = b.buffer;
        ts = b.getTokenStart();
        p = b.pos;
        continue;
      }
      if (isWhitespace(c) || isTerminatingMacro(c)) break;
      if (c == '/' || (c == ':' && p != ts)) special = true;
      p++;
    }
    b.pos = p;
    return interpretToken(a, ts, p, special);
  }

  // Reads a string form (opening quote already consumed). Port of LispReader.StringReader.
  private Object readStringForm() throws IOException {
    Buffer b = buffer;
    b.startNewToken();
    // Fast path: no escapes. The content stays contiguous in the buffer across refills,
    // so on the closing quote we can slice it out in one shot.
    int p = b.pos;
    char[] a = b.buffer;
    while (true) {
      char c = a[p];
      if (c == Buffer.SENTINEL && p == b.posEnd) {
        b.pos = p;
        if (!b.refill()) throw new RuntimeException("EOF while reading string");
        a = b.buffer;
        p = b.pos;
        continue;
      }
      if (c == '"') {
        String s = new String(a, b.getTokenStart(), p - b.getTokenStart());
        b.pos = p + 1;               // consume closing quote
        return s;
      }
      if (c == '\\') break;          // an escape: switch to the in-place decode path
      p++;
    }
    // In-place decode path (first backslash reached). Each escape collapses into the buffer
    // *behind* the read cursor, so the finished string is still one contiguous slice — no
    // StringBuilder. This reuses the in-place idea from the original Reader.java, but stays
    // O(n): it writes decoded chars into the already-consumed prefix rather than shifting the
    // unscanned tail on every escape (which would be O(n^2)).
    b.pos = p;                                   // position the read cursor at the backslash
    int wOff = p - b.getTokenStart();            // the clean prefix is already in place
    while (true) {
      int ch = b.read();
      if (ch == '"')
        return new String(b.buffer, b.getTokenStart(), wOff);
      if (ch == -1)
        throw new RuntimeException("EOF while reading string");
      if (ch == '\\')
        ch = readStringEscape();
      // Write index (tokenStart + wOff) always trails the read cursor once an escape has
      // shortened the content, so this never clobbers not-yet-read input.
      b.buffer[b.getTokenStart() + wOff] = (char) ch;
      wOff++;
    }
  }

  // Decodes one string escape (backslash already consumed), returning the resulting char.
  private char readStringEscape() throws IOException {
    Buffer b = buffer;
    int ch = b.read();
    if (ch == -1) throw new RuntimeException("EOF while reading string");
    switch (ch) {
      case 't': return '\t';
      case 'r': return '\r';
      case 'n': return '\n';
      case '\\': return '\\';
      case '"': return '"';
      case 'b': return '\b';
      case 'f': return '\f';
      case 'u': {
        int d = b.read();
        if (Character.digit(d, 16) == -1)
          throw new RuntimeException("Invalid unicode escape: \\u" + (char) d);
        return (char) readUnicodeChar(d, 16, 4, true);
      }
      default:
        if (Character.isDigit(ch)) {
          int uc = readUnicodeChar(ch, 8, 3, false);
          if (uc > 0377)
            throw new RuntimeException("Octal escape sequence must be in range [0, 377].");
          return (char) uc;
        }
        throw new RuntimeException("Unsupported escape character: \\" + (char) ch);
    }
  }

  // Port of LispReader.readUnicodeChar(PushbackReader,...): initch already read, reads up to
  // length-1 more base-`base` digits, stopping (and leaving the delimiter) on whitespace/macro/EOF.
  private int readUnicodeChar(int initch, int base, int length, boolean exact) throws IOException {
    Buffer b = buffer;
    int uc = Character.digit(initch, base);
    if (uc == -1) throw new IllegalArgumentException("Invalid digit: " + (char) initch);
    int i = 1;
    for (; i < length; i++) {
      int ch = b.peek();
      if (ch == -1 || isWhitespace(ch) || isMacro(ch)) break;   // leave delimiter unconsumed
      int d = Character.digit(ch, base);
      if (d == -1) throw new IllegalArgumentException("Invalid digit: " + (char) ch);
      b.read();
      uc = uc * base + d;
    }
    if (i != length && exact)
      throw new RuntimeException("Invalid character length: " + i + ", should be: " + length);
    return uc;
  }

  // Reads a character form (backslash already consumed). Port of LispReader.CharacterReader.
  private Object readCharacterForm() throws IOException {
    Buffer b = buffer;
    b.startNewToken();
    if (b.peek() == -1) throw new RuntimeException("EOF while reading character");
    // The first char after '\' is always part of the token, even a macro or whitespace char.
    int p = b.pos + 1;
    char[] a = b.buffer;
    while (true) {
      char c = a[p];
      if (c == Buffer.SENTINEL && p == b.posEnd) {
        b.pos = p;
        if (!b.refill()) { a = b.buffer; p = b.posEnd; break; }  // EOF (refill may have compacted)
        a = b.buffer;
        p = b.pos;
        continue;
      }
      if (isWhitespace(c) || isTerminatingMacro(c)) break;
      p++;
    }
    b.pos = p;
    return interpretCharacter(new String(a, b.getTokenStart(), p - b.getTokenStart()));
  }

  private static Object interpretCharacter(String token) {
    int len = token.length();
    if (len == 1) return token.charAt(0);
    switch (token) {
      case "newline": return '\n';
      case "space": return ' ';
      case "tab": return '\t';
      case "backspace": return '\b';
      case "formfeed": return '\f';
      case "return": return '\r';
      default:
        char c0 = token.charAt(0);
        if (c0 == 'u') {
          char c = (char) readUnicodeChar(token, 1, 4, 16);
          if (c >= '\ud800' && c <= '\udfff')
            throw new RuntimeException("Invalid character constant: \\u" + Integer.toString(c, 16));
          return c;
        }
        if (c0 == 'o') {
          int n = len - 1;
          if (n > 3)
            throw new RuntimeException("Invalid octal escape sequence length: " + n);
          int uc = readUnicodeChar(token, 1, n, 8);
          if (uc > 0377)
            throw new RuntimeException("Octal escape sequence must be in range [0, 377].");
          return (char) uc;
        }
        throw new RuntimeException("Unsupported character: \\" + token);
    }
  }

  // Port of LispReader.readUnicodeChar(String, offset, length, base).
  private static int readUnicodeChar(String token, int offset, int length, int base) {
    if (token.length() != offset + length)
      throw new IllegalArgumentException("Invalid unicode character: \\" + token);
    int uc = 0;
    for (int i = offset; i < offset + length; i++) {
      int d = Character.digit(token.charAt(i), base);
      if (d == -1)
        throw new IllegalArgumentException("Invalid digit: " + token.charAt(i));
      uc = uc * base + d;
    }
    return uc;
  }

  // nil / true / false, then symbol/keyword. `special` is true when the token contains a '/'
  // or a non-leading ':', which are the only cases needing the full matchSymbol logic; the
  // common plain symbol/keyword goes straight to Symbol.intern / Keyword.intern.
  private Object interpretToken(char[] a, int start, int end, boolean special) {
    int len = end - start;
    if (!special) {
      char c0 = a[start];
      if (c0 == ':') {
        if (len >= 2)   // plain keyword :name
          return internPlain(a, start, len, true);
        // ":" alone falls through to the full path (invalid token)
      } else {
        if (len == 3 && c0 == 'n' && a[start + 1] == 'i' && a[start + 2] == 'l')
          return null;
        if (len == 4 && c0 == 't' && a[start + 1] == 'r' && a[start + 2] == 'u' && a[start + 3] == 'e')
          return Boolean.TRUE;
        if (len == 5 && c0 == 'f' && a[start + 1] == 'a' && a[start + 2] == 'l'
            && a[start + 3] == 's' && a[start + 4] == 'e')
          return Boolean.FALSE;
        return internPlain(a, start, len, false);   // plain symbol
      }
    }
    String s = new String(a, start, len);
    Object ret = matchSymbol(s);
    if (ret != null) return ret;
    throw new RuntimeException("Invalid token: " + s);
  }

  // Per-reader cache from a token's char range to its interned Symbol/Keyword, so a repeated
  // plain token (extremely common in real code) skips the String allocation and the
  // Symbol/Keyword intern. Lazily allocated; a hash collision just recomputes (still correct).
  // Keyed on the whole range including any leading ':', so symbols and keywords never collide.
  private char[][] tokKey;
  private Object[] tokVal;
  private int tokSeen;
  private static final int TOK_MASK = 1023;
  private static final int TOK_CACHE_AFTER = 32;   // don't allocate the cache for small reads

  private Object internPlain(char[] a, int start, int len, boolean keyword) {
    char[][] keys = tokKey;
    if (keys == null) {
      if (++tokSeen <= TOK_CACHE_AFTER)
        return keyword ? Keyword.intern(Symbol.intern(new String(a, start + 1, len - 1)))
                       : Symbol.intern(new String(a, start, len));
      keys = tokKey = new char[TOK_MASK + 1][];
      tokVal = new Object[TOK_MASK + 1];
    }
    int h = 0;
    for (int i = 0; i < len; i++) h = h * 31 + a[start + i];
    int idx = h & TOK_MASK;
    char[] k = keys[idx];
    if (k != null && k.length == len) {
      int i = 0;
      while (i < len && k[i] == a[start + i]) i++;
      if (i == len) return tokVal[idx];
    }
    Object v = keyword
        ? Keyword.intern(Symbol.intern(new String(a, start + 1, len - 1)))
        : Symbol.intern(new String(a, start, len));
    keys[idx] = java.util.Arrays.copyOfRange(a, start, start + len);
    tokVal[idx] = v;
    return v;
  }

  // Hand-rolled equivalent of LispReader.matchSymbol (Clojure 1.12.5), avoiding a regex on
  // this hot path. Reproduces, without allocating a Matcher:
  //   symbolPat      = [:]?([\D&&[^/]].*/)?(/|[\D&&[^/]][^/]*)
  //   arraySymbolPat = ([\D&&[^/:]].*)/([1-9])
  // plus the ::-autoresolve and validity checks. See symMatch for the greedy semantics.
  private static final int SYM_NO_MATCH = -1;
  private static final int SYM_NS_PRESENT = 1;      // group1 (namespace) present
  private static final int SYM_NS_COLON_SLASH = 2;  // group1 ends with ":/"

  private static Object matchSymbol(String s) {
    int n = s.length();
    // symbolPat's leading [:]? is greedy: try consuming a leading ':' first, then not.
    int r = (s.charAt(0) == ':') ? symMatch(s, 1) : SYM_NO_MATCH;
    if (r == SYM_NO_MATCH) r = symMatch(s, 0);

    if (r != SYM_NO_MATCH) {
      boolean nsPresent = (r & SYM_NS_PRESENT) != 0;
      boolean nsEndsColonSlash = (r & SYM_NS_COLON_SLASH) != 0;
      // (ns endsWith ":/") || (name endsWith ":") || (s contains "::" at index >= 1)
      if ((nsPresent && nsEndsColonSlash)
          || s.charAt(n - 1) == ':'
          || s.indexOf("::", 1) != -1)
        return null;
      if (n >= 2 && s.charAt(0) == ':' && s.charAt(1) == ':') {
        // ::-autoresolve, matching LispReader's null-Resolver path (as used by RT.readString):
        // ::ns/name resolves ns as an ALIAS of the current namespace only (no Namespace/find);
        // ::name resolves against the current namespace itself.
        Symbol ks = Symbol.intern(s.substring(2));
        Namespace kns;
        if (ks.getNamespace() != null)
          kns = currentNS().lookupAlias(Symbol.intern(ks.getNamespace()));
        else
          kns = currentNS();
        if (kns != null)
          return Keyword.intern(kns.name.getName(), ks.getName());
        return null;
      }
      boolean isKeyword = s.charAt(0) == ':';
      Symbol sym = Symbol.intern(isKeyword ? s.substring(1) : s);
      return isKeyword ? Keyword.intern(sym) : sym;
    }

    // arraySymbolPat: ([\D&&[^/:]].*)/([1-9]) — ns/N with N a single 1-9. The '/' and digit
    // are the last two chars; the first char is non-digit, non-'/', non-':'.
    if (n >= 3) {
      char last = s.charAt(n - 1);
      char c0 = s.charAt(0);
      if (last >= '1' && last <= '9' && s.charAt(n - 2) == '/'
          && !isDigit(c0) && c0 != '/' && c0 != ':')
        return Symbol.intern(s.substring(0, n - 2), s.substring(n - 1));
    }
    return null;
  }

  // Simulates symbolPat matching ([\D&&[^/]].*/)?(/|[\D&&[^/]][^/]*) against s[p0..], with the
  // regex's greedy .* (so group1, the namespace, extends to the LAST '/'). Returns SYM_NO_MATCH,
  // or SYM_NS_PRESENT/SYM_NS_COLON_SLASH flags (0 == matched with no namespace). Note \D means
  // "not [0-9]"; the token's first char is never a digit (the reader dispatches those to numbers).
  private static int symMatch(String s, int p0) {
    int n = s.length();
    if (p0 >= n) return SYM_NO_MATCH;                 // group2 needs at least one char
    char first = s.charAt(p0);
    int lastSlash = s.lastIndexOf('/');
    if (lastSlash < p0) lastSlash = -1;               // no '/' in s[p0..]

    if (lastSlash < 0) {                              // no namespace: group2 = s[p0..]
      return isDigit(first) ? SYM_NO_MATCH : 0;       // group2's first char must be non-digit
    }

    // Namespace present: group1 = [\D&&[^/]].*/ (first char non-digit, non-'/').
    if (first != '/' && !isDigit(first)) {
      // Greedy: group1 ends at the last '/', so group2 = s[lastSlash+1 ..].
      if (lastSlash + 1 < n && !isDigit(s.charAt(lastSlash + 1))) {
        int flags = SYM_NS_PRESENT;
        if (lastSlash - 1 >= p0 && s.charAt(lastSlash - 1) == ':')
          flags |= SYM_NS_COLON_SLASH;                // group1 ends with ":/"
        return flags;
      }
      // group2 invalid at the last '/'. The only productive backtrack is group2 = "/" (a
      // single trailing slash), which needs the token to end with "//".
      if (s.charAt(n - 1) == '/' && n - 2 >= p0 && s.charAt(n - 2) == '/') {
        int flags = SYM_NS_PRESENT;
        if (n - 3 >= p0 && s.charAt(n - 3) == ':')
          flags |= SYM_NS_COLON_SLASH;
        return flags;
      }
    }
    // No namespace: group2 can only be a lone "/".
    if (n - p0 == 1 && first == '/') return 0;
    return SYM_NO_MATCH;
  }

  private static Namespace currentNS() {
    return (Namespace) RT.var("clojure.core", "*ns*").deref();
  }

  // Classifies and parses a number token directly, without regex, for the common
  // forms (decimal long, hex, octal, double). Delegates to matchNumber only for the
  // rarer tails: radix (NrDDD), ratios, N/M suffixes, over-long magnitudes, and
  // invalid tokens. Reproduces LispReader's pattern precedence exactly — in particular
  // that intPat is tried before floatPat, so a leading-zero integer ("08") is invalid
  // while a leading-zero float ("08.5") is a double.
  private static Object parseNumber(char[] a, int start, int end) {
    int i = start;
    boolean neg = false;
    char c0 = a[i];
    if (c0 == '+' || c0 == '-') { neg = (c0 == '-'); i++; }
    if (i >= end) return matchNumber(str(a, start, end));  // sign only (shouldn't happen)
    int bodyStart = i;
    char b0 = a[bodyStart];

    // A lone zero (with optional sign): 0, +0, -0.
    if (b0 == '0' && bodyStart + 1 == end) return 0L;

    if (b0 == '0') {
      // Hex: 0[xX][0-9A-Fa-f]+
      char b1 = a[bodyStart + 1];
      if (b1 == 'x' || b1 == 'X') {
        Long mag = parseMag(a, bodyStart + 2, end, 16);
        if (mag != null) return neg ? -mag : (long) mag;
        return matchNumber(str(a, start, end));            // empty/invalid/overflow/N
      }
      // A float can start with 0 ("0.5", "08.5", "0e3"); a plain integer starting with
      // 0 is octal ("0777") or invalid ("08"). Distinguish by the char after the digits.
      int j = bodyStart;
      while (j < end && isDigit(a[j])) j++;
      if (j < end && (a[j] == '.' || a[j] == 'e' || a[j] == 'E')) {
        if (isFloat(a, bodyStart, end)) return Double.parseDouble(str(a, start, end));
        return matchNumber(str(a, start, end));            // e.g. "1.5M", "1e"
      }
      Long mag = parseMag(a, bodyStart + 1, end, 8);        // octal 0[0-7]+
      if (mag != null) return neg ? -mag : (long) mag;
      return matchNumber(str(a, start, end));              // "08", "0N", "0/5", ...
    }

    // Leading digit 1-9: decimal integer, or a double, or a rarer form.
    int j = bodyStart;
    while (j < end && isDigit(a[j])) j++;
    if (j == end) {
      Long mag = parseMag(a, bodyStart, end, 10);           // [1-9][0-9]*
      if (mag != null) return neg ? -mag : (long) mag;
      return matchNumber(str(a, start, end));              // overflow -> BigInt
    }
    char cj = a[j];
    if (cj == '.' || cj == 'e' || cj == 'E') {
      if (isFloat(a, bodyStart, end)) return Double.parseDouble(str(a, start, end));
    }
    return matchNumber(str(a, start, end));                // radix, ratio, N, M, invalid
  }

  private static String str(char[] a, int start, int end) {
    return new String(a, start, end - start);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static int digitVal(char c, int base) {
    int d;
    if (c >= '0' && c <= '9') d = c - '0';
    else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
    else return -1;
    return d < base ? d : -1;
  }

  // Parses [i,end) as an unsigned magnitude in the given base (<= 16). Returns null if
  // any character is not a valid digit, the run is empty, or the value overflows a long
  // (in which case the caller falls back to matchNumber's BigInteger handling). A magnitude
  // that fits a long always has bitLength < 64, so it maps to Long just like Clojure does.
  private static Long parseMag(char[] a, int i, int end, int base) {
    if (i >= end) return null;
    long val = 0;
    for (; i < end; i++) {
      int d = digitVal(a[i], base);
      if (d < 0) return null;
      if (val > (Long.MAX_VALUE - d) / base) return null;  // overflow -> BigInt path
      val = val * base + d;
    }
    return val;
  }

  // True iff [i,end) matches floatPat's magnitude grammar: [0-9]+(\.[0-9]*)?([eE][-+]?[0-9]+)?
  // Only called once a '.' or exponent is known present, so it never accepts a bare integer
  // (which intPat would have claimed first).
  private static boolean isFloat(char[] a, int i, int end) {
    int ds = i;
    while (i < end && isDigit(a[i])) i++;
    if (i == ds) return false;                 // need at least one leading digit
    if (i < end && a[i] == '.') {
      i++;
      while (i < end && isDigit(a[i])) i++;
    }
    if (i < end && (a[i] == 'e' || a[i] == 'E')) {
      i++;
      if (i < end && (a[i] == '+' || a[i] == '-')) i++;
      int es = i;
      while (i < end && isDigit(a[i])) i++;
      if (i == es) return false;               // exponent needs at least one digit
    }
    return i == end;
  }

  // --- Faithful port of clojure.lang.LispReader.matchNumber (Clojure 1.12.5) ---

  private static final Pattern intPat =
      Pattern.compile("([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?");
  private static final Pattern ratioPat = Pattern.compile("([-+]?[0-9]+)/([0-9]+)");
  private static final Pattern floatPat = Pattern.compile("([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?");

  private static Object matchNumber(String s) {
    Matcher m = intPat.matcher(s);
    if (m.matches()) {
      if (m.group(2) != null) {
        if (m.group(8) != null)
          return BigInt.ZERO;
        return Numbers.num(0);
      }
      boolean negate = m.group(1).equals("-");
      String n;
      int radix = 10;
      if ((n = m.group(3)) != null)
        radix = 10;
      else if ((n = m.group(4)) != null)
        radix = 16;
      else if ((n = m.group(5)) != null)
        radix = 8;
      else if ((n = m.group(7)) != null)
        radix = Integer.parseInt(m.group(6));
      if (n == null)
        return null;
      BigInteger bn = new BigInteger(n, radix);
      if (negate)
        bn = bn.negate();
      if (m.group(8) != null)
        return BigInt.fromBigInteger(bn);
      return bn.bitLength() < 64 ? Numbers.num(bn.longValue()) : BigInt.fromBigInteger(bn);
    }
    m = floatPat.matcher(s);
    if (m.matches()) {
      if (m.group(4) != null)
        return new BigDecimal(m.group(1));
      return Double.parseDouble(s);
    }
    m = ratioPat.matcher(s);
    if (m.matches()) {
      String numerator = m.group(1);
      if (numerator.startsWith("+"))
        numerator = numerator.substring(1);
      return Numbers.divide(
          Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(numerator))),
          Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(m.group(2)))));
    }
    return null;
  }
}
