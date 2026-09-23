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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Point-to-point request/response: a client sends a request to a worker and gets a typed response.
 */
public final class RequestResponseExample {

  private static final Logger LOGGER = Logger.getLogger(RequestResponseExample.class.getName());

  private static final String TARGET = "score-service";

  /** Wire contract (wire id {@code "score.apply"}). */
  record ScoreRequest(String playerId, int points) {}

  /** Wire contract (wire id {@code "score.result"}). */
  record ScoreResponse(String playerId, int totalPoints, String responderNodeId) {}

  public static void main(String[] args) {
    InMemoryBus sharedBus = new InMemoryBus();
    JacksonSerializer serializer = JacksonSerializer.jackson();

    Vyra client = newNode(sharedBus, serializer, "client");
    Vyra worker = newNode(sharedBus, serializer, "worker-1");

    Map<String, Integer> scores = new ConcurrentHashMap<>();
    worker.handle(
        TARGET,
        ScoreRequest.class,
        req ->
            CompletableFuture.completedFuture(
                new ScoreResponse(
                    req.playerId(),
                    scores.merge(req.playerId(), req.points(), Integer::sum),
                    "worker-1")));

    for (int i = 1; i <= 3; i++) {
      ScoreResponse response =
          client
              .request(
                  TARGET,
                  new ScoreRequest("mario", i * 100),
                  ScoreResponse.class,
                  Duration.ofSeconds(2))
              .toCompletableFuture()
              .join();
      LOGGER.info(
          ">>> [Client] Request %d -> Total: %d pts (Processed by: %s)"
              .formatted(i, response.totalPoints(), response.responderNodeId()));
    }

    LOGGER.info("Shutting down nodes...");
    client.close();
    worker.close();
  }

  private static Vyra newNode(InMemoryBus bus, JacksonSerializer serializer, String nodeId) {
    Vyra node = InMemoryVyra.inMemory(bus).serializer(serializer).nodeId(nodeId).build();
    node.register("score.apply", ScoreRequest.class);
    node.register("score.result", ScoreResponse.class);
    return node;
  }
}
