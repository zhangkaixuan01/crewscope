package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.shared.event.EventActor;
import java.util.UUID;

/** Appends sanitized member Action command facts and returns the committed DomainEvent ID. */
public interface ActionCommandEventPublisher {

    UUID bundlePlanned(ActionBundle bundle, EventActor actor, UUID correlationId);

    UUID bundleConfirmed(
            Confirmation confirmation, ActionBundle bundle, EventActor actor, UUID correlationId);

    UUID confirmationCancelled(
            Confirmation confirmation, ActionBundle bundle, EventActor actor, UUID correlationId);
}
