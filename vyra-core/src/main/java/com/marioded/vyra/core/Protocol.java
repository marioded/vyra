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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wire-level protocol that handles message creation, payload serialization and transport. Owns the
 * {@link Serializer} and {@link MessageRegistry} and is the only component that builds {@link
 * Message} envelopes and calls {@link Transport#send}.
 */
final class Protocol {

  /** Reserved messageType for the remote-error envelope. */
  static final String ERROR_MESSAGE_TYPE = "vyra.error";

  private final String nodeId;
  private final Transport transport;
  private final MessageRegistry registry;
  private final Serializer serializer;

  Protocol(
      @NotNull String nodeId,
      @NotNull Transport transport,
      @NotNull MessageRegistry registry,
      @NotNull Serializer serializer) {
    this.nodeId = nodeId;
    this.transport = transport;
    this.registry = registry;
    this.serializer = serializer;
  }

  /** Resolves the messageType and carries the payload object. */
  @NotNull
  SerializedPayload serialize(@NotNull Object value) {
    return new SerializedPayload(registry.getMessageType(value.getClass()), value);
  }

  /**
   * Converts a decoded generic payload to the target type (the single conversion point in core).
   */
  <T> @Nullable T convertPayload(@Nullable Object payload, @NotNull Class<T> type) {
    if (payload == null) {
      return null;
    }

    return serializer.convert(payload, type);
  }

  /** Serializes a full message envelope with the user-configured serializer. */
  byte @NotNull [] encodeEnvelope(@NotNull Message message) {
    return serializer.serialize(Objects.requireNonNull(message, "message"));
  }

  /** Deserializes a full message envelope with the user-configured serializer. */
  @NotNull
  Message decodeEnvelope(byte @NotNull [] bytes) {
    return serializer.deserialize(bytes, Message.class);
  }

  /** Builds a request envelope addressed to {@code destination}. */
  @NotNull
  Message buildRequest(
      @NotNull UUID messageId,
      @NotNull MessageKind kind,
      @NotNull String destination,
      @NotNull String messageType,
      @NotNull Object payload) {
    return new Message(
        messageId,
        null,
        nodeId,
        kind,
        destination,
        messageType,
        System.currentTimeMillis(),
        payload,
        Map.of());
  }

  /** Builds a standard request envelope addressed to {@code destination}. */
  @NotNull
  Message buildRequest(
      @NotNull UUID messageId,
      @NotNull String destination,
      @NotNull String messageType,
      @NotNull Object payload) {
    return buildRequest(messageId, MessageKind.REQUEST, destination, messageType, payload);
  }

  /** Builds an event envelope addressed to {@code channel}. */
  @NotNull
  Message buildEvent(
      @NotNull UUID messageId,
      @NotNull String channel,
      @NotNull String messageType,
      @NotNull Object payload) {
    return new Message(
        messageId,
        null,
        nodeId,
        MessageKind.EVENT,
        channel,
        messageType,
        System.currentTimeMillis(),
        payload,
        Map.of());
  }

  /** Sends an envelope through the transport. */
  @NotNull
  CompletionStage<Void> send(@NotNull Message message) {
    return transport.send(message.kind(), message.destination(), encodeEnvelope(message));
  }

  /** Sends a typed response back to the request's source. */
  void sendResponse(@NotNull Message request, @NotNull Object response) {
    try {
      SerializedPayload serialized = serialize(response);
      Message responseMessage =
          new Message(
              UUID.randomUUID(),
              request.messageId(),
              nodeId,
              MessageKind.RESPONSE,
              request.source(),
              serialized.messageType(),
              System.currentTimeMillis(),
              serialized.payload(),
              Map.of());
      send(responseMessage);
    } catch (Exception e) {
      sendErrorResponse(request, RemoteError.Code.SERIALIZATION_ERROR, String.valueOf(e));
    }
  }

  /** Sends the reserved error envelope back to the request's source. */
  void sendErrorResponse(
      @NotNull Message request, @NotNull RemoteError.Code code, @NotNull String message) {
    Message errorMessage =
        new Message(
            UUID.randomUUID(),
            request.messageId(),
            nodeId,
            MessageKind.RESPONSE,
            request.source(),
            ERROR_MESSAGE_TYPE,
            System.currentTimeMillis(),
            new RemoteError(code, message),
            Map.of());
    send(errorMessage);
  }

  record SerializedPayload(@NotNull String messageType, @NotNull Object payload) {}
}
