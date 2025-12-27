package lijeur;

public class ReaderException extends RuntimeException {
  private String file;
  private int line;
  private int column;

  public ReaderException(String message, String file, int line, int column) {
    super((file != null ? file + " " : "") + 
          (line != -1 && column != -1 ? "[line " + line + ", col " + column + "]" : "") +
          " " +
          message);
    this.file = file;
    this.line = line;
    this.column = column;
  }

  public String getFile() {
    return file;
  }

  public int getLine() {
    return line;
  }

  public int getColumn() {
    return column;
  }
}
