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

import com.marioded.vyra.core.Vyra;
import com.marioded.vyra.core.VyraBuilder;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point for building a Vyra instance backed by the in-memory transport, for local development
 * and tests.
 *
 * <pre>{@code
 * Vyra vyra = InMemoryVyra.inMemory().serializer(JacksonSerializer.jackson()).build();
 * }</pre>
 *
 * <p>The payload serializer is required; supply one from a serializer module (e.g. {@code
 * JacksonSerializer} from {@code vyra-jackson}).
 *
 * <p>Pass a shared {@link InMemoryBus} to connect multiple instances to the same "network".
 */
public final class InMemoryVyra {

  private InMemoryVyra() {}

  /** Returns a {@link VyraBuilder} on a fresh in-memory bus. */
  public static @NotNull VyraBuilder inMemory() {
    return Vyra.builder().transport(new InMemoryTransport(new InMemoryBus()));
  }

  /** Returns a {@link VyraBuilder} attached to the given shared bus. */
  public static @NotNull VyraBuilder inMemory(@NotNull InMemoryBus bus) {
    return Vyra.builder().transport(new InMemoryTransport(bus));
  }
}
