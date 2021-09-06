package org.dcsa.ovs.notifications.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.TransportCallBasedEvent;
import org.dcsa.core.events.model.enums.SignatureMethod;
import org.dcsa.core.events.model.transferobjects.TransportCallTO;
import org.dcsa.core.events.service.GenericEventService;
import org.dcsa.core.events.service.TransportCallTOService;
import org.dcsa.core.events.service.impl.MessageSignatureHandler;
import org.dcsa.core.exception.CreateException;
import org.dcsa.core.service.impl.ExtendedBaseServiceImpl;
import org.dcsa.ovs.notifications.model.NotificationEndpoint;
import org.dcsa.ovs.notifications.repository.NotificationEndpointRepository;
import org.dcsa.ovs.notifications.service.NotificationEndpointService;
import org.dcsa.ovs.notifications.service.TimestampNotificationMailService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Service
@Slf4j
public class NotificationEndpointServiceImpl extends ExtendedBaseServiceImpl<NotificationEndpointRepository, NotificationEndpoint, UUID> implements NotificationEndpointService {

    private static final TypeReference<List<Event>> EVENT_TYPE_REFERENCE = new TypeReference<>() {};

    private final GenericEventService genericEventService;
    private final MessageSignatureHandler messageSignatureHandler;
    private final NotificationEndpointRepository notificationEndpointRepository;
    private final TransportCallTOService transportCallTOService;
    private final ObjectMapper objectMapper;
    private final TimestampNotificationMailService timestampNotificationMailService;

    @Override
    protected Mono<NotificationEndpoint> preSaveHook(NotificationEndpoint notificationEndpoint) {
        SignatureMethod method = SignatureMethod.HMAC_SHA256;
        byte[] secret = notificationEndpoint.getSecret();
        if (secret == null) {
            return Mono.error(new CreateException("Missing mandatory secret field"));
        }
        if (secret.length < method.getMinKeyLength()) {
            return Mono.error(new CreateException("length of the secret should be minimum " + method.getMinKeyLength()
                    + " bytes long (was: " + secret.length + ")"));
        }
        if (method.getMaxKeyLength() < secret.length) {
            return Mono.error(new CreateException("length of the secret should be maximum " + method.getMinKeyLength()
                    + " bytes long (was: " + secret.length + ")"));
        }
        return super.preSaveHook(notificationEndpoint);
    }

    @Override
    protected Mono<NotificationEndpoint> preUpdateHook(NotificationEndpoint original, NotificationEndpoint update) {
        if (update.getSecret() == null) {
            update.setSecret(original.getSecret());
        }
        return super.preUpdateHook(original, update);
    }

    @Override
    public NotificationEndpointRepository getRepository() {
        return notificationEndpointRepository;
    }

    @Transactional
    @Override
    public Mono<Void> receiveNotification(ServerHttpRequest request, UUID endpointID) {
        return findById(endpointID)
                .flatMap(notificationEndpoint -> {
                    String subscriptionID = notificationEndpoint.getSubscriptionID();
                    if (request.getMethod() == HttpMethod.HEAD) {
                        // verify request - we are happy at this point. (note that we forgive missing subscriptionIDs
                        // as the endpoint can be verified before we know the Subscription ID)
                        return Mono.empty();
                    }
                    if (subscriptionID == null) {
                        // We do not have a subscription ID yet. Assume that it is not a match
                        // Ideally, we would include a "Retry-After" header as well.
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
                    }
                    return messageSignatureHandler.verifyRequest(request,
                            notificationEndpoint.getSubscriptionID(),
                            notificationEndpoint.getSecret(),
                            eventConverter());
                }).flatMap(signatureResult -> {
                    if (!signatureResult.isValid()) {
                        // The unconditional usage of UNAUTHORIZED is deliberate. We are not interested in letting
                        // the caller know why we are rejecting - just that we are not happy.  Telling more might
                        // inform them of a bug or enable them to guess part of the secret.
                        log.debug("Rejecting message because: " + signatureResult.getResult());
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                    }

                    Mono<? extends Event> result = Mono.empty();
                    for (Event event : signatureResult.getParsed()) {
                        event.setNewRecord(true);
                        if (event instanceof TransportCallBasedEvent) {
                            TransportCallBasedEvent tcbe = (TransportCallBasedEvent)event;
                            TransportCallTO transportCallTO = tcbe.getTransportCall();
                            result = result.then(transportCallTOService.findById(transportCallTO.getTransportCallID()))
                                    .switchIfEmpty(transportCallTOService.create(transportCallTO))
                                    .doOnNext(((TransportCallBasedEvent) event)::setTransportCall)
                                    .doOnNext(tc -> ((TransportCallBasedEvent) event).setTransportCallID(tc.getTransportCallID()))
                                    .flatMap(ignored -> genericEventService.findByEventTypeAndEventID(event.getEventType(), event.getEventID()))
                                    .switchIfEmpty(
                                            genericEventService.create(event)
                                            .flatMap(savedEvent -> timestampNotificationMailService.sendEmailNotificationsForEvent(event)
                                                    .then(Mono.just(event))
                                            )
                                    ).thenReturn(event);

                        }
                    }
                    return result.then();
                });
    }

    private MessageSignatureHandler.Converter<List<Event>> eventConverter() {
        return (payload -> objectMapper.readValue(payload, EVENT_TYPE_REFERENCE));
    }

}
