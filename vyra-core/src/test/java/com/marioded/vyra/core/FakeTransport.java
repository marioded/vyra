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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Test transport acting as a shared bus: envelopes sent to a destination are delivered
 * synchronously to every subscriber of that destination.
 */
final class FakeTransport implements Transport {

  final Map<String, List<Function<byte[], CompletionStage<Void>>>> subscribers =
      new ConcurrentHashMap<>();
  final List<SentEnvelope> sent = new CopyOnWriteArrayList<>();
  volatile Function<byte[], CompletionStage<Void>> handler;

  /** Records what the transport observed on the wire, for test assertions. */
  record SentEnvelope(MessageKind kind, String destination, byte[] envelope) {}

  @Override
  public @NotNull CompletionStage<Void> send(
      @NotNull MessageKind kind, @NotNull String destination, byte @NotNull [] envelope) {
    sent.add(new SentEnvelope(kind, destination, envelope));
    List<Function<byte[], CompletionStage<Void>>> subs =
        subscribers.get(kind.prefix() + destination);
    if (subs != null) {
      for (Function<byte[], CompletionStage<Void>> sub : subs) {
        sub.apply(envelope);
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void subscribe(@NotNull MessageKind kind, @NotNull String destination) {
    if (handler != null) {
      subscribers
          .computeIfAbsent(kind.prefix() + destination, d -> new CopyOnWriteArrayList<>())
          .add(handler);
    }
  }

  @Override
  public void setMessageHandler(@NotNull Function<byte[], CompletionStage<Void>> handler) {
    this.handler = handler;
  }

  @Override
  public void close() {
    subscribers.clear();
  }
}
