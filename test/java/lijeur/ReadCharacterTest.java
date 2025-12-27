package lijeur;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReadCharacterTest {
  @Test
  public void testCharacter() throws IOException {
    assertEquals('a', new Reader(new StringReader("\\a")).read());
    assertThrows(ReaderException.class, () -> new Reader(new StringReader("\\aa")).read());
    assertEquals('ሴ', new Reader(new StringReader("\\u1234")).read());
    assertEquals('ÿ', new Reader(new StringReader("\\o377")).read());
    assertEquals('\n', new Reader(new StringReader("\\newline")).read());
    assertEquals('\r', new Reader(new StringReader("\\return")).read());
    assertEquals(' ', new Reader(new StringReader("\\space")).read());
    assertEquals('\t', new Reader(new StringReader("\\tab")).read());
    assertEquals('\b', new Reader(new StringReader("\\backspace")).read());
    assertEquals('\f', new Reader(new StringReader("\\formfeed")).read());
  }
}
