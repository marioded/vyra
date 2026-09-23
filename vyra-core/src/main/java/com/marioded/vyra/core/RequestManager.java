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
import com.marioded.vyra.core.exception.VyraRemoteException;
import com.marioded.vyra.core.exception.VyraSerializationException;
import com.marioded.vyra.core.exception.VyraTimeoutException;
import com.marioded.vyra.core.exception.VyraTransportException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the outbound request lifecycle: pending-request correlation, timeouts and response
 * completion.
 *
 * <p>Incoming responses are completed on the handler executor so user callbacks never run on
 * transport threads.
 */
final class RequestManager {

  private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timeoutScheduler;
  private final ExecutorService handlerExecutor;
  private final Protocol protocol;
  private final AtomicBoolean closed;

  RequestManager(
      @NotNull ExecutorService handlerExecutor,
      @NotNull Protocol protocol,
      @NotNull String nodeId,
      @NotNull AtomicBoolean closed) {
    this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
    this.protocol = Objects.requireNonNull(protocol, "protocol");
    this.timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "vyra-timeout-" + nodeId);
              t.setDaemon(true);
              return t;
            });
    this.closed = Objects.requireNonNull(closed, "closed");
  }

  private static void cancelTimeout(@NotNull PendingRequest pending) {
    ScheduledFuture<?> task = pending.timeoutTask().get();
    if (task != null) {
      task.cancel(false);
    }
  }

  /**
   * Sends a standard 1-to-1 worker queue request and returns a stage completing with the typed
   * response, or failing with {@link VyraTimeoutException} if no response arrives within {@code
   * timeout}.
   */
  <T> @NotNull CompletionStage<T> request(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout) {
    return doRequest(destination, request, responseType, timeout, MessageKind.REQUEST);
  }

  /**
   * Sends a 1-to-N broadcast request and returns a stage completing with the first non-null
   * response, or failing with {@link VyraTimeoutException} if no response arrives within {@code
   * timeout}.
   */
  <T> @NotNull CompletionStage<T> broadcastRequest(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout) {
    return doRequest(destination, request, responseType, timeout, MessageKind.BROADCAST_REQUEST);
  }

  private <T> @NotNull CompletionStage<T> doRequest(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout,
      @NotNull MessageKind kind) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new VyraException("Vyra instance is closed"));
    }

    Protocol.SerializedPayload serialized;
    try {
      serialized = protocol.serialize(request);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(
          e instanceof VyraException vyraException
              ? vyraException
              : new VyraSerializationException("Failed to resolve messageType for request", e));
    }

    UUID messageId = UUID.randomUUID();
    CompletableFuture<Object> future = new CompletableFuture<>();
    AtomicReference<ScheduledFuture<?>> timeoutTaskRef = new AtomicReference<>();
    PendingRequest pending = new PendingRequest(future, responseType, timeoutTaskRef);

    pendingRequests.put(messageId, pending);

    if (closed.get()) {
      pendingRequests.remove(messageId);
      return CompletableFuture.failedFuture(new VyraException("Vyra instance is closed"));
    }

    long timeoutMs = timeout.toMillis();
    try {
      ScheduledFuture<?> timeoutTask =
          timeoutScheduler.schedule(
              () -> onTimeout(messageId, destination, timeoutMs), timeoutMs, TimeUnit.MILLISECONDS);
      timeoutTaskRef.set(timeoutTask);

      if (!pendingRequests.containsKey(messageId)) {
        timeoutTask.cancel(false);
      }
    } catch (RejectedExecutionException e) {
      pendingRequests.remove(messageId);
      return CompletableFuture.failedFuture(new VyraException("Vyra instance is closed", e));
    }

    Message message =
        protocol.buildRequest(
            messageId, kind, destination, serialized.messageType(), serialized.payload());

    try {
      protocol
          .send(message)
          .whenComplete((v, error) -> onSendComplete(messageId, destination, error));
    } catch (Exception e) {
      PendingRequest removed = pendingRequests.remove(messageId);

      if (removed != null) {
        cancelTimeout(removed);
        Throwable failure =
            e instanceof VyraException vyraException
                ? vyraException
                : new VyraTransportException("Failed to send request to '" + destination + "'", e);
        removed.future().completeExceptionally(failure);
      }
    }

    @SuppressWarnings("unchecked")
    CompletionStage<T> result = (CompletionStage<T>) future;

    return result;
  }

  private void onTimeout(UUID messageId, String destination, long timeoutMs) {
    PendingRequest pending = pendingRequests.remove(messageId);
    if (pending != null) {
      VyraTimeoutException failure =
          new VyraTimeoutException(
              "Request to '" + destination + "' timed out after " + timeoutMs + " ms");
      completeExceptionallyAsync(pending.future(), failure);
    }
  }

  private void onSendComplete(UUID messageId, String destination, @Nullable Throwable error) {
    if (error != null) {
      PendingRequest removed = pendingRequests.remove(messageId);
      if (removed != null) {
        cancelTimeout(removed);
        VyraTransportException failure =
            new VyraTransportException("Failed to send request to '" + destination + "'", error);
        completeExceptionallyAsync(removed.future(), failure);
      }
    }
  }

  private void completeExceptionallyAsync(CompletableFuture<Object> future, Throwable failure) {
    try {
      handlerExecutor.execute(() -> future.completeExceptionally(failure));
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(failure);
    }
  }

  /**
   * Completes the pending request matching the response's correlationId, if any. Already invoked on
   * the handler executor (via {@link DefaultVyra}'s decode task), so the response is processed
   * inline: user callbacks attached to the pending future run on the handler executor, never on a
   * transport thread, and a saturated executor cannot spuriously reject an on-time response.
   */
  void handleResponse(@NotNull Message message) {
    UUID correlationId = message.correlationId();
    if (correlationId == null) {
      return;
    }

    PendingRequest pending = pendingRequests.remove(correlationId);
    if (pending == null) {
      return;
    }
    cancelTimeout(pending);

    try {
      if (Protocol.ERROR_MESSAGE_TYPE.equals(message.messageType())) {
        RemoteError error = protocol.convertPayload(message.payload(), RemoteError.class);

        if (error == null) {
          pending
              .future()
              .completeExceptionally(
                  new VyraSerializationException("Malformed error envelope: missing payload"));
        } else {
          pending.future().completeExceptionally(new VyraRemoteException(error));
        }
      } else {
        Object response = protocol.convertPayload(message.payload(), pending.responseType());
        pending.future().complete(response);
      }
    } catch (Exception ex) {
      pending
          .future()
          .completeExceptionally(
              ex instanceof VyraException vyraException
                  ? vyraException
                  : new VyraSerializationException("Failed to process response", ex));
    }
  }

  /** Fails all pending requests and stops the timeout scheduler. */
  void close() {
    VyraException closeException = new VyraException("Vyra instance closed");

    for (PendingRequest pending : pendingRequests.values()) {
      cancelTimeout(pending);
      pending.future().completeExceptionally(closeException);
    }

    pendingRequests.clear();
    timeoutScheduler.shutdownNow();
  }

  private record PendingRequest(
      @NotNull CompletableFuture<Object> future,
      @NotNull Class<?> responseType,
      @NotNull AtomicReference<ScheduledFuture<?>> timeoutTask) {}
}
