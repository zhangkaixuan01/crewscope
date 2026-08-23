package io.crewscope.application.model;

/** Trusted operation receiving a temporary defensive copy that is cleared immediately afterward. */
@FunctionalInterface
public interface ProviderCredentialOperation<T> {

    T apply(byte[] secretBytes);
}
