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
package com.marioded.vyra.redis;

import com.marioded.vyra.core.Vyra;
import com.marioded.vyra.core.VyraBuilder;
import io.lettuce.core.RedisClient;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point for building a Vyra instance backed by Redis.
 *
 * <pre>{@code
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * Vyra vyra = RedisVyra.redis(client).serializer(JacksonSerializer.jackson()).build();
 * }</pre>
 *
 * <p>The payload serializer is required; supply one from a serializer module (e.g. {@code
 * JacksonSerializer} from {@code vyra-jackson}).
 *
 * <p>The {@link RedisClient} is owned by the caller: closing the Vyra instance closes the
 * transport's connections but never the client.
 */
public final class RedisVyra {

  private RedisVyra() {}

  /** Returns a {@link VyraBuilder} pre-configured with a Redis transport. */
  public static @NotNull VyraBuilder redis(@NotNull RedisClient client) {
    return Vyra.builder().transport(new RedisTransport(client));
  }
}
