package lijeur;

import clojure.lang.*;
import clojure.lang.Compiler;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class Reader {

  private final Buffer buffer;

  public static final int DEFAULT_READ_CHUNK_SIZE = 4096;
  // A safe upper bound in base 10 (so also in base 8)
  // ie the result of the equation: val * 10 + 9 <= Long.MAX_VALUE
  public static final long LONG_LIMIT = (Long.MAX_VALUE - 9) / 10;
  public static final long LONG_LIMIT_RADIX = (Long.MAX_VALUE - 35) / 36;
  public static final long MAX_DECIMAL_COUNT = 15;

  public static final Keyword LINE_KEY = Keyword.intern(Symbol.intern(null, "line"));
  public static final Keyword COLUMN_KEY = Keyword.intern(Symbol.intern(null, "column"));
  public static final Keyword END_LINE_KEY = Keyword.intern(Symbol.intern(null, "end-line"));
  public static final Keyword END_COLUMN_KEY = Keyword.intern(Symbol.intern(null, "end-column"));

  public static final Symbol QUOTE_SYM = Symbol.intern(null, "quote");

  private final boolean throwOnEOF;
  private final Object eofValue;
  private final String fileName;

  private static final Character delimList = ')';
  public static final BitSet macroMask = new BitSet(0x80);

  static {
    macroMask.set('"');
    macroMask.set(':');
    macroMask.set(';');
    macroMask.set('\'');
    macroMask.set('@');
    macroMask.set('^');
    macroMask.set('`');
    macroMask.set('~');
    macroMask.set('(');
    macroMask.set(')');
    macroMask.set('[');
    macroMask.set(']');
    macroMask.set('{');
    macroMask.set('}');
    macroMask.set('\\');
    macroMask.set('%');
    macroMask.set('#');
  }

  public static final BitSet macroTerminatingMask = new BitSet(0x80);

  static {
    macroTerminatingMask.set('"');
    macroTerminatingMask.set(';');
    macroTerminatingMask.set('@');
    macroTerminatingMask.set('^');
    macroTerminatingMask.set('`');
    macroTerminatingMask.set('~');
    macroTerminatingMask.set('(');
    macroTerminatingMask.set(')');
    macroTerminatingMask.set('[');
    macroTerminatingMask.set(']');
    macroTerminatingMask.set('{');
    macroTerminatingMask.set('}');
    macroTerminatingMask.set('\'');
  }

  private static final ThreadLocal<IPersistentMap> gensymEnv = new ThreadLocal<>();

  public Reader(java.io.Reader r, int readChunkSize, boolean throwOnEOF, Object eofValue, boolean countLines, String fileName) {
    this.buffer = new Buffer(r, readChunkSize, countLines);
    this.throwOnEOF = throwOnEOF;
    this.eofValue = eofValue;
    this.fileName = fileName;
  }

  public Reader(java.io.Reader r, int readChunkSize, boolean throwOnEOF, Object eofValue, boolean countLines) {
    this(r, readChunkSize, throwOnEOF, eofValue, countLines, null);
  }

  public Reader(java.io.Reader r, boolean throwOnEOF, Object eofValue, boolean countLines) {
    this(r, DEFAULT_READ_CHUNK_SIZE, throwOnEOF, eofValue, countLines);
  }

  public Reader(java.io.Reader r, boolean throwOnEOF, Object eofValue) {
    this(r, throwOnEOF, eofValue, false);
  }

  public Reader(java.io.Reader r, boolean throwOnEOF) {
    this(r, throwOnEOF, null, false);
  }

  public Reader(java.io.Reader r) {
    this(r, true, null, false);
  }

  public static boolean isWhitespace(int ch) {
    return (Character.isWhitespace(ch) || ch == ',');
  }

  public static boolean isMacro(int ch) {
    return ch > -1 && ch < 0x80 && macroMask.get(ch);
  }

  public static boolean isMacroTerminating(int ch) {
    return ch > -1 && ch < 0x80 && macroTerminatingMask.get(ch);
  }

  public static boolean isDigit(int ch) {
    return ch >= '0' && ch <= '9';
  }

  public void skipWhitespace() throws IOException {
    while(true) {
      int c = buffer.read();
      if(!isWhitespace(c)) {
        buffer.unread();
        break;
      }
    }
  }

  public void skipLine() throws IOException {
    while(true) {
      int c = buffer.read();
      if(c == '\n' || c == '\r') {
        break;
      }
    }
  }

  public void readInvalidNumber() throws IOException {
    while (true) {
      int ch = buffer.read();
      if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
      }
    }
  }

  public Number readNumberExponential() throws IOException {
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '9') {
        // Continue
      } else if (ch == 'M') {
        return readNumberBigDecimal();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        return Double.parseDouble(buffer.getTokenString());
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readRatio() throws IOException {
    char[] token = buffer.getToken();
    String numerator = new String(token, buffer.getTokenStart(), buffer.getTokenEnd() - buffer.getTokenStart() - 1);
    int denominatorStart = buffer.getTokenEnd();
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '9') {
        // Continue reading
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        if(buffer.getTokenEnd() == denominatorStart) {
          readInvalidNumber();
        }
        char[] denominatorToken = buffer.getToken();
        String denominator = new String(denominatorToken, denominatorStart, buffer.getTokenEnd() - denominatorStart);
        return Numbers.divide(
            Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(numerator))),
            Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(denominator)))
        );
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readNumberBigDecimal() throws IOException {
    int ch = buffer.read();
    if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
      buffer.unread();
      char[] tokenChars = buffer.getToken();
      return new BigDecimal(tokenChars, buffer.getTokenStart(), buffer.getTokenEnd() - buffer.getTokenStart() - 1);
    } else {
      readInvalidNumber();
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public Number readNumberBigInt(boolean isOctal) throws IOException {
    int ch = buffer.read();
    if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
      buffer.unread();
      char[] tokenChars = buffer.getToken();
      String tokenString = new String(tokenChars, buffer.getTokenStart(), buffer.getTokenEnd() - buffer.getTokenStart() - 1);
      if(isOctal) {
        return BigInt.fromBigInteger(new BigInteger(tokenString, 8));
      } else {
        return BigInt.fromBigInteger(new BigInteger(tokenString));
      }
    } else {
      readInvalidNumber();
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public Number readNumberBigIntRadix() throws IOException {
    int ch = buffer.read();
    if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
      buffer.unread();
      char[] tokenChars = buffer.getToken();
      int tokenStart = buffer.getTokenStart() + 2;
      return BigInt.fromBigInteger(new BigInteger(new String(tokenChars, tokenStart, buffer.getTokenEnd() - tokenStart - 1), 16));
    } else {
      readInvalidNumber();
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public Number readNumberOverflow(boolean isOctal, boolean isFloatingPoint) throws IOException {
    boolean isFloatingPointNumber = isFloatingPoint;
    boolean isExponential = false;
    boolean isValidOctal = true;
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '7') {
        // Continue
      } else if(ch >= '8' && ch <= '9') {
        isValidOctal = false;
      } else if (ch == '.') {
        if(isFloatingPointNumber) {
          readInvalidNumber();
        }
        isFloatingPointNumber = true;
      } else if (ch == 'e'  ||ch == 'E') {
        if(isExponential) {
          readInvalidNumber();
        }
        isExponential = true;
      } else if(ch == '/') {
        if(isFloatingPointNumber || isExponential) {
          readInvalidNumber();
        }
        return readRatio();
      } else if(ch == 'N') {
        if(isFloatingPointNumber || isExponential) {
          readInvalidNumber();
        } else if(!isOctal) {
          return readNumberBigInt(false);
        } else if(isValidOctal) {
          return readNumberBigInt(true);
        } else {
          throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
        }
      } else if(ch == 'M') {
        return readNumberBigDecimal();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        if(isFloatingPointNumber || isExponential) {
          return Double.parseDouble(buffer.getTokenString());
        } else if(!isOctal) {
          BigInteger bi = new BigInteger(buffer.getTokenString());
          if(bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
              && bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0){
            return bi.longValue();
          } else {
            return BigInt.fromBigInteger(bi);
          }
        } else if(isValidOctal) {
          BigInteger bi = new BigInteger(buffer.getTokenString(), 8);
          if(bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
              && bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0) {
            return bi.longValue();
          } else {
            return BigInt.fromBigInteger(bi);
          }
        } else {
          throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
        }
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readNumberOverflowRadix(boolean negative, int radix, int radixLength) throws IOException {
    while (true) {
      int ch = buffer.read();
      int charInt = -1;
      if (ch >= '0' && ch <= '9') {
        charInt = ch - '0';
      } else if (ch >= 'a' && ch <= 'z') {
        charInt = ch - 'a' + 10;
      } else if (ch >= 'A' && ch <= 'Z') {
        charInt = ch - 'A' + 10;
      }
      if(charInt > 0 && charInt < radix) {
        // Continue
      } else if (ch == 'N' &&
          radixLength == 1 &&
          (buffer.getToken()[radixLength] == 'x' ||
              buffer.getToken()[radixLength] == 'X')
      ) {
        return readNumberBigIntRadix();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        char[] bufferToken = buffer.getToken();
        int tokenStart = buffer.getTokenStart() + radixLength + 1;
        String tokenString = new String(bufferToken, tokenStart, buffer.getTokenEnd() - tokenStart);
        BigInteger bi = new BigInteger(tokenString, radix);
        if(negative) {
          bi = bi.negate();
        }
        if(bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
            && bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0) {
          return bi.longValue();
        } else {
          return BigInt.fromBigInteger(bi);
        }
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readNumberRadix(boolean negative, int radix, int radixLength) throws IOException {
    if(radix >= Character.MIN_RADIX && radix <= Character.MAX_RADIX) {
      long val = 0;
      while (true) {
        int ch = buffer.read();
        int charInt = -1;
        if (ch >= '0' && ch <= '9') {
          charInt = ch - '0';
        } else if (ch >= 'a' && ch <= 'z') {
          charInt = ch - 'a' + 10;
        } else if (ch >= 'A' && ch <= 'Z') {
          charInt = ch - 'A' + 10;
        }
        if(charInt >= 0 && charInt < radix) {
          if(val > LONG_LIMIT_RADIX) {
            return readNumberOverflowRadix(negative, radix, radixLength);
          }
          val = val * radix + charInt;
        } else if (ch == 'N' &&
            radixLength == 1 &&
            (buffer.getToken()[radixLength] == 'x' ||
                buffer.getToken()[radixLength] == 'X')
        ) {
          return readNumberBigIntRadix();
        } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
          if(negative) {
            return -val;
          } else {
            return val;
          }
        } else {
          readInvalidNumber();
        }
      }
    } else {
      readInvalidNumber();
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public Number readNumberRadixCh1(boolean negative, int ch1) throws IOException {
    if(ch1 >= '2' && ch1 <= '9') {
      return readNumberRadix(negative, ch1 - '0', negative ? 2 : 1);
    } else {
      readInvalidNumber();
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public Number readNumberRadixCh2(boolean negative, int ch1, int ch2) throws IOException {
    if(ch1 >= '0' && ch1 <= '9' && ch2 >= '0' && ch2 <= '9') {
      return readNumberRadix(negative,(ch1 - '0') * 10 + (ch2 - '0'), negative ? 3 : 2);
    } else {
      readInvalidNumber();
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public Number readNumberFloatingPoint(boolean negative, long val) throws IOException {
    int decimalCount = 0;
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '9') {
        decimalCount++;
        if(val > LONG_LIMIT || decimalCount > MAX_DECIMAL_COUNT) {
          return readNumberOverflow(false, true);
        }
        val = val * 10 + ch - '0';
      } else if (ch == 'e' || ch == 'E') {
        return readNumberExponential();
      } else if (ch == 'M') {
        return readNumberBigDecimal();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        if (negative) {
          return -(val / Math.pow(10, decimalCount));
        } else {
          return val / Math.pow(10, decimalCount);
        }
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readNumberOctal(boolean negative) throws IOException {
    long val10 = 0;
    long val8 = 0;
    boolean isValidOctal = true;
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '7') {
        if(val10 > LONG_LIMIT && isValidOctal) {
          return readNumberOverflow(true, false);
        }
        val10 = val10 * 10 + ch - '0';
        val8 = val8 * 8 + ch - '0';
      } else if(ch >= '8' && ch <= '9') {
        isValidOctal = false;
        val10 = val10 * 10 + ch - '0';
        val8 = val8 * 8 + ch - '0';
      } else if (ch == '.') {
        return readNumberFloatingPoint(negative, val10);
      } else if (ch == '/') {
        return readRatio();
      } else if (ch == 'e' || ch == 'E') {
        return readNumberExponential();
      } else if (ch == 'N') {
        if(isValidOctal) {
          return readNumberBigInt(true);
        } else {
          throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
        }
      } else if (ch == 'M') {
        return readNumberBigDecimal();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        if(isValidOctal) {
          if(negative) {
            return -val8;
          } else {
            return val8;
          }
        } else {
          throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
        }
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readNumber(boolean negative) throws IOException {
    int ch1 = buffer.read();
    if (ch1 == '0') {
      int ch2 = buffer.read();
      if (ch2 == 'x' || ch2 == 'X') {
        return readNumberRadix(negative, 16, negative? 2 : 1);
      } else if (isDigit(ch2)) {
        buffer.unread();
        return readNumberOctal(negative);
      } else if (ch2 == '.') {
        return readNumberFloatingPoint(negative, 0);
      } else if (ch2 == '/') {
        return readRatio();
      } else if (ch2 == 'e' || ch2 == 'E') {
        return readNumberExponential();
      } else if (ch2 == 'N') {
        return readNumberBigInt(false);
      } else if (ch2 == 'M') {
        return readNumberBigDecimal();
      } else if (isWhitespace(ch2) || isMacro(ch2) || ch2 == -1) {
        return 0L;
      }
    } else {
      int ch2 = buffer.read();
      int ch3 = buffer.read();
      if (ch2 == 'r' || ch2 == 'R') {
        buffer.unread();
        return readNumberRadixCh1(negative, ch1);
      } else if (ch3 == 'r' || ch3 == 'R') {
        return readNumberRadixCh2(negative, ch1, ch2);
      } else {
        buffer.unread();
        buffer.unread();
        long val = ch1 - '0';
        while (true) {
          int ch = buffer.read();
          if (ch >= '0' && ch <= '9') {
            if(val > LONG_LIMIT) {
              return readNumberOverflow(false, false);
            }
            val = val * 10 + ch - '0';
          } else if (ch == '.') {
            return readNumberFloatingPoint(negative, val);
          } else if (ch == '/') {
            return readRatio();
          } else if (ch == 'e' || ch == 'E') {
            return readNumberExponential();
          } else if (ch == 'N') {
            return readNumberBigInt(false);
          } else if (ch == 'M') {
            return readNumberBigDecimal();
          } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
            buffer.unread();
            if(negative) {
              return -val;
            } else {
              return val;
            }
          } else {
            readInvalidNumber();
          }
        }
      }
    }
    throw new ReaderException("Invalid number: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
  }

  public boolean isNumberLiteral(int initch) throws IOException {
    if (isDigit(initch)) {
      return true;
    } else if (initch == '+' || initch == '-') {
      int ch = buffer.read();
      buffer.unread();
      return isDigit(ch);
    } else {
      return false;
    }
  }

  public Number readNumberNegative() throws IOException {
    return readNumber(true);
  }

  public void readInvalidSymbol() throws IOException {
    while (true) {
      int ch = buffer.read();
      if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        throw new ReaderException("Invalid symbol: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
      }
    }
  }

  public Symbol readSymbolNamespaced(int slashIndex) throws IOException {
    while (true) {
      int ch = buffer.read();
      if(ch == '/') {
        readInvalidSymbol();
      } else if (isWhitespace(ch) || isMacroTerminating(ch) || ch == -1) {
        buffer.unread();
        char[] bufferToken = buffer.getToken();
        if(buffer.getTokenStart() == slashIndex && slashIndex == buffer.getTokenEnd() - 1) {
          return Symbol.intern(null, "/");
        } else if(buffer.getTokenStart() == slashIndex) {
          throw new ReaderException("Invalid symbol: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
        } else if (slashIndex == buffer.getTokenEnd() - 1) {
          throw new ReaderException("Invalid symbol: " + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
        } else {
          String nsString = new String(bufferToken, buffer.getTokenStart(), slashIndex - buffer.getTokenStart());
          String nameString = new String(bufferToken, slashIndex + 1, buffer.getTokenEnd() - (slashIndex + 1));
          return Symbol.intern(nsString, nameString);
        }
      }
    }
  }

  public Object readSymbol() throws IOException {
    int line = buffer.getLine();
    int column = buffer.getColumn();
    while(true) {
      int ch = buffer.read();
      if(ch == '/') {
        return readSymbolNamespaced(buffer.getTokenEnd() - 1);
      } else if (isWhitespace(ch) || isMacroTerminating(ch) || ch == -1) {
        buffer.unread();
        String tokenString = buffer.getTokenString();
        if(tokenString.equals("nil")) {
          return null;
        } else if(tokenString.equals("true")) {
          return Boolean.TRUE;
        } else if(tokenString.equals("false")) {
          return Boolean.FALSE;
        }
        Symbol sym = Symbol.intern(null, tokenString);
        return sym.withMeta(RT.map(
            LINE_KEY, line,
            COLUMN_KEY, column,
            END_LINE_KEY, buffer.getLine(),
            END_COLUMN_KEY, buffer.getColumn()
        ));
      }
    }
  }

  private byte digit16(int ch) {
    if ('0' <= ch && ch <= '9') {
      return (byte) (ch - '0');
    } else if ('a' <= ch && ch <= 'f') {
      return (byte) (ch - 'a' + 10);
    } else if ('A' <= ch && ch <= 'F') {
      return (byte) (ch - 'A' + 10);
    } else {
      throw new ReaderException("Invalid digit: " + ((char) ch), fileName, buffer.getLine(), buffer.getColumn());
    }
  }

  private char readUnicodeChar() throws IOException {
    int ch1 = buffer.read();
    if(ch1 == '"') {
      throw new ReaderException("Invalid unicode escape: \\u", fileName, buffer.getLine(), buffer.getColumn());
    } else if (ch1 == -1) {
      throw new ReaderException("EOF while reading", fileName, buffer.getLine(), buffer.getColumn());
    }
    int ch2 = buffer.read();
    if(ch2 == '"') {
      throw new ReaderException("Invalid unicode escape: \\u" + (char)ch1, fileName, buffer.getLine(), buffer.getColumn());
    } else if (ch2 == -1) {
      throw new ReaderException("EOF while reading", fileName, buffer.getLine(), buffer.getColumn());
    }
    int ch3 = buffer.read();
    if(ch3 == '"') {
      throw new ReaderException("Invalid unicode escape: \\u" + (char)ch1 + (char)ch2, fileName, buffer.getLine(), buffer.getColumn());
    } else if (ch3 == -1) {
      throw new ReaderException("EOF while reading", fileName, buffer.getLine(), buffer.getColumn());
    }
    int ch4 = buffer.read();
    if(ch4 == '"') {
      throw new ReaderException("Invalid unicode escape: \\u" + (char)ch1 + (char)ch2 + (char)ch3, fileName, buffer.getLine(), buffer.getColumn());
    } else if (ch4 == -1) {
      throw new ReaderException("EOF while reading", fileName, buffer.getLine(), buffer.getColumn());
    }
    int ch = (digit16(ch1) << 12)
        + (digit16(ch2) << 8)
        + (digit16(ch3) << 4)
        +  digit16(ch4);
    return (char) ch;
  }

  private char readOctalChar() throws IOException {
    int value = 0;
    for (int i = 0; i < 3; ++i) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '7') {
        value = (value << 3) + (ch - '0');
      } else {
        buffer.unread();
        break;
      }
    }
    if (value > 0377) {
      throw new ReaderException("Octal escape sequence must be in range [0, 377], got: " + Integer.toString(value, 8), fileName, buffer.getLine(), buffer.getColumn());
    }
    return (char) value;
  }

  public Object readString() throws IOException {
    while (true) {
      int ch = buffer.read();
      if (ch == '"') {
        return new String(buffer.getToken(), buffer.getTokenStart() + 1, buffer.getTokenEnd() - 2 - buffer.getTokenStart());
      } else if (ch == -1) {
        throw new ReaderException("EOF while reading string", fileName, buffer.getLine(), buffer.getColumn());
      } else if (ch == '\\') {
        int ch2 = buffer.read();
        if (ch2 == '"') {
          buffer.replace(2, '"');
        } else if (ch2 == '\\') {
          buffer.replace(2,'\\');
        } else if (ch2 == 'n') {
          buffer.replace(2,'\n');
        } else if (ch2 == 'r') {
          buffer.replace(2,'\r');
        } else if (ch2 == 'u') {
          char unicodeChar = readUnicodeChar();
          buffer.replace(6, unicodeChar);
        } else if (ch2 == 't') {
          buffer.replace(2,'\t');
        } else if (ch2 >= '0' && ch2 <= '7') {
          buffer.unread();
          int replacePos = buffer.getTokenEnd() - 1;
          char octalChar = readOctalChar();
          int replaceLength = buffer.getTokenEnd() - replacePos;
          buffer.replace(replaceLength, octalChar);
        } else if (ch2 == 'b') {
          buffer.replace(2,'\b');
        } else if (ch2 == 'f') {
          buffer.replace(2,'\f');
        } else if (ch2 == -1) {
          throw new ReaderException("EOF while reading string", fileName, buffer.getLine(), buffer.getColumn());
        }
      }
    }
  }

  private boolean compareNext(int ch, String s) throws IOException {
    if ((char) ch != s.charAt(0)) {
      return false;
    }

    for (int i = 1; i < s.length(); ++i) {
      ch = buffer.read();
      if (ch == -1) {
        throw new ReaderException("EOF while reading", fileName, buffer.getLine(), buffer.getColumn());
      }
      if ((char) ch != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  public Object readCharacter() throws IOException {
    int ch = buffer.read();
    if(ch == -1) {
      throw new ReaderException("EOF while reading character", fileName, buffer.getLine(), buffer.getColumn());
    }
    int peek = buffer.read();
    buffer.unread();

    if (isWhitespace(peek) || isMacroTerminating(peek) || peek == -1) {
      return (char) ch;
    }

    if (ch == 'u') {
      return readUnicodeChar();
    } else if (ch == 'o') {
      return readOctalChar();
    } else if (compareNext(ch, "newline")) {
      return '\n';
    } else if (compareNext(ch, "return")) {
      return '\r';
    } else if (compareNext(ch, "space")) {
      return ' ';
    } else if (compareNext(ch, "tab")) {
      return '\t';
    } else if (compareNext(ch, "backspace")) {
      return '\b';
    } else if (compareNext(ch, "formfeed")) {
      return '\f';
    }

    while (true) {
      int chx = buffer.read();
      if (isWhitespace(chx) || isMacroTerminating(chx) || chx == -1) {
        buffer.unread();
        throw new ReaderException("Invalid character: \\" + buffer.getTokenString(), fileName, buffer.getLine(), buffer.getColumn());
      }
    }
  }

  public Object readList() throws IOException {
    ArrayList acc = new ArrayList();
    while (true) {
      Object o = read(true, delimList);
      if (o == delimList) {
        IPersistentList res = PersistentList.EMPTY;
        for (ListIterator i = acc.listIterator(acc.size()); i.hasPrevious(); ) {
          res = (IPersistentList) res.cons(i.previous());
        }
        return res;
      } else {
        acc.add(o);
      }
    }
  }

  /*public Object readMeta() throws IOException {
    Object meta = read(true, null);
  }*/

  public Object readQuote() throws IOException {
    Object quoted = read(true, null);
    return new PersistentList(quoted).cons(QUOTE_SYM);
  }

  private Symbol resolveSymbol(Symbol sym) {
    Namespace currentNs = (Namespace)RT.CURRENT_NS.get();
    Object o = currentNs.getMappings().entryAt(sym);
    if(o == null) {
      return Symbol.intern(currentNs.getName().getName(), sym.getName());
    } else if(o instanceof Class<?>) {
      Class c = (Class)o;
      return Symbol.intern(null, c.getName());
    } else if (o instanceof Var) {
      Var v = (Var)o;
      return Symbol.intern(v.ns.getName().getName(), v.sym.getName());
    } else {
      return null;
    }
  }

  private Symbol resolveNamespacedSymbol(String nsString, Symbol fullSym) {
    Symbol namespace = Symbol.intern(null, nsString);
    Namespace ns = resolveNs(namespace);
    if(ns == null || ns.getName().getName().equals(nsString)) {
      // not an alias
      return fullSym;
    } else {
      return Symbol.intern(ns.getName().getName(), fullSym.getName());
    }
  }

  private Symbol registerGensym(Symbol sym) {
    if(gensymEnv.get() == null) {
      throw new ReaderException("Gensym literal not in syntax-quote", fileName, buffer.getLine(), buffer.getColumn());
    } else {
      Symbol gensym = (Symbol) gensymEnv.get().entryAt(sym);
      if(gensym != null) {
        return gensym;
      } else {

      }
    }
  }

  public Object readSyntaxQuote() throws IOException {
    try {
      gensymEnv.set(PersistentArrayMap.EMPTY);
      Object quoted = read(true, null);
      // if special-symbol
      if (Compiler.specials.containsKey(quoted)) {
        return new PersistentList(quoted).cons(QUOTE_SYM);
      } else if (quoted instanceof Symbol) {
        Symbol sym = (Symbol) quoted;
        String symName = sym.getName();
        // Class name
        if (symName.indexOf('.') > 0) {
          // Constructor call
          if (symName.endsWith(".")) {
            Symbol csym = (Symbol.intern(symName.substring(0, symName.length() - 1)));
            return Symbol.intern(resolveSymbol(csym).getName() + ".");
          } else {
            return sym;
          }
        } else {
          String namespace = sym.getNamespace();
          if (namespace != null) {
            Object maybeClass = ((Namespace) RT.CURRENT_NS.get()).getMappings().entryAt(Symbol.intern(null, namespace));
            if (maybeClass instanceof Class<?>) {
              Class<?> c = (Class<?>) maybeClass;
              return new PersistentList(Symbol.intern(c.getName(), sym.getName())).cons(QUOTE_SYM);
            } else {
              return resolveNamespacedSymbol(namespace, sym);
            }
          } else if (symName.endsWith("#")) {
            return registerGensym(sym);
          } else if (symName.startsWith(".")) {
            return sym;
          } else {
            return resolveSymbol(sym);
          }
        }
      }
      return new PersistentList(quoted).cons(QUOTE_SYM);
    } finally {
      gensymEnv.remove();
    }
  }

  public Object read(boolean throwOnEOF, Character delim) throws IOException {
    while(true) {
      skipWhitespace();
      buffer.startNewToken();
      int ch1 = buffer.read();
      if (ch1 == -1) {
        if (throwOnEOF) {
          throw new ReaderException("EOF while reading", fileName, buffer.getLine(), buffer.getColumn());
        } else {
          return eofValue;
        }
      } else if (ch1 == '"') {
        return readString();
      } else if (ch1 == '-') {
        int ch2 = buffer.read();
        buffer.unread();
        if (isDigit(ch2)) {
          return readNumberNegative();
        } else {
          return readSymbol();
        }
      } else if (ch1 == '\\') {
        return readCharacter();
      } else if (ch1 == '+') {
        int ch2 = buffer.read();
        buffer.unread();
        if (isDigit(ch2)) {
          return readNumber(false);
        } else {
          return readSymbol();
        }
      } else if (isNumberLiteral(ch1)) {
        buffer.unread();
        return readNumber(false);
      } else if (!isWhitespace(ch1) && !isMacro(ch1)) {
        buffer.unread();
        return readSymbol();
      } else if (ch1 == '(') {
        return readList();
      } else if (ch1 == '\'') {
        return readQuote();
      } else if (ch1 == '`') {
        return readSyntaxQuote();
      } /*else if (ch1 == '^') {
        return readMeta();
      }*/ else if (ch1 == ';') {
        skipLine();
      } else if (delim != null) {
        if (ch1 == delim) {
          return delim;
        } else {
          throw new ReaderException("Unmatched delimiter: " + (char) ch1, fileName, buffer.getLine(), buffer.getColumn());
        }
      } else {
        throw new ReaderException("Unexpected character: " + (char) ch1, fileName, buffer.getLine(), buffer.getColumn());
      }
    }
  }

  public Object read() throws IOException {
    return read(throwOnEOF, null);
  }
}
