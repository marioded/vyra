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

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable message envelope. A single envelope is used for requests, responses and events; the
 * {@link MessageKind} distinguishes the role and the {@code destination} carries the bare logical
 * destination (service target, node ID or channel). The {@link Transport} used is unaware of the
 * routing semantics and only moves the envelope.
 *
 * @param messageId unique message identifier; referenced by responses via {@code correlationId}
 * @param correlationId correlation target or {@code null} for events and initial requests
 * @param source node ID of the sender
 * @param kind message role: request, response or event
 * @param destination bare logical destination
 * @param messageType stable protocol identifier; never a Java class name
 * @param timestamp creation time in milliseconds since epoch
 * @param payload application payload object
 * @param headers out-of-band metadata (content type, tracing, tenant, ...); never {@code null}
 */
public record Message(
    @NotNull UUID messageId,
    @Nullable UUID correlationId,
    @NotNull String source,
    @NotNull MessageKind kind,
    @NotNull String destination,
    @NotNull String messageType,
    long timestamp,
    @Nullable Object payload,
    @Nullable Map<String, String> headers)
    implements Serializable {

  public Message {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(messageType, "messageType");
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  /** Convenience constructor for messages without headers. */
  public Message(
      @NotNull UUID messageId,
      @Nullable UUID correlationId,
      @NotNull String source,
      @NotNull MessageKind kind,
      @NotNull String destination,
      @NotNull String messageType,
      long timestamp,
      @Nullable Object payload) {
    this(
        messageId,
        correlationId,
        source,
        kind,
        destination,
        messageType,
        timestamp,
        payload,
        Map.of());
  }
}
