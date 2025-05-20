package lijeur;

import clojure.lang.*;

import java.io.StringReader;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

public class Reader {

  private Buffer buffer;

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

  public Reader(java.io.Reader r) {
    buffer = new Buffer(r, 4096);
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

  public Number readNumberFloatingPoint(long val) throws IOException {
    double decimal = 0;
    int decimalCount = 0;
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '9') {
        decimalCount++;
        decimal = decimal + (ch - '0') / (Math.pow(10.0, decimalCount));
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        return val + decimal;
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readRatio() throws IOException {
    char[] token = buffer.getToken();
    String numerator = new String(token, buffer.getTokenStart(), buffer.getTokenEnd() - buffer.getTokenStart() - 1);
    int denominatorStart = buffer.getTokenStart();
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '9') {
        // Continue reading
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
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

  public Number readNumberOverflow() throws IOException {
    boolean isFloatingPointNumber = false;
    while (true) {
      int ch = buffer.read();
      if (ch >= '0' && ch <= '9') {
        // Continue
      } else if (ch == '.') {
        if(isFloatingPointNumber) {
          readInvalidNumber();
        }
        isFloatingPointNumber = true;
      } else if(ch == '/') {
        if(isFloatingPointNumber) {
          readInvalidNumber();
        }
        readRatio();
      } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
        buffer.unread();
        if(isFloatingPointNumber) {
          return Double.parseDouble(buffer.getTokenString());
        } else {
          return BigInt.fromBigInteger(new BigInteger(buffer.getTokenString()));
        }
      } else {
        readInvalidNumber();
      }
    }
  }

  public Number readNumber() throws IOException {
    int ch1 = buffer.read();
    if (ch1 == '0') {
      int ch2 = buffer.read();
      if (ch2 == 'x' || ch2 == 'X') {
        return 0;
        //return readNumberHex();
      } else if (isDigit(ch2)) {
        buffer.unread();
        return 0;
        //return readNumberOctal();
      } else if (isWhitespace(ch2) || isMacro(ch2) || ch2 == -1) {
        return 0;
      }
    } else {
      int ch2 = buffer.read();
      if (ch2 == 'r') {
        return 0;
        //return readNumberRadix();
      } else {
        buffer.unread();
        long val = ch1 - '0';
        while (true) {
          int ch = buffer.read();
          if (ch >= '0' && ch <= '9') {
            val = val * 10 + ch - '0';
            if(val < 0) {
              return readNumberOverflow();
            }
          } else if (ch == '.') {
            return readNumberFloatingPoint(val);
          } else if (ch == '/') {
            return readRatio();
          } else if (isWhitespace(ch) || isMacro(ch) || ch == -1) {
            buffer.unread();
            return val;
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

  public static Number negativeNumber(Number n) {
    return n;
  }

  public Object read() throws IOException {
    skipWhitespace();
    buffer.startNewToken();
    int initch = buffer.read();

    if (initch == -1) {
      return null;
    } else if (isNumberLiteral(initch)) {
      buffer.unread();
      return readNumber();
    } else {
      return null;
    }
  }
}
