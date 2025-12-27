package lijeur;

import static org.junit.jupiter.api.Assertions.*;

import clojure.lang.BigInt;
import clojure.lang.Ratio;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;

public class ReadNumberTest {

  @Test
  public void test0() throws IOException {
    assertEquals(0L, new Reader(new StringReader("0")).read());
  }

  @Test
  public void test0Radix16() throws IOException {
    // Different from clojure.tools.reader
    assertEquals(0L, new Reader(new StringReader("0x")).read());
    assertEquals(0L, new Reader(new StringReader("0X")).read());
    assertEquals(10L, new Reader(new StringReader("0xa")).read());
    assertEquals(10L, new Reader(new StringReader("0XA")).read());
    assertEquals(BigInt.fromLong(11259375), new Reader(new StringReader("0XAbcdefN")).read());
  }

  @Test
  public void test0Octal() throws IOException {
    assertEquals(0L, new Reader(new StringReader("00")).read());
    assertEquals(63L, new Reader(new StringReader("077")).read());
  }

  @Test
  public void test0FloatingPoint() throws IOException {
    assertEquals(0.0, new Reader(new StringReader("0.")).read());
    assertEquals(0.123, new Reader(new StringReader("0.123")).read());
    assertEquals(123.45678901, new Reader(new StringReader("123.45678901")).read());
  }

  @Test
  public void test0Ratio() throws IOException {
    assertEquals(0L, new Reader(new StringReader("0/1")).read());
  }

  @Test
  public void test0Exponential() throws IOException {
    assertEquals(0.0, new Reader(new StringReader("0e2")).read());
  }

  @Test
  public void test0BigInt() throws IOException {
    assertEquals(BigInt.fromLong(0), new Reader(new StringReader("0N")).read());
  }

  @Test
  public void test0BigDecimal() throws IOException {
    assertEquals(new BigDecimal("0"), new Reader(new StringReader("0M")).read());
    assertEquals(new BigDecimal("123"), new Reader(new StringReader("0123M")).read());
    assertEquals(new BigDecimal("1.23"), new Reader(new StringReader("01.23M")).read());
    assertEquals(new BigDecimal("1.2e3"), new Reader(new StringReader("01.2e3M")).read());
  }

  @Test
  public void testNumber() throws IOException {
    assertEquals(9223372036854775807L, new Reader(new StringReader("9223372036854775807")).read());
    assertEquals(BigInt.fromLong(9223372036854775807L), new Reader(new StringReader("9223372036854775807N")).read());
  }

  @Test
  public void testFloatingPoint() throws IOException {
    assertEquals(123.45678901, new Reader(new StringReader("123.45678901")).read());
    // Overflow
    assertEquals(0.12345678900123456789, new Reader(new StringReader("0.12345678900123456789")).read());
    assertEquals(190.0, new Reader(new StringReader("1.9e2")).read());
    assertEquals(Double.POSITIVE_INFINITY, new Reader(new StringReader("12.1E234567")).read());
    assertEquals(1000.0, new Reader(new StringReader("999.999999999999999")).read());
    // Overflow
    assertEquals(1000.0, new Reader(new StringReader("999.9999999999999999")).read());
    assertEquals(0.999999999999999, new Reader(new StringReader("0.999999999999999")).read());
  }

  @Test
  public void testRadix() throws IOException {
    assertEquals(1L, new Reader(new StringReader("2r1")).read());
    assertEquals(139023L, new Reader(new StringReader("2r0100001111100001111")).read());
    // Overflow
    assertEquals(9223372036854775807L, new Reader(new StringReader("2r111111111111111111111111111111111111111111111111111111111111111")).read());
    assertEquals(BigInt.fromBigInteger(new BigInteger("17332240144241699839")), new Reader(new StringReader("2r1111000010001000011111001001010010111111010010101010101111111111")).read());
    assertEquals(BigInt.fromBigInteger(new BigInteger("86846823611197163108337531226495015298096208677436155")), new Reader(new StringReader("36r0123456789abcdefghijklmnopqrstuvwxyz")).read());
  }

  @Test
  public void testRatio() throws IOException {
    assertEquals(0L, new Reader(new StringReader("0/123456789")).read());
    assertEquals(1L, new Reader(new StringReader("123456789/123456789")).read());
    assertEquals(new Ratio(new BigInteger("137174211111111111111111111111111111111111111"), new BigInteger("13717421")), new Reader(new StringReader("1234567899999999999999999999999999999999999999/123456789")).read());
    assertEquals(new Ratio(new BigInteger("13717421"), new BigInteger("137174211111111111111111111111111111111111111")), new Reader(new StringReader("123456789/1234567899999999999999999999999999999999999999")).read());
  }

  @Test
  public void testExponential() throws IOException {
    assertEquals(1.0, new Reader(new StringReader("1e0")).read());
    assertEquals(Double.POSITIVE_INFINITY, new Reader(new StringReader("123456789e123456789")).read());
    assertEquals(1900.0, new Reader(new StringReader("19e2")).read());
  }

  @Test
  public void testBigDecimal() throws IOException {
    assertEquals(new BigDecimal("1.1"), new Reader(new StringReader("1.1M")).read());
    assertEquals(new BigDecimal("1.2e3"), new Reader(new StringReader("1.2e3M")).read());
    assertEquals(new BigDecimal("999999999999999999999999"), new Reader(new StringReader("999999999999999999999999M")).read());
    assertEquals(new BigDecimal("999999999999999999999999.9"), new Reader(new StringReader("999999999999999999999999.9M")).read());
    assertEquals(new BigDecimal("9.999999999999999999999999E+25"), new Reader(new StringReader("999999999999999999999999.9e2M")).read());
  }

  @Test
  public void testNegativeNumber() throws IOException {
    assertEquals(-2L, new Reader(new StringReader("-2")).read());
    assertEquals(-1.23456789E-4, new Reader(new StringReader("-0.000123456789")).read());
    assertEquals(BigInt.fromBigInteger(new BigInteger("-1234567899999999999999999999")), new Reader(new StringReader("-1234567899999999999999999999")).read());
    assertEquals(BigInt.fromLong(-123456789), new Reader(new StringReader("-123456789N")).read());
    assertEquals(new BigDecimal("-1.2e3"), new Reader(new StringReader("-1.2e3M")).read());
    assertEquals(new Ratio(new BigInteger("-1"), new BigInteger("2")), new Reader(new StringReader("-1/2")).read());
    assertEquals(-81985529216486895L, new Reader(new StringReader("-0x123456789abcdef")).read());
    assertEquals(-44027L, new Reader(new StringReader("-36rxyz")).read());
    assertEquals(-9223372036854775807L, new Reader(new StringReader("-2r111111111111111111111111111111111111111111111111111111111111111")).read());
    assertEquals(-9223372036854775808L, new Reader(new StringReader("-9223372036854775808")).read());
    assertEquals(BigInt.fromBigInteger(new BigInteger("-9223372036854775808")), new Reader(new StringReader("-9223372036854775808N")).read());
  }

  @Test
  public void testPlus() throws IOException {
    assertEquals(0L, new Reader(new StringReader("+00")).read());
    assertEquals(2L, new Reader(new StringReader("+2")).read());
    assertEquals(0L, new Reader(new StringReader("+0x")).read());
    assertEquals(10L, new Reader(new StringReader("+0xa")).read());
  }
}