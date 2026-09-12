package traceapp;

/**
 * A domain object, deliberately carrying one field that is PII and several that are not, so the
 * suite can prove which ones can reach a trace document.
 *
 * <p>{@link #getPassengerEmail()} is refused by the redaction denylist at projection-config load
 * time and is asserted to be absent from every document. {@link #getCarrier()} (an enum),
 * {@link #getStops()} (an int) and {@link #isRefundable()} (a boolean) are the whitelisted
 * projections.
 */
public final class Fare {

    public enum Carrier { INDIGO, VISTARA, AKASA }

    private final Carrier carrier;
    private final int stops;
    private final long priceMinor;
    private final boolean refundable;
    private final String passengerEmail;

    public Fare(Carrier carrier, int stops, long priceMinor, boolean refundable,
                String passengerEmail) {
        this.carrier = carrier;
        this.stops = stops;
        this.priceMinor = priceMinor;
        this.refundable = refundable;
        this.passengerEmail = passengerEmail;
    }

    public Carrier getCarrier() { return carrier; }

    public int getStops() { return stops; }

    public long getPriceMinor() { return priceMinor; }

    public boolean isRefundable() { return refundable; }

    /** PII. Named in the projection config ON PURPOSE, so the refusal can be asserted. */
    public String getPassengerEmail() { return passengerEmail; }
}
