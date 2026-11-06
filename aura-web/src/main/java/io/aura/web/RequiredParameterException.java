package io.aura.web;

/**
 * A parameter the route contract calls required and the request did not carry.
 *
 * <p>It is an {@link IllegalArgumentException} so an application's own handler for bad requests
 * still catches it, and a {@link CodedException} so the response names the missing field instead
 * of answering a bare 400.
 */
final class RequiredParameterException extends IllegalArgumentException implements CodedException {

    private final String parameter;

    RequiredParameterException(String parameter) {
        super("Required parameter '" + parameter + "' is missing");
        this.parameter = parameter;
    }

    @Override public String code() { return "AURA_PARAMETER_REQUIRED"; }

    @Override public String field() { return parameter; }
}
