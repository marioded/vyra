/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marioded.vyra.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Routes incoming messages by {@link MessageKind}: responses to the {@link RequestManager},
 * requests to the registered handler (dispatched to the handler executor), events to the channel
 * subscribers.
 */
final class MessageDispatcher {

  private static final Logger LOGGER = Logger.getLogger(MessageDispatcher.class.getName());
  private final HandlerRegistry handlerRegistry;
  private final RequestManager requestManager;
  private final Protocol protocol;
  private final ExecutorService handlerExecutor;

  MessageDispatcher(
      @NotNull HandlerRegistry handlerRegistry,
      @NotNull RequestManager requestManager,
      @NotNull Protocol protocol,
      @NotNull ExecutorService handlerExecutor) {
    this.handlerRegistry = handlerRegistry;
    this.requestManager = requestManager;
    this.protocol = protocol;
    this.handlerExecutor = handlerExecutor;
  }

  @NotNull
  CompletionStage<Void> onMessage(@NotNull Message message) {
    switch (message.kind()) {
      case RESPONSE -> requestManager.handleResponse(message);
      case REQUEST, BROADCAST_REQUEST -> handleRequest(message);
      case EVENT -> handleEvent(message);
    }

    return CompletableFuture.completedFuture(null);
  }

  private void handleRequest(@NotNull Message message) {
    boolean isBroadcast = message.kind() == MessageKind.BROADCAST_REQUEST;
    HandlerRegistry.HandlerEntry entry =
        handlerRegistry.lookupRequest(message.destination(), message.messageType());

    if (entry == null) {
      if (!isBroadcast) {
        protocol.sendErrorResponse(
            message,
            RemoteError.Code.UNKNOWN_MESSAGE_TYPE,
            "No handler registered for messageType '"
                + message.messageType()
                + "' on target '"
                + message.destination()
                + "'");
      }
      return;
    }

    try {
      handlerExecutor.execute(
          () -> {
            try {
              Object request = protocol.convertPayload(message.payload(), entry.requestType());
              CompletionStage<Object> result = entry.handler().apply(request);
              result.whenComplete(
                  (response, error) -> {
                    if (error != null) {
                      if (!isBroadcast) {
                        protocol.sendErrorResponse(
                            message, RemoteError.Code.HANDLER_ERROR, String.valueOf(error));
                      }
                    } else if (response == null) {
                      if (!isBroadcast) {
                        protocol.sendErrorResponse(
                            message,
                            RemoteError.Code.HANDLER_ERROR,
                            "handler returned null response");
                      }
                    } else {
                      protocol.sendResponse(message, response);
                    }
                  });
            } catch (Exception e) {
              if (!isBroadcast) {
                protocol.sendErrorResponse(
                    message, RemoteError.Code.HANDLER_ERROR, String.valueOf(e));
              }
            }
          });
    } catch (RejectedExecutionException e) {
      if (!isBroadcast) {
        protocol.sendErrorResponse(
            message,
            RemoteError.Code.SERVER_BUSY,
            "Handler executor rejected request processing: " + e.getMessage());
      }
    }
  }

  private void handleEvent(@NotNull Message message) {
    List<HandlerRegistry.EventSubscriber> subscribers =
        handlerRegistry.eventSubscribers(message.destination());
    if (subscribers == null || subscribers.isEmpty()) {
      return;
    }
    try {
      handlerExecutor.execute(
          () -> {
            for (HandlerRegistry.EventSubscriber subscriber : subscribers) {
              try {
                Object event = protocol.convertPayload(message.payload(), subscriber.eventType());
                subscriber.consumer().accept(event);
              } catch (Exception e) {
                LOGGER.severe(
                    "Error processing event of type %s on channel '%s': %s"
                        .formatted(message.messageType(), message.destination(), e));
              }
            }
          });
    } catch (RejectedExecutionException e) {
      // Events have no request/response semantics: a rejected executor drops
      // the event with a warning instead of putting a semantically wrong error
      // envelope on the wire.
      LOGGER.severe("Handler executor rejected event processing: " + e.getMessage());
    }
  }
}
