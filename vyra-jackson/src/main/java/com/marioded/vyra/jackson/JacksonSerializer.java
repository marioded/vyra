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
package com.marioded.vyra.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.marioded.vyra.core.Serializer;
import com.marioded.vyra.core.exception.VyraSerializationException;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JSON {@link Serializer} backed by Jackson. Thread-safe.
 *
 * <p>The {@link #smile()} and {@link #cbor()} factories produce compact binary formats (Smile and
 * CBOR, RFC 8949) with the same behavior.
 */
public final class JacksonSerializer implements Serializer {

  private final @NotNull ObjectMapper objectMapper;

  JacksonSerializer() {
    this(new ObjectMapper());
  }

  JacksonSerializer(@NotNull ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public static @NotNull JacksonSerializer jackson() {
    return new JacksonSerializer();
  }

  public static @NotNull JacksonSerializer jackson(@NotNull ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    return new JacksonSerializer(objectMapper);
  }

  /** Binary (Smile) serializer: compact binary JSON from the Jackson project. */
  public static @NotNull JacksonSerializer smile() {
    return new JacksonSerializer(new ObjectMapper(new SmileFactory()));
  }

  public static @NotNull JacksonSerializer smile(@NotNull ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    if (!(objectMapper.getFactory() instanceof SmileFactory)) {
      throw new IllegalArgumentException(
          "smile(ObjectMapper) requires a mapper backed by a SmileFactory; "
              + "use smile() or new ObjectMapper(new SmileFactory())");
    }
    return new JacksonSerializer(objectMapper);
  }

  /** Binary (CBOR) serializer: IETF-standard binary format (RFC 8949). */
  public static @NotNull JacksonSerializer cbor() {
    return new JacksonSerializer(new ObjectMapper(new CBORFactory()));
  }

  public static @NotNull JacksonSerializer cbor(@NotNull ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    if (!(objectMapper.getFactory() instanceof CBORFactory)) {
      throw new IllegalArgumentException(
          "cbor(ObjectMapper) requires a mapper backed by a CBORFactory; "
              + "use cbor() or new ObjectMapper(new CBORFactory())");
    }
    return new JacksonSerializer(objectMapper);
  }

  @Override
  public byte @NotNull [] serialize(@NotNull Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (Exception e) {
      throw new VyraSerializationException("Failed to serialize " + value.getClass().getName(), e);
    }
  }

  @Override
  public <T> @NotNull T deserialize(byte @NotNull [] bytes, @NotNull Class<T> type) {
    try {
      return objectMapper.readValue(bytes, type);
    } catch (Exception e) {
      throw new VyraSerializationException("Failed to deserialize " + type.getName(), e);
    }
  }

  @Override
  public <T> @Nullable T convert(@NotNull Object value, @NotNull Class<T> type) {
    return objectMapper.convertValue(value, type);
  }
}
