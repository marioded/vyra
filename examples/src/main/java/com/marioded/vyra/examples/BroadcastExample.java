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
package com.marioded.vyra.examples;

import com.marioded.vyra.core.Vyra;
import com.marioded.vyra.inmemory.InMemoryBus;
import com.marioded.vyra.inmemory.InMemoryVyra;
import com.marioded.vyra.jackson.JacksonSerializer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * Broadcast request/response: a client asks every worker at once; only the node owning the entity
 * replies, the others stay silent.
 */
public final class BroadcastExample {

  private static final Logger LOGGER = Logger.getLogger(BroadcastExample.class.getName());

  private static final String TARGET = "game-cluster";

  /** Wire contract (wire id {@code "player.get"}). */
  record GetPlayerRequest(String playerId) {}

  /** Wire contract (wire id {@code "player.info"}). */
  record PlayerResponse(String playerId, String name, int score, String responderNodeId) {}

  public static void main(String[] args) {
    InMemoryBus sharedBus = new InMemoryBus();
    JacksonSerializer serializer = JacksonSerializer.jackson();

    Vyra client = newNode(sharedBus, serializer, "client");

    Vyra node1 = newNode(sharedBus, serializer, "node-1");
    registerWorker(
        node1, Map.of("player-1", new PlayerResponse("player-1", "Mario", 1000, "node-1")));

    Vyra node2 = newNode(sharedBus, serializer, "node-2");
    registerWorker(
        node2, Map.of("player-2", new PlayerResponse("player-2", "Luigi", 2000, "node-2")));

    Vyra node3 = newNode(sharedBus, serializer, "node-3");
    registerWorker(
        node3, Map.of("player-3", new PlayerResponse("player-3", "Peach", 3000, "node-3")));

    // Only the node owning the player replies
    PlayerResponse found =
        client
            .broadcastRequest(
                TARGET,
                new GetPlayerRequest("player-2"),
                PlayerResponse.class,
                Duration.ofSeconds(2))
            .toCompletableFuture()
            .join();
    LOGGER.info(
        ">>> [Client] Found %s on %s (Score: %d)"
            .formatted(found.name(), found.responderNodeId(), found.score()));

    // No node owns the player: all stay silent and the request times out
    try {
      client
          .broadcastRequest(
              TARGET,
              new GetPlayerRequest("player-999"),
              PlayerResponse.class,
              Duration.ofMillis(500))
          .toCompletableFuture()
          .join();
    } catch (CompletionException e) {
      LOGGER.info(">>> [Client] Expected timeout: no node owns 'player-999'");
    }

    LOGGER.info("Shutting down nodes...");
    client.close();
    node1.close();
    node2.close();
    node3.close();
  }

  private static Vyra newNode(InMemoryBus bus, JacksonSerializer serializer, String nodeId) {
    Vyra node = InMemoryVyra.inMemory(bus).serializer(serializer).nodeId(nodeId).build();
    node.register("player.get", GetPlayerRequest.class);
    node.register("player.info", PlayerResponse.class);
    return node;
  }

  /** Answers only for owned players otherwise just remains silent */
  private static void registerWorker(Vyra worker, Map<String, PlayerResponse> store) {
    worker.handle(
        TARGET,
        GetPlayerRequest.class,
        req -> CompletableFuture.completedFuture(store.get(req.playerId())));
  }
}
