package lijeur;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

public class Buffer {
  private final Reader reader;
  int bufferSize;
  final int readChunkSize;
  boolean EOF = false;

  char[] buffer;

  // Current reading position in the buffer
  private int pos = 0;
  // Max reading position in the buffer
  private int posEnd = 0;
  // Position of the start of current token in the buffer
  private int tokenStart = 0;

  public Buffer(Reader reader, int readChunkSize) {
    this.reader = reader;
    this.readChunkSize = readChunkSize;
    this.bufferSize = 2 * readChunkSize;
    buffer = new char[bufferSize];
  }

  public int read() throws IOException {
    if (posEnd == 0) {
      posEnd = reader.read(buffer, 0, readChunkSize);
      if(posEnd <= 0) {
        EOF = true;
        return -1;
      }
    }
    if(pos == posEnd && (bufferSize - pos) >= readChunkSize) {
      int readLength = reader.read(buffer, pos, readChunkSize);
      if(readLength <= 0) {
        EOF = true;
        return -1;
      } else {
        posEnd = posEnd + readLength;
      }
    } else if (pos == posEnd && (bufferSize - pos) < readChunkSize) {
      buffer = Arrays.copyOf(buffer, bufferSize + readChunkSize);
      bufferSize = bufferSize + readChunkSize;
      int readLength = reader.read(buffer, pos, readChunkSize);
      if(readLength <= 0) {
        EOF = true;
        return -1;
      } else {
        posEnd = posEnd + readLength;
      }
    }
    EOF = false;
    return buffer[pos++];
  }

  public void unread() {
    if(pos > tokenStart && !EOF) {
      pos--;
    }
  }

  public char[] getToken() {
    return buffer;
  }

  public int getTokenStart() {
    return tokenStart;
  }

  public int getTokenEnd() {
    return pos;
  }

  public String getTokenString() {
    return new String(getToken(), getTokenStart(), getTokenEnd() - getTokenStart());
  }

  public void startNewToken() {
    if(pos > readChunkSize) {
      System.arraycopy(buffer, pos, buffer, 0, posEnd - pos);
      posEnd = posEnd - pos;
      pos = 0;
      if(bufferSize > 2 * readChunkSize && posEnd < readChunkSize) {
        buffer = Arrays.copyOf(buffer, 2 * readChunkSize);
        bufferSize = 2 * readChunkSize;
      }
    }
    tokenStart = pos;
  }
}
