package lijeur;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReadStringTest {
  @Test
  public void testString() throws IOException {
    assertEquals("", new Reader(new StringReader("\"\"")).read());
    assertEquals("123", new Reader(new StringReader("\"123\"")).read());
    assertThrows(ReaderException.class, () -> new Reader(new StringReader("\"123")).read());
  }

  @Test
  public void testEscapes() throws IOException {
    assertThrows(ReaderException.class, () -> new Reader(new StringReader("\"\\")).read());
    assertThrows(ReaderException.class, () -> new Reader(new StringReader("\"\\\"")).read());
    assertEquals("\"", new Reader(new StringReader("\"\\\"\"")).read());
    assertEquals("\\", new Reader(new StringReader("\"\\\\\"")).read());
    assertEquals("\n", new Reader(new StringReader("\"\\n\"")).read());
    assertEquals("\r", new Reader(new StringReader("\"\\r\"")).read());
    assertEquals("\t", new Reader(new StringReader("\"\\t\"")).read());
    assertEquals("\b", new Reader(new StringReader("\"\\b\"")).read());
    assertEquals("\f", new Reader(new StringReader("\"\\f\"")).read());
  }

  @Test
  public void testUnicodeChar() throws IOException {
    assertEquals("ሴ", new Reader(new StringReader("\"\\u1234\"")).read());
    assertEquals("ሴ5", new Reader(new StringReader("\"\\u12345\"")).read());
    assertThrows(ReaderException.class, () -> new Reader(new StringReader("\"\\u\"")).read());
  }

  @Test
  public void testOctalChar() throws IOException {
    assertEquals("\u0000", new Reader(new StringReader("\"\\0\"")).read());
    assertEquals("ÿ", new Reader(new StringReader("\"\\377\"")).read());
    assertEquals("\\8", new Reader(new StringReader("\"\\8\"")).read());
    assertEquals("\\a", new Reader(new StringReader("\"\\a\"")).read());
    assertThrows(ReaderException.class, () -> new Reader(new StringReader("\"\\400\"")).read());
    assertEquals("ÿ7", new Reader(new StringReader("\"\\3777\"")).read());
  }
}
