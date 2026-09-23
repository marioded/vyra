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

import com.marioded.vyra.core.exception.VyraException;
import com.marioded.vyra.core.exception.VyraTransportException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Default {@link Vyra} implementation. Instances can be created through {@link VyraBuilder}.
 *
 * <p>The main components are:
 *
 * <ul>
 *   <li>{@link Protocol}: envelope construction, serialization and transport.
 *   <li>{@link RequestManager}: outbound request lifecycle such as timeouts and responses.
 *   <li>{@link HandlerRegistry}: request handlers and event subscribers of incoming messages.
 *   <li>{@link MessageDispatcher}: routes incoming messages to the request manager, registered
 *       handlers or subscribers.
 * </ul>
 *
 * <p>The transport is always closed by {@link #close()} but the handler executor is closed only if
 * this instance created it.
 */
final class DefaultVyra implements Vyra {

  private static final Logger LOGGER = Logger.getLogger(DefaultVyra.class.getName());

  private final Transport transport;
  private final MessageRegistry registry;
  private final ExecutorService handlerExecutor;
  private final Protocol protocol;
  private final RequestManager requestManager;
  private final HandlerRegistry handlerRegistry;
  private final MessageDispatcher dispatcher;

  private final boolean ownsHandlerExecutor;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  DefaultVyra(
      @NotNull String nodeId,
      @NotNull Transport transport,
      @NotNull MessageRegistry registry,
      @NotNull Serializer serializer,
      @NotNull ExecutorService handlerExecutor,
      boolean ownsHandlerExecutor) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
    this.ownsHandlerExecutor = ownsHandlerExecutor;
    this.protocol =
        new Protocol(nodeId, transport, registry, Objects.requireNonNull(serializer, "serializer"));
    this.requestManager = new RequestManager(handlerExecutor, protocol, nodeId, closed);
    this.handlerRegistry = new HandlerRegistry();
    this.dispatcher =
        new MessageDispatcher(handlerRegistry, requestManager, protocol, handlerExecutor);

    transport.setMessageHandler(this::onEnvelope);
    transport.subscribe(MessageKind.RESPONSE, nodeId);
  }

  /**
   * Receive logic: the transport hands over raw bytes on its thread then the user-configured
   * serializer is user code and so it must never run on transport threads, so decoding is
   * dispatched to the handler executor.
   *
   * <p>Unparseable envelopes are logged and dropped. If the message is from another Vyra node, be
   * sure to use the same serializer. A rejected executor degrades gracefully cause it would stop
   * the handler thread.
   */
  private @NotNull CompletionStage<Void> onEnvelope(byte @NotNull [] bytes) {
    try {
      handlerExecutor.execute(
          () -> {
            try {
              Message message = protocol.decodeEnvelope(bytes);

              dispatcher.onMessage(message);
            } catch (Exception e) {
              // useful for systems using the same channel names
              if (System.getProperty("vyra.disableUnparseableMessageLogging") == null) {
                LOGGER.log(
                    Level.WARNING,
                    "Dropping unparseable message. If the message arrives from another Vyra node, be sure to use the same serializer.");
              }
            }
          });
      return CompletableFuture.completedFuture(null);
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void register(@NotNull String messageType, @NotNull Class<?> type) {
    ensureOpen();
    registry.register(messageType, type);
  }

  @Override
  public <T> @NotNull CompletionStage<T> request(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout) {
    ensureOpen();
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(responseType, "responseType");
    Objects.requireNonNull(timeout, "timeout");

    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }

    return requestManager.request(destination, request, responseType, timeout);
  }

  @Override
  public <T> @NotNull CompletionStage<T> broadcastRequest(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout) {
    ensureOpen();
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(responseType, "responseType");
    Objects.requireNonNull(timeout, "timeout");

    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }

    return requestManager.broadcastRequest(destination, request, responseType, timeout);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T, R> void handle(
      @NotNull String target,
      @NotNull Class<T> requestType,
      @NotNull Function<T, CompletionStage<R>> handler) {
    ensureOpen();
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestType, "requestType");
    Objects.requireNonNull(handler, "handler");

    String messageType = registry.getMessageType(requestType);
    Function<Object, CompletionStage<Object>> erased =
        (Function<Object, CompletionStage<Object>>) (Function<?, ?>) handler;

    handlerRegistry.registerRequest(target, messageType, requestType, erased);
    transport.subscribe(MessageKind.REQUEST, target);
    transport.subscribe(MessageKind.BROADCAST_REQUEST, target);
  }

  @Override
  public @NotNull CompletionStage<Void> publish(@NotNull String channel, @NotNull Object event) {
    ensureOpen();
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(event, "event");

    try {
      Protocol.SerializedPayload serialized = protocol.serialize(event);
      Message message =
          protocol.buildEvent(
              UUID.randomUUID(), channel, serialized.messageType(), serialized.payload());
      CompletionStage<Void> sent = protocol.send(message);
      CompletableFuture<Void> result = new CompletableFuture<>();

      sent.whenComplete(
          (v, error) -> {
            try {
              handlerExecutor.execute(
                  () -> {
                    if (error != null) {
                      result.completeExceptionally(error);
                    } else {
                      result.complete(null);
                    }
                  });
            } catch (RejectedExecutionException e) {
              result.completeExceptionally(e);
            }
          });

      return result;
    } catch (VyraException e) {
      return CompletableFuture.failedFuture(e);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(
          new VyraTransportException("Failed to publish event to '" + channel + "'", e));
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> void subscribe(
      @NotNull String channel, @NotNull Class<T> eventType, @NotNull Consumer<T> handler) {
    ensureOpen();
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(handler, "handler");

    Consumer<Object> erased = (Consumer<Object>) handler;
    handlerRegistry.registerEvent(channel, eventType, erased);
    transport.subscribe(MessageKind.EVENT, channel);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      requestManager.close();
      transport.close();
      if (ownsHandlerExecutor) {
        handlerExecutor.shutdownNow();
      }
    }
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("Vyra instance is closed");
    }
  }
}
