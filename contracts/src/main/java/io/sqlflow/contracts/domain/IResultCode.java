package io.sqlflow.contracts.domain;

/**
 * Standard status code contract for {@link R} envelopes ({@code 01 §3.2.2}).
 */
public interface IResultCode {

    int getCode();

    String getMessage();
}
