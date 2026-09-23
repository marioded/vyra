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
import java.util.logging.Logger;

/**
 * Publish/subscribe events: a publisher broadcasts events to a channel; every active subscriber
 * receives a copy (at-most-once).
 */
public final class EventsExample {

  private static final Logger LOGGER = Logger.getLogger(EventsExample.class.getName());

  private static final String CHANNEL = "game-events";

  /** Wire contract (wire id {@code "player.scored"}). */
  record PlayerScoredEvent(String playerId, int points, String serverId) {}

  public static void main(String[] args) throws InterruptedException {
    InMemoryBus sharedBus = new InMemoryBus();
    JacksonSerializer serializer = JacksonSerializer.jackson();

    Vyra publisher = newNode(sharedBus, serializer, "publisher");
    Vyra auditNode = newNode(sharedBus, serializer, "audit-node");
    Vyra leaderboardNode = newNode(sharedBus, serializer, "leaderboard-node");

    auditNode.subscribe(
        CHANNEL,
        PlayerScoredEvent.class,
        evt ->
            LOGGER.info(
                "[audit-node] Observed score: player=%s scored %d pts on %s"
                    .formatted(evt.playerId(), evt.points(), evt.serverId())));

    leaderboardNode.subscribe(
        CHANNEL,
        PlayerScoredEvent.class,
        evt ->
            LOGGER.info(
                "[leaderboard-node] Updating leaderboard: player=%s +%d pts"
                    .formatted(evt.playerId(), evt.points())));

    publishAndWait(publisher, new PlayerScoredEvent("mario", 100, "server-1"));
    publishAndWait(publisher, new PlayerScoredEvent("luigi", 250, "server-2"));
    publishAndWait(publisher, new PlayerScoredEvent("mario", 50, "server-1"));

    LOGGER.info("Shutting down nodes...");
    publisher.close();
    auditNode.close();
    leaderboardNode.close();
  }

  private static Vyra newNode(InMemoryBus bus, JacksonSerializer serializer, String nodeId) {
    Vyra node = InMemoryVyra.inMemory(bus).serializer(serializer).nodeId(nodeId).build();
    node.register("player.scored", PlayerScoredEvent.class);
    return node;
  }

  private static void publishAndWait(Vyra vyra, PlayerScoredEvent event)
      throws InterruptedException {
    vyra.publish(CHANNEL, event).toCompletableFuture().join();
    Thread.sleep(50); // let async subscriber callbacks flush
  }
}
