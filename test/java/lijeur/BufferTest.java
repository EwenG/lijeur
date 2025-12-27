package lijeur;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

public class BufferTest {

  @Test
  public void testReadCharacters() throws IOException {
    Buffer buffer = new Buffer(new StringReader("abc"), 2);

    assertEquals('a', buffer.read());
    assertEquals('b', buffer.read());
    assertEquals('c', buffer.read());
    assertEquals(-1, buffer.read()); // End of input
  }

  @Test
  public void testUnreadBehavior() throws IOException {
    Buffer buffer = new Buffer(new StringReader("xyz"), 2);
    assertEquals('x', buffer.read());
    assertEquals('y', buffer.read());
    buffer.unread(); // Should step back to 'y'
    assertEquals('y', buffer.read());
  }

  @Test
  public void testUnreadDoesNotUnderflowTokenStart() throws IOException {
    Buffer buffer = new Buffer(new StringReader("hi"), 1);
    buffer.startNewToken();
    assertEquals('h', buffer.read());
    buffer.unread(); // valid unread
    buffer.unread(); // should not go before tokenStart
    assertEquals('h', buffer.read());
  }

  @Test
  public void testTokenStringAndBounds() throws IOException {
    Buffer buffer = new Buffer(new StringReader("hello world"), 5);
    buffer.startNewToken();
    buffer.read(); // h
    buffer.read(); // e
    buffer.read(); // l
    buffer.read(); // l
    buffer.read(); // o

    assertEquals("hello", buffer.getTokenString());
    assertEquals(0, buffer.getTokenStart());
    assertEquals(5, buffer.getTokenEnd());
  }

  @Test
  public void testStartNewTokenAdjustsPosition() throws IOException {
    Buffer buffer = new Buffer(new StringReader("abcdef"), 3);
    buffer.read(); // a
    buffer.read(); // b
    buffer.read(); // c
    buffer.startNewToken(); // should shift buffer if needed

    buffer.read(); // d
    buffer.read(); // e

    assertEquals("de", buffer.getTokenString());
    assertEquals(3, buffer.getTokenStart());
    assertEquals(5, buffer.getTokenEnd());
  }

  @Test
  public void testBufferGrowthBeyondInitialSize() throws IOException {
    String input = "01234567890123456789"; // 20 characters
    Buffer buffer = new Buffer(new StringReader(input), 5);

    StringBuilder result = new StringBuilder();
    int ch;
    while ((ch = buffer.read()) != -1) {
      result.append((char) ch);
    }

    assertEquals(input, result.toString());
    assertTrue(buffer.bufferSize >= 20); // ensure buffer grew
  }

  @Test
  public void testBufferShrinksAfterGrowth() throws IOException {
    int chunkSize = 4;
    Buffer buffer = new Buffer(new StringReader("abcdefghi"), chunkSize);

    // Step 1: Grow buffer (simulate reading lots of data)
    for (int i = 0; i < 9; i++) {
      buffer.read(); // reads all 8 characters (buffer should grow beyond initial size)
    }

    assertTrue(buffer.bufferSize > 2 * chunkSize, "Buffer should have grown");

    // Step 2: simulate moving past readChunkSize to allow compaction
    buffer.startNewToken(); // should shift buffer and shrink if posEnd < chunkSize
    assertEquals(2 * chunkSize, buffer.bufferSize, "Buffer should shrink back to initial size");
  }

  @Test
  public void testEOFHandling() throws IOException {
    Buffer buffer = new Buffer(new StringReader("x"), 1);
    assertEquals('x', buffer.read());
    assertEquals(-1, buffer.read()); // EOF
    assertEquals(-1, buffer.read()); // Keep returning EOF
    assertTrue(buffer.EOF); // public for test access
  }

  @Test
  public void testLineColumns() throws IOException {
    Buffer buffer = new Buffer(new StringReader("abc\nde"), 2, true);

    assertEquals(0, buffer.getLine());
    assertEquals(0, buffer.getColumn());
    buffer.read();
    assertEquals(0, buffer.getLine());
    assertEquals(1, buffer.getColumn());
    buffer.read();
    assertEquals(0, buffer.getLine());
    assertEquals(2, buffer.getColumn());
    buffer.read();
    assertEquals(0, buffer.getLine());
    assertEquals(3, buffer.getColumn());
    buffer.read();
    assertEquals(1, buffer.getLine());
    assertEquals(0, buffer.getColumn());
    buffer.read();
    assertEquals(1, buffer.getLine());
    assertEquals(1, buffer.getColumn());
    buffer.read();
    assertEquals(1, buffer.getLine());
    assertEquals(2, buffer.getColumn());
    assertEquals(-1, buffer.read());
  }
}
