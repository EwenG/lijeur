package lijeur;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.io.IOException;

public class ReaderTest {

    @Test
    void testThrowOnEOFTrue() throws IOException {
        Reader reader = new Reader(new StringReader("  123"), true, null, false);
        assertEquals(123L, reader.read()); // Read the number
        
        assertThrows(ReaderException.class, () -> {
            reader.read(); // Should throw on EOF
        }, "Expected ReaderException on EOF");
    }

    @Test
    void testThrowOnEOFFalse() throws IOException {
        Reader reader = new Reader(new StringReader("123"), false, null, false);
        assertEquals(123L, reader.read()); // Read the number
        assertNull(reader.read()); // Should return null on EOF
    }

    @Test
    void testThrowOnEOFFalseWithEOFValue() throws IOException {
        Object eofValue = "EOF_MARKER";
        Reader reader = new Reader(new StringReader("123"), false, eofValue, false);
        assertEquals(123L, reader.read()); // Read the number
        assertEquals(reader.read(), eofValue);
    }

    @Test
    void testConstructorWithOnlyReader() throws IOException {
        Reader reader = new Reader(new StringReader(""));
        // Default is throwOnEOF = true
        ReaderException exception = assertThrows(ReaderException.class, () -> {
            reader.read();
        }, "Expected ReaderException on EOF with default constructor");
        assertTrue(exception.getMessage().contains("EOF while reading"));
    }

    @Test
    void testConstructorWithReaderAndThrowOnEOF() throws IOException {
        Reader reader = new Reader(new StringReader(""), false);
        assertNull(reader.read()); // Should return null, not throw
    }

    @Test
    void testCountLinesParameter() throws IOException {
        // Test that countLines parameter tracks line/column correctly in exceptions
        Reader readerWithLines = new Reader(new StringReader("123\n456"), true, null, true);
        
        assertEquals(123L, readerWithLines.read()); // Read first number
        assertEquals(456L, readerWithLines.read()); // Read second number on line 2
        
        ReaderException exception = assertThrows(ReaderException.class, () -> {
            readerWithLines.read(); // Should throw on EOF with line/column info
        }, "Expected ReaderException on EOF");
        
        assertTrue(exception.getMessage().contains("EOF while reading"), "Exception should contain EOF message");
        assertTrue(exception.getLine() == 1, "Exception should contain line number");
        assertTrue(exception.getColumn() == 3, "Exception should contain column number");
    }

  @Test
  void testWhiteSpaces() throws IOException {
    // Test that countLines parameter tracks line/column correctly in exceptions
    Reader readerWithLines = new Reader(new StringReader("123\n456"), true, null, true);

    assertEquals(123L, readerWithLines.read()); // Read first number
    assertEquals(456L, readerWithLines.read()); // Read second number on line 2
  }
}
