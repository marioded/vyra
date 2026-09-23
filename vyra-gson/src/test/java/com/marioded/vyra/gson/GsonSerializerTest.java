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
package com.marioded.vyra.gson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.marioded.vyra.core.Message;
import com.marioded.vyra.core.MessageKind;
import com.marioded.vyra.core.RemoteError;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GsonSerializerTest {

  record EchoRequest(String text) {}

  record PlayerJoined(String playerId) {}

  @Test
  void roundTrip() {
    GsonSerializer serializer = new GsonSerializer();
    byte[] bytes = serializer.serialize(new EchoRequest("hi"));
    EchoRequest back = serializer.deserialize(bytes, EchoRequest.class);
    assertEquals("hi", back.text());
  }

  @Test
  void customGsonIsHonored() {
    Gson gson =
        new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();
    GsonSerializer serializer = new GsonSerializer(gson);

    byte[] bytes = serializer.serialize(new PlayerJoined("p1"));
    String json = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(json.contains("\"player_id\""), json);

    PlayerJoined back = serializer.deserialize(bytes, PlayerJoined.class);
    assertEquals("p1", back.playerId());
  }

  @Test
  void messageEnvelopeRoundTrip() {
    GsonSerializer serializer = new GsonSerializer();
    Message message =
        new Message(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            null,
            "node-1",
            MessageKind.REQUEST,
            "backend",
            "player.get",
            1_700_000_000_000L,
            new EchoRequest("hi"),
            Map.of("trace-id", "abc", "tenant", "t1"));

    Message back = serializer.deserialize(serializer.serialize(message), Message.class);

    assertEquals(message.messageId(), back.messageId());
    assertNull(back.correlationId());
    assertEquals(message.source(), back.source());
    assertEquals(message.kind(), back.kind());
    assertEquals(message.destination(), back.destination());
    assertEquals(message.messageType(), back.messageType());
    assertEquals(message.timestamp(), back.timestamp());
    assertEquals(new EchoRequest("hi"), serializer.convert(back.payload(), EchoRequest.class));
    assertEquals(message.headers(), back.headers());
  }

  @Test
  void messageEnvelopeWithNullPayloadRoundTrips() {
    GsonSerializer serializer = new GsonSerializer();
    Message message =
        new Message(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "node-1",
            MessageKind.EVENT,
            "game-events",
            "player.joined",
            1_700_000_000_000L,
            null,
            Map.of("trace-id", "abc"));

    Message back = serializer.deserialize(serializer.serialize(message), Message.class);

    assertEquals(message.messageId(), back.messageId());
    assertEquals(message.correlationId(), back.correlationId());
    assertEquals(message.source(), back.source());
    assertEquals(message.kind(), back.kind());
    assertEquals(message.destination(), back.destination());
    assertEquals(message.messageType(), back.messageType());
    assertEquals(message.timestamp(), back.timestamp());
    assertNull(back.payload());
    assertEquals(message.headers(), back.headers());
  }

  @Test
  void messagePayloadIsEmbeddedAsReadableJson() {
    GsonSerializer serializer = new GsonSerializer();
    Message message =
        new Message(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            null,
            "node-1",
            MessageKind.REQUEST,
            "backend",
            "player.get",
            1_700_000_000_000L,
            new PlayerJoined("p1"),
            Map.of("trace-id", "abc"));

    String json = new String(serializer.serialize(message), StandardCharsets.UTF_8);

    assertTrue(json.contains("\"payload\":{\"playerId\":\"p1\"}"), json);
    assertFalse(json.contains("\"payload\":\""), json);
  }

  @Test
  void remoteErrorRoundTrip() {
    GsonSerializer serializer = new GsonSerializer();
    RemoteError error = new RemoteError(RemoteError.Code.HANDLER_ERROR, "boom");

    RemoteError back = serializer.deserialize(serializer.serialize(error), RemoteError.class);

    assertEquals(error.code(), back.code());
    assertEquals(error.message(), back.message());
  }
}
