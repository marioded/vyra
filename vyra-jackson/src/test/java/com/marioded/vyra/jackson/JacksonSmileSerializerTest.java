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
package com.marioded.vyra.jackson;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.marioded.vyra.core.Message;
import com.marioded.vyra.core.MessageKind;
import com.marioded.vyra.core.RemoteError;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JacksonSmileSerializerTest {

  record EchoRequest(String text) {}

  record BinaryPayload(String name, byte[] data) {}

  record PlayerJoined(String playerId, int score) {}

  @Test
  void messageEnvelopeRoundTrip() {
    JacksonSerializer serializer = JacksonSerializer.smile();
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

    assertNotNull(back);
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
    JacksonSerializer serializer = JacksonSerializer.smile();
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

    assertNotNull(back);
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
  void outputIsBinary() {
    JacksonSerializer serializer = JacksonSerializer.smile();
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
            Map.of());

    byte[] bytes = serializer.serialize(message);

    assertTrue(bytes.length >= 3, "Smile output must carry the 3-byte magic header");
    assertEquals(0x3A, bytes[0] & 0xFF);
    assertEquals(0x29, bytes[1] & 0xFF);
    assertEquals(0x0A, bytes[2] & 0xFF);
  }

  @Test
  void payloadWithByteArrayRoundTrips() {
    JacksonSerializer serializer = JacksonSerializer.smile();
    byte[] data = {1, 2, 3, -1, 0, 127};
    BinaryPayload payload = new BinaryPayload("blob", data);

    Message message =
        new Message(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            null,
            "node-1",
            MessageKind.REQUEST,
            "backend",
            "binary.test",
            1_700_000_000_000L,
            payload,
            Map.of());

    Message back = serializer.deserialize(serializer.serialize(message), Message.class);
    assertNotNull(back);
    BinaryPayload restored = serializer.convert(back.payload(), BinaryPayload.class);
    assertNotNull(restored);

    assertEquals(payload.name(), restored.name());
    assertArrayEquals(data, restored.data());
  }

  @Test
  void remoteErrorRoundTrip() {
    JacksonSerializer serializer = JacksonSerializer.smile();
    RemoteError error = new RemoteError(RemoteError.Code.HANDLER_ERROR, "boom");

    RemoteError back = serializer.deserialize(serializer.serialize(error), RemoteError.class);
    assertNotNull(back);
    assertEquals(error.code(), back.code());
    assertEquals(error.message(), back.message());
  }

  @Test
  void customMapperIsHonored() {
    ObjectMapper mapper = new ObjectMapper(new SmileFactory());
    mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    JacksonSerializer serializer = JacksonSerializer.smile(mapper);

    byte[] bytes = serializer.serialize(new PlayerJoined("p1", 42));
    String text = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(text.contains("player_id"), text);

    PlayerJoined back = serializer.deserialize(bytes, PlayerJoined.class);

    assertNotNull(back);
    assertEquals("p1", back.playerId());
    assertEquals(42, back.score());
  }
}
