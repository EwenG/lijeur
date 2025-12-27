package lijeur;

import clojure.lang.Symbol;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

public class ReadSymbolTest {
  @Test
  public void testMinus() throws IOException {
    assertEquals(Symbol.intern(null, "-"), new Reader(new StringReader("-")).read());
    assertEquals(Symbol.intern(null, "-sym"), new Reader(new StringReader("-sym")).read());
  }

  @Test
  public void testPlus() throws IOException {
    assertEquals(Symbol.intern(null, "+"), new Reader(new StringReader("+")).read());
    assertEquals(Symbol.intern(null, "+sym"), new Reader(new StringReader("+sym")).read());
  }

  @Test
  public void testSlash() throws IOException {
    assertEquals(Symbol.intern(null, "/"), new Reader(new StringReader("/")).read());
  }

  @Test
  public void testNilTrueFalse() throws IOException {
    assertNull(new Reader(new StringReader("nil")).read());
    assertEquals(Boolean.TRUE, new Reader(new StringReader("true")).read());
    assertEquals(Boolean.FALSE, new Reader(new StringReader("false")).read());
  }

  @Test
  public void testInvalid() {
    assertThrows(RuntimeException.class, () -> new Reader(new StringReader("-/")).read());
    assertThrows(RuntimeException.class, () -> new Reader(new StringReader("/-")).read());
    assertThrows(RuntimeException.class, () -> new Reader(new StringReader("-/n/s")).read());
    assertThrows(RuntimeException.class, () -> new Reader(new StringReader("/n/s")).read());
  }

  @Test
  public void testValid() throws IOException {
    assertEquals(Symbol.intern(null, "sym"), new Reader(new StringReader("sym")).read());
    assertEquals(Symbol.intern("ns", "sym"), new Reader(new StringReader("ns/sym")).read());
  }
}
