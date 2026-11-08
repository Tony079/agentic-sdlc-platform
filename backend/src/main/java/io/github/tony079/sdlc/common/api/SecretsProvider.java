package io.github.tony079.sdlc.common.api;

import java.util.Optional;

/**
 * Secrets abstraction (owner decision O-12, 2026-11-08). Application code asks for a secret by name and never logs or
 * returns it in a response. Providers: {@code env} (environment / Spring properties; development) and {@code file}
 * (one file per secret in a mounted directory, as written by a managed secrets service or orchestrator). A managed
 * secrets-service adapter plugs in here once the provider is chosen.
 */
public interface SecretsProvider {

    /** Provider name ({@code env} or {@code file}). */
    String provider();

    /** The secret, if configured. Names: {@code ^[a-z0-9][a-z0-9.-]{1,63}$}. */
    Optional<String> secret(String name);
}
