public class GZipRuntimeException extends RuntimeException {
    public GZipRuntimeException(Throwable cause) {
        super(cause);
    }

    public GZipRuntimeException(String message) {
        super(message);
    }

    public GZipRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
