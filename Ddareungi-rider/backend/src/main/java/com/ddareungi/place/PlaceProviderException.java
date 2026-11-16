package com.ddareungi.place;

/**
 * A place provider call failed. The message is internal and never contains provider bodies or credentials; the
 * service turns it into a public error ({@code PLACE_PROVIDER_ERROR}, or {@code EXTERNAL_API_TIMEOUT} when
 * {@link #timeout()} is true).
 */
public class PlaceProviderException extends RuntimeException {
    private final boolean timeout;

    public PlaceProviderException(String message, boolean timeout) {
        super(message, null, false, false);
        this.timeout = timeout;
    }

    public boolean timeout() { return timeout; }
}
