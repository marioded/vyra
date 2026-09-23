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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe storage of request handlers (keyed by target + message type) and event subscribers
 * (keyed by channel)
 */
final class HandlerRegistry {

  private final Map<HandlerKey, HandlerEntry> requestHandlers = new ConcurrentHashMap<>();
  private final Map<String, List<EventSubscriber>> eventSubscribers = new ConcurrentHashMap<>();

  void registerRequest(
      @NotNull String target,
      @NotNull String messageType,
      @NotNull Class<?> requestType,
      @NotNull Function<Object, CompletionStage<Object>> handler) {
    HandlerKey key = new HandlerKey(target, messageType);
    HandlerEntry previous =
        requestHandlers.putIfAbsent(key, new HandlerEntry(requestType, handler));

    if (previous != null) {
      throw new IllegalStateException(
          "A handler is already registered for messageType '"
              + messageType
              + "' on target '"
              + target
              + "'");
    }
  }

  @Nullable
  HandlerEntry lookupRequest(@NotNull String target, @NotNull String messageType) {
    return requestHandlers.get(new HandlerKey(target, messageType));
  }

  void registerEvent(
      @NotNull String channel, @NotNull Class<?> eventType, @NotNull Consumer<Object> consumer) {
    eventSubscribers
        .computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>())
        .add(new EventSubscriber(eventType, consumer));
  }

  @Nullable
  List<EventSubscriber> eventSubscribers(@NotNull String channel) {
    return eventSubscribers.get(channel);
  }

  record HandlerKey(@NotNull String target, @NotNull String messageType) {}

  record HandlerEntry(
      @NotNull Class<?> requestType, @NotNull Function<Object, CompletionStage<Object>> handler) {}

  record EventSubscriber(@NotNull Class<?> eventType, @NotNull Consumer<Object> consumer) {}
}
