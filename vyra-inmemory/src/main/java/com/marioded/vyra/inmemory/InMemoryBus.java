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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

/**
 * Shared delivery bus for {@link InMemoryTransport} instances. One bus represents one "network":
 * transports attached to the same bus deliver envelopes to each other. Delivery is synchronous and
 * broadcast: every subscriber of a key receives every envelope published to it.
 */
public final class InMemoryBus {

  private final @NotNull Map<String, List<Consumer<byte[]>>> subscribers =
      new ConcurrentHashMap<>();
  private final @NotNull AtomicBoolean closed = new AtomicBoolean(false);

  public void subscribe(@NotNull String key, @NotNull Consumer<byte[]> handler) {
    subscribers.computeIfAbsent(key, d -> new CopyOnWriteArrayList<>()).add(handler);
  }

  public void unsubscribe(@NotNull String key, @NotNull Consumer<byte[]> handler) {
    List<Consumer<byte[]>> handlers = subscribers.get(key);
    if (handlers != null) {
      handlers.remove(handler);
    }
  }

  public void publish(@NotNull String key, byte @NotNull [] envelope) {
    if (closed.get()) {
      throw new IllegalStateException("InMemoryBus is closed");
    }
    List<Consumer<byte[]>> handlers = subscribers.get(key);
    if (handlers == null || handlers.isEmpty()) {
      return;
    }
    for (Consumer<byte[]> handler : handlers) {
      handler.accept(envelope);
    }
  }
}
