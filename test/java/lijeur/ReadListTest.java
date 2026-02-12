package lijeur;

import clojure.lang.IPersistentList;
import clojure.lang.PersistentList;
import clojure.lang.Symbol;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

public class ReadListTest {
  @Test
  public void testList() throws IOException {
    assertEquals(PersistentList.EMPTY, new Reader(new StringReader("()")).read());
    assertTrue(((IPersistentList) PersistentList.EMPTY)
            .cons('c')
            .cons(55)
            .cons("str")
            .cons(Symbol.intern(null, "sym"))
        .equiv(new Reader(new StringReader("(sym, \"str\", 55, \\c)")).read()));
  }

  @Test
  public void testListInvalid() throws IOException {
    //assertThrows(ReaderException.class, () -> new Reader(new StringReader("(")).read());
    //assertThrows(ReaderException.class, () -> new Reader(new StringReader("(]")).read());
    //Object s = new Reader(new StringReader("(%")).read();
    Object s = '%';
    Object s2 = new Reader(new StringReader("<")).read();
  }
}

