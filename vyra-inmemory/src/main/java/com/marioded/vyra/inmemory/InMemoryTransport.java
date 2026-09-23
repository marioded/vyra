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

import com.marioded.vyra.core.MessageKind;
import com.marioded.vyra.core.Transport;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Dependency-free in-memory {@link Transport} for local development and tests. Instances attached
 * to the same {@link InMemoryBus} deliver envelopes to each other. Delivery is best-effort and
 * synchronous.
 */
public final class InMemoryTransport implements Transport {

  private final @NotNull InMemoryBus bus;
  private final @NotNull Set<String> subscriptions = ConcurrentHashMap.newKeySet();
  private final @NotNull java.util.function.Consumer<byte[]> deliver = this::deliver;

  private @Nullable Function<byte[], CompletionStage<Void>> messageHandler;

  public InMemoryTransport(@NotNull InMemoryBus bus) {
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  @Override
  public @NotNull CompletionStage<Void> send(
      @NotNull MessageKind kind, @NotNull String destination, byte @NotNull [] envelope) {
    try {
      bus.publish(kind.prefix() + destination, envelope);
      return CompletableFuture.completedFuture(null);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void subscribe(@NotNull MessageKind kind, @NotNull String destination) {
    String key = kind.prefix() + destination;
    if (subscriptions.add(key)) {
      bus.subscribe(key, deliver);
    }
  }

  @Override
  public void setMessageHandler(@NotNull Function<byte[], CompletionStage<Void>> handler) {
    this.messageHandler = Objects.requireNonNull(handler, "handler");
  }

  @Override
  public void close() {
    for (String key : subscriptions) {
      bus.unsubscribe(key, deliver);
    }
    subscriptions.clear();
  }

  private void deliver(byte[] bytes) {
    if (messageHandler != null) {
      messageHandler.apply(bytes);
    }
  }
}
