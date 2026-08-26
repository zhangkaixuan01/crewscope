package io.crewscope.application.notification;

/** Provider-neutral notification write and query-recovery boundary. */
public interface NotificationProviderPort {

    NotificationSendResult send(
            NotificationProviderRequest request, NotificationCredentialHandle credential);

    NotificationQueryResult query(
            NotificationProviderRequest request, NotificationCredentialHandle credential);
}
