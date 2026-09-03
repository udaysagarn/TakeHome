package ai.devin.mend.exception;

/** The Devin API refused or failed a call after menD's retries were spent. */
public class DevinApiException extends RuntimeException {
    public DevinApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
