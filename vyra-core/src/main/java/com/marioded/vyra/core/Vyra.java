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
import com.marioded.vyra.core.exception.VyraTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Public entry point for Vyra: typed asynchronous request/response and publish/subscribe events
 * over a {@link Transport}.
 *
 * <p>Create instances through {@link #builder()} or through the convenience factories in the
 * transport modules.
 *
 * <p>All methods are thread-safe. User handlers and listeners run on the Vyra handler executor,
 * never on a transport thread.
 */
public interface Vyra extends AutoCloseable {

  /**
   * Starts building a Vyra instance. The transport and the payload serializer are required;
   * everything else has a default. Transport modules provide pre-configured builders (e.g. {@code
   * RedisVyra.redis(client)}).
   */
  static @NotNull VyraBuilder builder() {
    return new DefaultVyraBuilder();
  }

  /**
   * Registers the stable wire identifier for {@code type}. Must be called before the type is used
   * in {@link #request}, {@link #handle} or {@link #subscribe}.
   */
  void register(@NotNull String messageType, @NotNull Class<?> type);

  /**
   * Sends a request to the logical {@code destination} and returns a stage completing with the
   * typed response, or failing with {@link VyraTimeoutException} if no response arrives within
   * {@code timeout}.
   *
   * <p>Note: {@code responseType} must be a concrete class. Generic types (e.g. {@code List<Foo>})
   * cannot be passed as {@code Class}. Wrap them in a concrete record instead.
   */
  <T> @NotNull CompletionStage<T> request(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout);

  /**
   * Sends a broadcast request to all active nodes handling {@code destination} and returns a stage
   * completing with the first non-null response, or failing with {@link VyraTimeoutException} if
   * all nodes remain silent or no response arrives within {@code timeout}.
   *
   * <p>Unlike standard worker-queue {@link #request(String, Object, Class, Duration)} which routes
   * to a single worker, a broadcast request is delivered concurrently to all nodes listening on
   * {@code destination}. Handlers that return {@code null} remain silent; the first node to return
   * a non-null response completes the returned stage.
   */
  <T> @NotNull CompletionStage<T> broadcastRequest(
      @NotNull String destination,
      @NotNull Object request,
      @NotNull Class<T> responseType,
      @NotNull Duration timeout);

  /**
   * Registers a handler for requests of {@code requestType} addressed to the logical {@code
   * target}, and starts listening for them. The handler runs on the Vyra handler executor, never on
   * a transport thread.
   */
  <T, R> void handle(
      @NotNull String target,
      @NotNull Class<T> requestType,
      @NotNull Function<T, CompletionStage<R>> handler);

  /** Publishes an event to {@code channel} (at-most-once delivery). */
  @NotNull
  CompletionStage<Void> publish(@NotNull String channel, @NotNull Object event);

  /** Subscribes {@code handler} to events of {@code eventType} on {@code channel}. */
  <T> void subscribe(
      @NotNull String channel, @NotNull Class<T> eventType, @NotNull Consumer<T> handler);

  /**
   * Closes the Vyra instance, releasing all resources and refusing further requests, handlers or
   * subscriptions. Any pending requests will complete exceptionally with {@link VyraException}.
   */
  @Override
  void close();
}
