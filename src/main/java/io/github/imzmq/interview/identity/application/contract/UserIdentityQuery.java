package io.github.imzmq.interview.identity.application.contract;

import java.util.function.Supplier;

/**
 * Resolves the stable user identifier used by business modules without exposing Servlet types.
 */
public interface UserIdentityQuery {

    String resolve(String authorization, String userIdHeader, Supplier<String> sessionIdSupplier);

    /**
     * Resolves identity from a principal already authenticated by the security layer.
     */
    String resolveTrusted(String authenticatedPrincipal, Supplier<String> sessionIdSupplier);
}
