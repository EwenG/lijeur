import lijeur.Parser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ParserTest {
  @Test
  void testParseSimpleVector() throws IOException {
    String edn = "[1 2 3]";
    Object result = Parser.parseEdn(edn);

    assertTrue(result instanceof List);
    assertEquals(List.of(1L, 2L, 3L), result);
  }
}
