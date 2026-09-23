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
package com.marioded.vyra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.marioded.vyra.core.RemoteError;
import com.marioded.vyra.core.Vyra;
import com.marioded.vyra.core.exception.VyraRemoteException;
import com.marioded.vyra.core.exception.VyraTimeoutException;
import com.marioded.vyra.jackson.JacksonSerializer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisTransportIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private static RedisClient client;

  @BeforeAll
  static void setUp() {
    client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
  }

  @AfterAll
  static void tearDown() {
    if (client != null) {
      client.shutdown();
    }
  }

  record EchoRequest(String text) {}

  record EchoResponse(String text) {}

  record JoinedEvent(String playerId) {}

  private Vyra node(String id) {
    Vyra v =
        Vyra.builder()
            .transport(new RedisTransport(client))
            .nodeId(id)
            .serializer(JacksonSerializer.jackson())
            .build();
    v.register("echo.req", EchoRequest.class);
    v.register("echo.res", EchoResponse.class);
    v.register("joined", JoinedEvent.class);
    return v;
  }

  @Test
  void requestResponseRoundTrip() throws Exception {
    Vyra backend = node("backend-1");
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse(req.text())));

    Vyra client = node("client-1");
    EchoResponse response =
        client
            .request("backend", new EchoRequest("hello"), EchoResponse.class, Duration.ofSeconds(5))
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals("hello", response.text());

    backend.close();
    client.close();
  }

  @Test
  void requestTimesOutWhenNoNodeListens() {
    Vyra client = node("client-2");
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "nowhere", new EchoRequest("x"), EchoResponse.class, Duration.ofMillis(300))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS));
    assertInstanceOf(VyraTimeoutException.class, ex.getCause());
    client.close();
  }

  @Test
  void remoteHandlerErrorPropagates() {
    Vyra backend = node("backend-3");
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.failedFuture(new IllegalStateException("boom")));

    Vyra client = node("client-3");
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS));
    assertInstanceOf(VyraRemoteException.class, ex.getCause());
    assertEquals(RemoteError.Code.HANDLER_ERROR, ((VyraRemoteException) ex.getCause()).getCode());

    backend.close();
    client.close();
  }

  @Test
  void eventsRoundTrip() throws Exception {
    Vyra backend = node("backend-4");
    BlockingQueue<JoinedEvent> received = new LinkedBlockingQueue<>();
    backend.subscribe("game-events", JoinedEvent.class, received::add);

    Vyra client = node("client-4");
    client
        .publish("game-events", new JoinedEvent("p1"))
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

    JoinedEvent event = received.poll(10, TimeUnit.SECONDS);
    assertEquals("p1", event.playerId());

    backend.close();
    client.close();
  }

  @Test
  void malformedMessageOnChannelDoesNotKillTheNode() throws Exception {
    Vyra backend = node("backend-5");
    BlockingQueue<JoinedEvent> received = new LinkedBlockingQueue<>();
    backend.subscribe("game-events", JoinedEvent.class, received::add);

    // garbage bytes directly to the channel, bypassing the
    // envelope serializer. the transport must log and drop it without crashing lettuce because the
    // final step is handled through the dedicated executor
    try (StatefulRedisPubSubConnection<byte[], byte[]> raw =
        client.connectPubSub(ByteArrayCodec.INSTANCE)) {
      raw.async()
          .publish(
              "evt:game-events".getBytes(StandardCharsets.UTF_8),
              "this is not a valid envelope".getBytes(StandardCharsets.UTF_8))
          .get(10, TimeUnit.SECONDS);
    }

    Vyra client = node("client-5");
    client
        .publish("game-events", new JoinedEvent("p2"))
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

    JoinedEvent event = received.poll(10, TimeUnit.SECONDS);
    assertEquals("p2", event.playerId());

    backend.close();
    client.close();
  }
}
