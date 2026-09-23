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

import java.util.concurrent.ExecutorService;
import org.jetbrains.annotations.NotNull;

/**
 * Configures and builds a {@link Vyra} instance. Obtain one from {@link Vyra#builder()} or from a
 * transport-module factory such as {@code RedisVyra.redis(client)}.
 *
 * <p>The transport and the payload serializer are required; every other option has a default:
 *
 * <ul>
 *   <li>{@code nodeId}: auto-generated unique instance identity if not set.
 *   <li>{@code serializer}: required; supply a serializer from a serializer module (e.g. {@code
 *       JacksonSerializer} from {@code vyra-jackson}).
 *   <li>{@code registry}: an internal default registry filled via {@link Vyra#register}; supply a
 *       custom {@link MessageRegistry} only for non-default mapping strategies.
 *   <li>{@code handlerExecutor}: an owned fixed-size daemon executor if not set; a supplied
 *       executor is owned by the caller and never shut down by {@link Vyra#close()}.
 * </ul>
 */
public interface VyraBuilder {

  /** Sets the node ID (instance identity). Must be unique among live instances. */
  @NotNull
  VyraBuilder nodeId(@NotNull String nodeId);

  /** Sets the transport. Required. */
  @NotNull
  VyraBuilder transport(@NotNull Transport transport);

  /**
   * Sets the payload serializer. Required; no default. Supply a serializer from a serializer
   * module, e.g. {@code JacksonSerializer} from {@code vyra-jackson}.
   */
  @NotNull
  VyraBuilder serializer(@NotNull Serializer serializer);

  /** Sets a custom message registry. Defaults to an internal registry. */
  @NotNull
  VyraBuilder registry(@NotNull MessageRegistry registry);

  /** Sets the executor for user handlers. Defaults to an owned daemon pool. */
  @NotNull
  VyraBuilder handlerExecutor(@NotNull ExecutorService handlerExecutor);

  /** Builds the {@link Vyra} instance. */
  @NotNull
  Vyra build();
}
