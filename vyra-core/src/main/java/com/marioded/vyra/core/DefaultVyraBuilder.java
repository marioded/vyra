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

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.NotNull;

/** Default {@link VyraBuilder} implementation. */
final class DefaultVyraBuilder implements VyraBuilder {

  private String nodeId;
  private Transport transport;
  private Serializer serializer;
  private MessageRegistry registry;
  private ExecutorService handlerExecutor;

  @Override
  public @NotNull VyraBuilder nodeId(@NotNull String nodeId) {
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    return this;
  }

  @Override
  public @NotNull VyraBuilder transport(@NotNull Transport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
    return this;
  }

  @Override
  public @NotNull VyraBuilder serializer(@NotNull Serializer serializer) {
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    return this;
  }

  @Override
  public @NotNull VyraBuilder registry(@NotNull MessageRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    return this;
  }

  @Override
  public @NotNull VyraBuilder handlerExecutor(@NotNull ExecutorService handlerExecutor) {
    this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
    return this;
  }

  @Override
  public @NotNull Vyra build() {
    if (transport == null) {
      throw new IllegalStateException(
          "transport is required: set it via builder.transport(...) "
              + "or use a transport-module factory such as RedisVyra.redis(client)");
    }

    if (serializer == null) {
      throw new IllegalStateException(
          "serializer is required: set it via builder.serializer(...), "
              + "e.g. builder.serializer(JacksonSerializer.jackson()) from the vyra-jackson module");
    }
    String id = nodeId != null ? nodeId : "vyra-" + UUID.randomUUID();

    Serializer effectiveSerializer = serializer;
    MessageRegistry effectiveRegistry = registry != null ? registry : new DefaultMessageRegistry();
    ExecutorService executor = handlerExecutor;
    boolean ownsExecutor = false;

    if (executor == null) {
      int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
      executor =
          Executors.newFixedThreadPool(
              threads,
              r -> {
                Thread t = new Thread(r, "vyra-handler-" + id);
                t.setDaemon(true);
                return t;
              });
      ownsExecutor = true;
    }

    return new DefaultVyra(
        id, transport, effectiveRegistry, effectiveSerializer, executor, ownsExecutor);
  }
}
