package com.back.coffeeprod.domain.payment.gateway;

/** Only explicit, final provider rejection is REJECTED; all other failures require lookup. */
public class GatewayException extends RuntimeException {
    private final boolean rejected;
    public GatewayException(boolean rejected) { super(rejected ? "PG_REJECTED" : "PG_UNKNOWN"); this.rejected = rejected; }
    public boolean isRejected() { return rejected; }
}
