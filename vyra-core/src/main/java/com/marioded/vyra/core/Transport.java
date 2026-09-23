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

import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Transport is strictly responsible for moving raw envelope bytes to their destination. It is
 * completely unaware of the routing semantics (request, response or event); the core owns those.
 * The transport needs the {@link MessageKind} and the bare destination to choose the most efficient
 * delivery mechanism for the underlying infrastructure (e.g. worker queues for requests, broadcast
 * for events) and to build its keys/channels.
 *
 * <p>The envelope bytes are produced by the core using the configured {@link Serializer} then the
 * transport treats them as opaque.
 */
public interface Transport extends AutoCloseable {

  /**
   * Sends the raw envelope bytes to the given destination. The returned stage completes when the
   * transport has handed the bytes to the underlying infrastructure (or, where supported, when the
   * infrastructure confirms receipt).
   */
  @NotNull
  CompletionStage<Void> send(
      @NotNull MessageKind kind, @NotNull String destination, byte @NotNull [] envelope);

  /**
   * Starts delivering messages of the given {@code kind} addressed to {@code destination} to the
   * message handler.
   */
  void subscribe(@NotNull MessageKind kind, @NotNull String destination);

  /** Sets the handler invoked for every delivered message. Must be set before subscribing. */
  void setMessageHandler(@NotNull Function<byte[], CompletionStage<Void>> handler);

  @Override
  void close();
}
