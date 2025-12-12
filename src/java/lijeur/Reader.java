package lijeur;

import clojure.lang.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class Reader {

  private Buffer buffer;

  public static final BitSet macroMask = new BitSet(0x80);
  // A safe upper bound in base 10 (so also in base 8)
  // ie the result of the equation: val * 10 + 9 <= Long.MAX_VALUE
  public static final long LONG_LIMIT = (Long.MAX_VALUE - 9) / 10;
  public static final long LONG_LIMIT_RADIX = (Long.MAX_VALUE - 35) / 36;
  public static final long MAX_DECIMAL_COUNT = 15;

  private final boolean throwOnEOF;
  private final Object eofValue;
  private final boolean countLines;

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

  public Reader(java.io.Reader r, int readChunkSize, boolean throwOnEOF, Object eofValue, boolean countLines) {
    this.buffer = new Buffer(r, readChunkSize, countLines);
    this.throwOnEOF = throwOnEOF;
    this.eofValue = eofValue;
    this.countLines = countLines;
  }

  public Reader(java.io.Reader r) {
    this(r, 4096, true, null, false);
  }

  public static boolean isWhitespace(int ch) {
    return (Character.isWhitespace(ch) || ch == ',');
  }

  public static boolean isMacro(int ch) {
    return ch > -1 && ch < 0x80 && macroMask.get(ch);
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

  public void readInvalidNumber() throws IOException {
    while (true) {
      int ch = buffer.read();
      if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
          throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
          throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
  }

  public Number readNumberRadixCh1(boolean negative, int ch1) throws IOException {
    if(ch1 >= '2' && ch1 <= '9') {
      return readNumberRadix(negative, ch1 - '0', negative ? 2 : 1);
    } else {
      readInvalidNumber();
    }
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
  }

  public Number readNumberRadixCh2(boolean negative, int ch1, int ch2) throws IOException {
    if(ch1 >= '0' && ch1 <= '9' && ch2 >= '0' && ch2 <= '9') {
      return readNumberRadix(negative,(ch1 - '0') * 10 + (ch2 - '0'), negative ? 3 : 2);
    } else {
      readInvalidNumber();
    }
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
          throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
          throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
    throw new RuntimeException("Invalid number: " + buffer.getTokenString());
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
        throw new RuntimeException("Invalid symbol: " + buffer.getTokenString());
      }
    }
  }

  public Symbol readSymbolNamespaced(int slashIndex) throws IOException {
    while (true) {
      int ch = buffer.read();
      if(ch == '/') {
        readInvalidSymbol();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        char[] bufferToken = buffer.getToken();
        if(buffer.getTokenStart() == slashIndex && slashIndex == buffer.getTokenEnd() - 1) {
          return Symbol.intern(null, "/");
        } else if(buffer.getTokenStart() == slashIndex) {
          throw new RuntimeException("Invalid symbol: " + buffer.getTokenString());
        } else if (slashIndex == buffer.getTokenEnd() - 1) {
          throw new RuntimeException("Invalid symbol: " + buffer.getTokenString());
        } else {
          String nsString = new String(bufferToken, buffer.getTokenStart(), slashIndex - buffer.getTokenStart());
          String nameString = new String(bufferToken, slashIndex + 1, buffer.getTokenEnd() - (slashIndex + 1));
          return Symbol.intern(nsString, nameString);
        }
      }
    }
  }

  public Object readSymbol() throws IOException {
    while (true) {
      int ch = buffer.read();
      if(ch == '/') {
        return readSymbolNamespaced(buffer.getTokenEnd() - 1);
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        if(buffer.getTokenString().equals("nil")) {
          return null;
        } else if(buffer.getTokenString().equals("true")) {
          return Boolean.TRUE;
        } else if(buffer.getTokenString().equals("false")) {
          return Boolean.FALSE;
        }
        return Symbol.intern(null, buffer.getTokenString());
      }
    }
  }

  public Object read() throws IOException {
    skipWhitespace();
    buffer.startNewToken();
    int ch1 = buffer.read();

    if (ch1 == -1) {
      return null;
    } else if(ch1 == '-') {
      int ch2 = buffer.read();
      buffer.unread();
      if(isDigit(ch2)) {
        return readNumberNegative();
      } else {
        return readSymbol();
      }
    } else if (isNumberLiteral(ch1)) {
      buffer.unread();
      return readNumber(false);
    } else if(!isWhitespace(ch1) && !isMacro(ch1))  {
      buffer.unread();
      return readSymbol();
    } else {
      return null;
    }
  }
}
