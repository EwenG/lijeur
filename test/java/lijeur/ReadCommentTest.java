package lijeur;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReadCommentTest {
  @Test
  public void testComment() throws IOException {
    assertEquals(2L, new Reader(new StringReader("; a 1 ()\n2")).read());
  }
}
