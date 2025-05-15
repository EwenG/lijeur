package lijeur;

import java.io.IOException;
import java.io.Reader;

public class Buffer {
  // There should be 2 fields for chunks (char arrays)

  // There should be fields to keep track of the current chunk and the current position in the current chunk.

  // There should be fields to keep track of the start position of the current token being read

  // There should be a char array of reasonable size (maybe 256). This field should be returned by getToken()
  // when the token fits in this char array. Otherwise, if the token is to big, getToken() should allocate a new array.


  Buffer(Reader reader, int size) throws IOException {
  }

  // Should read one char and return it
  // Should load the current chunk if needed, by reading the reader.
  // If the current chunk is full, it should load the other chunk
  // If both chunks are full, the current chunk should be extended
  char read() throws IOException {

  }

  // Should set the position one step back
  char unread() throws IOException {

  }

  // Should mark the end of the current token, next calls to read() should read a new token
  char mark() {

  }

  // Should be called only after mark(), should return the marked token
  char getToken() {

  }

  // Should return length of the token returned by getToken()
  long getTokenLength() {

  }
}
