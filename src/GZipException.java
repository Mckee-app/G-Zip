public class GZipException extends Exception {
    public GZipException(Throwable cause) {
        super(cause);
    }

    public GZipException(String message) {
        super(message);
    }

    public GZipException(String message, Throwable cause) {
        super(message, cause);
    }
}
