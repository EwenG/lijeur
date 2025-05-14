package lijeur;

import clojure.lang.IPersistentVector;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class Parser {

  public static Object parseEdn(String input) throws IOException {
    return parse(new Buffer(new StringReader(input), 8192));
  }

  private static Object parse(Buffer r) throws IOException {
    r.skipWhitespace();
    char c = r.peek();
    switch (c) {
      case '[':
        return parseVector(r);
      case '"':
        return parseString(r);
      case ':':
        return parseKeyword(r);
      default:
        if (Character.isDigit(c) || c == '-' || c == '+') {
          return parseNumber(r);
        } else {
          return parseSymbol(r);
        }
    }
  }

  private static IPersistentVector parseVector(Buffer r) throws IOException {
    List<Object> list = new ArrayList<>();
    r.expect('[');
    r.skipWhitespace();
    while (r.peek() != ']') {
      list.add(parse(r));
      r.skipWhitespace();
    }
    r.expect(']');
    return PersistentVector.create(list);
  }

  private static String parseString(Buffer r) throws IOException {
    r.expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = r.next();
      if (c == '"') break;
      if (c == '\\') {
        c = r.next();
        switch (c) {
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'r': sb.append('\r'); break;
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          default: sb.append(c); break;
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static Keyword parseKeyword(Buffer r) throws IOException {
    r.expect(':');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = r.peek();
      if (Character.isWhitespace(c) || c == ']' || c == '}') break;
      sb.append(r.next());
    }
    String s = sb.toString();
    int slashIndex = s.indexOf('/');
    if (slashIndex > 0 && slashIndex < s.length() - 1) {
      String ns = s.substring(0, slashIndex);
      String name = s.substring(slashIndex + 1);
      return Keyword.intern(ns, name);
    } else {
      return Keyword.intern(s);
    }
  }

  private static Object parseSymbol(Buffer r) throws IOException {
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = r.peek();
      if (Character.isWhitespace(c) || c == ']' || c == '}') break;
      sb.append(r.next());
    }
    return sb.toString();
  }

  private static Object parseNumber(Buffer r) throws IOException {
    boolean isNegative = false;
    boolean isFloating = false;
    long value = 0;
    double dvalue = 0.0;
    int scale = 0;

    char c = r.peek();
    if (c == '-') {
      isNegative = true;
      r.next();
      c = r.peek();
    } else if (c == '+') {
      r.next();
      c = r.peek();
    }

    while (Character.isDigit(c)) {
      int digit = r.next() - '0';
      value = value * 10 + digit;
      dvalue = dvalue * 10 + digit;
      if (isFloating) scale++;
      c = r.peek();
    }

    if (c == '.') {
      isFloating = true;
      r.next(); // consume '.'
      c = r.peek();
      while (Character.isDigit(c)) {
        int digit = r.next() - '0';
        dvalue = dvalue * 10 + digit;
        scale++;
        c = r.peek();
      }
      dvalue = dvalue / Math.pow(10, scale);
    }

    if (isFloating) {
      return isNegative ? -dvalue : dvalue;
    } else {
      return isNegative ? -value : value;
    }
  }


  private static class Buffer {
    private final Reader reader;
    private final char[] buf;
    private int pos = 0;
    private int limit = 0;

    Buffer(Reader reader, int size) {
      this.reader = reader;
      this.buf = new char[size];
    }

    char peek() throws IOException {
      if (pos >= limit) {
        limit = reader.read(buf);
        pos = 0;
        if (limit == -1) return (char) -1;
      }
      return buf[pos];
    }

    char next() throws IOException {
      char c = peek();
      pos++;
      return c;
    }

    void expect(char expected) throws IOException {
      char c = next();
      if (c != expected) {
        throw new RuntimeException("Expected '" + expected + "', got '" + c + "'");
      }
    }

    void skipWhitespace() throws IOException {
      while (true) {
        char c = peek();
        if (Character.isWhitespace(c)) {
          pos++;
        } else {
          break;
        }
      }
    }
  }
}
