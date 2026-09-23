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
package com.marioded.vyra.inmemory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marioded.vyra.core.MessageKind;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class InMemoryTransportTest {

  private static final byte[] ENVELOPE = new byte[] {0x01, 0x02, 0x03};

  @Test
  void deliversToSubscribedDestination() {
    InMemoryBus bus = new InMemoryBus();
    InMemoryTransport transport = new InMemoryTransport(bus);
    List<byte[]> received = new CopyOnWriteArrayList<>();
    transport.setMessageHandler(
        bytes -> {
          received.add(bytes);
          return CompletableFuture.completedFuture(null);
        });
    transport.subscribe(MessageKind.REQUEST, "backend");

    InMemoryTransport sender = new InMemoryTransport(bus);
    sender.send(MessageKind.REQUEST, "backend", ENVELOPE).toCompletableFuture().join();
    assertEquals(1, received.size());
    assertArrayEquals(ENVELOPE, received.get(0));
  }

  @Test
  void doesNotDeliverToOtherDestinations() {
    InMemoryBus bus = new InMemoryBus();
    InMemoryTransport transport = new InMemoryTransport(bus);
    List<byte[]> received = new CopyOnWriteArrayList<>();
    transport.setMessageHandler(
        bytes -> {
          received.add(bytes);
          return CompletableFuture.completedFuture(null);
        });
    transport.subscribe(MessageKind.REQUEST, "backend");

    InMemoryTransport sender = new InMemoryTransport(bus);
    sender.send(MessageKind.REQUEST, "other", ENVELOPE).toCompletableFuture().join();
    assertTrue(received.isEmpty());
  }

  @Test
  void closeUnsubscribes() {
    InMemoryBus bus = new InMemoryBus();
    InMemoryTransport transport = new InMemoryTransport(bus);
    List<byte[]> received = new CopyOnWriteArrayList<>();
    transport.setMessageHandler(
        bytes -> {
          received.add(bytes);
          return CompletableFuture.completedFuture(null);
        });
    transport.subscribe(MessageKind.REQUEST, "backend");
    transport.close();

    InMemoryTransport sender = new InMemoryTransport(bus);
    sender.send(MessageKind.REQUEST, "backend", ENVELOPE).toCompletableFuture().join();
    assertTrue(received.isEmpty());
  }

  @Test
  void deliversBroadcastRequestToMultipleSubscribers() {
    InMemoryBus bus = new InMemoryBus();
    InMemoryTransport sub1 = new InMemoryTransport(bus);
    List<byte[]> received1 = new CopyOnWriteArrayList<>();
    sub1.setMessageHandler(
        bytes -> {
          received1.add(bytes);
          return CompletableFuture.completedFuture(null);
        });
    sub1.subscribe(MessageKind.BROADCAST_REQUEST, "cluster");

    InMemoryTransport sub2 = new InMemoryTransport(bus);
    List<byte[]> received2 = new CopyOnWriteArrayList<>();
    sub2.setMessageHandler(
        bytes -> {
          received2.add(bytes);
          return CompletableFuture.completedFuture(null);
        });
    sub2.subscribe(MessageKind.BROADCAST_REQUEST, "cluster");

    InMemoryTransport sender = new InMemoryTransport(bus);
    sender.send(MessageKind.BROADCAST_REQUEST, "cluster", ENVELOPE).toCompletableFuture().join();

    assertEquals(1, received1.size());
    assertEquals(1, received2.size());
    assertArrayEquals(ENVELOPE, received1.get(0));
    assertArrayEquals(ENVELOPE, received2.get(0));
  }
}
