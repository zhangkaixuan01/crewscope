package io.crewscope.application.notification;

/** Bounded callback that receives a temporary secret copy owned and cleared by the handle. */
@FunctionalInterface
public interface NotificationCredentialOperation<T> {

    T apply(byte[] secretBytes);
}
