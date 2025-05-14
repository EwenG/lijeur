package lijeur;

import java.io.Reader;
import java.io.StringReader;
import java.io.IOException;
import java.util.*;

public class Parser {

  private static class Buffer {
    private final char[] buf;
    private final Reader reader;
    private int pos = 0;
    private int limit = 0;

    Buffer(Reader reader, int size) throws IOException {
      this.buf = new char[size];
      this.reader = reader;
      fill();
    }

    private void fill() throws IOException {
      limit = reader.read(buf);
      pos = 0;
    }

    char peek() throws IOException {
      if (pos >= limit) fill();
      return pos < limit ? buf[pos] : (char) -1;
    }

    char next() throws IOException {
      if (pos >= limit) fill();
      return pos < limit ? buf[pos++] : (char) -1;
    }

    void skipWhitespace() throws IOException {
      while (Character.isWhitespace(peek())) next();
    }
  }

  private static final Map<String, String> INTERNED_KEYWORDS = new HashMap<>();

  private static String internKeyword(String kw) {
    return INTERNED_KEYWORDS.computeIfAbsent(kw, k -> k);
  }

  public static Object parseEdn(String input) throws IOException {
    return parse(new Buffer(new StringReader(input), 8192));
  }

  private static Object parse(Buffer r) throws IOException {
    r.skipWhitespace();
    char c = r.peek();

    if (c == (char) -1) return null;

    switch (c) {
      case 'n': return parseNil(r);
      case 't':
      case 'f': return parseBoolean(r);
      case '"': return parseString(r);
      case '[': return parseVector(r);
      case '(': return parseList(r);
      case '{': return parseMap(r);
      case '#': return parseDispatch(r);
      default:
        if (Character.isDigit(c) || c == '-') {
          return parseNumber(r);
        }
        return parseSymbol(r);
    }
  }

  private static Object parseNil(Buffer r) throws IOException {
    r.next(); r.next(); r.next();
    return null;
  }

  private static Boolean parseBoolean(Buffer r) throws IOException {
    char first = r.next();
    if (first == 't') {
      r.next(); r.next(); r.next();
      return true;
    } else {
      r.next(); r.next(); r.next(); r.next();
      return false;
    }
  }

  private static Number parseNumber(Buffer r) throws IOException {
    StringBuilder sb = new StringBuilder();
    char c = r.peek();
    while (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
      sb.append(r.next());
      c = r.peek();
    }
    String s = sb.toString();
    try {
      if (s.contains(".")) {
        return Double.parseDouble(s);
      } else {
        return Long.parseLong(s);
      }
    } catch (NumberFormatException e) {
      throw new RuntimeException("Invalid number: " + s);
    }
  }

  private static String parseString(Buffer r) throws IOException {
    r.next();
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = r.next();
      if (c == (char) -1) throw new RuntimeException("EOF in string");
      if (c == '"') return sb.toString();
      if (c == '\\') {
        char esc = r.next();
        switch (esc) {
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'r': sb.append('\r'); break;
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          default: sb.append(esc);
        }
      } else {
        sb.append(c);
      }
    }
  }

  private static String parseSymbol(Buffer r) throws IOException {
    StringBuilder sb = new StringBuilder();
    char c = r.peek();
    while (!Character.isWhitespace(c) && "()[]{}".indexOf(c) == -1 && c != (char) -1) {
      sb.append(r.next());
      c = r.peek();
    }
    String s = sb.toString();
    if (s.startsWith(":")) return internKeyword(s);
    switch (s) {
      case "nil": return null;
      case "true": return "true";
      case "false": return "false";
      default: return s;
    }
  }

  private static List<Object> parseVector(Buffer r) throws IOException {
    r.next();
    List<Object> list = new ArrayList<>();
    while (true) {
      r.skipWhitespace();
      char c = r.peek();
      if (c == ']') {
        r.next();
        return list;
      }
      list.add(parse(r));
    }
  }

  private static List<Object> parseList(Buffer r) throws IOException {
    r.next();
    List<Object> list = new ArrayList<>();
    while (true) {
      r.skipWhitespace();
      char c = r.peek();
      if (c == ')') {
        r.next();
        return list;
      }
      list.add(parse(r));
    }
  }

  private static Map<Object, Object> parseMap(Buffer r) throws IOException {
    r.next();
    Map<Object, Object> map = new HashMap<>();
    while (true) {
      r.skipWhitespace();
      char c = r.peek();
      if (c == '}') {
        r.next();
        return map;
      }
      Object k = parse(r);
      r.skipWhitespace();
      Object v = parse(r);
      map.put(k, v);
    }
  }

  private static Set<Object> parseSet(Buffer r) throws IOException {
    r.next(); // skip #
    if (r.next() != '{') throw new RuntimeException("Expected set literal");
    Set<Object> set = new HashSet<>();
    while (true) {
      r.skipWhitespace();
      char c = r.peek();
      if (c == '}') {
        r.next();
        return set;
      }
      set.add(parse(r));
    }
  }

  private static Object parseDispatch(Buffer r) throws IOException {
    r.next();
    char tag = r.peek();
    if (tag == '{') return parseSet(r);
    StringBuilder tagBuf = new StringBuilder();
    while (!Character.isWhitespace(tag) && tag != (char) -1) {
      tagBuf.append(r.next());
      tag = r.peek();
    }
    Object value = parse(r);
    return Map.of("tag", tagBuf.toString(), "value", value);
  }
}
