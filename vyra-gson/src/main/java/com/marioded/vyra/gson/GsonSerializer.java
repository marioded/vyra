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
package com.marioded.vyra.gson;

import com.google.gson.Gson;
import com.marioded.vyra.core.Serializer;
import com.marioded.vyra.core.exception.VyraSerializationException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** JSON {@link Serializer} backed by Gson. */
public final class GsonSerializer implements Serializer {

  private final @NotNull Gson gson;

  GsonSerializer() {
    this(new Gson());
  }

  GsonSerializer(@NotNull Gson gson) {
    this.gson = Objects.requireNonNull(gson, "gson");
  }

  @Override
  public byte @NotNull [] serialize(@NotNull Object value) {
    try {
      return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new VyraSerializationException("Failed to serialize " + value.getClass().getName(), e);
    }
  }

  @Override
  public <T> @NotNull T deserialize(byte @NotNull [] bytes, @NotNull Class<T> type) {
    try {
      return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
    } catch (Exception e) {
      throw new VyraSerializationException("Failed to deserialize " + type.getName(), e);
    }
  }

  @Override
  public <T> @Nullable T convert(@NotNull Object value, @NotNull Class<T> type) {
    return gson.fromJson(gson.toJsonTree(value), type);
  }

  public static @NotNull GsonSerializer gson() {
    return new GsonSerializer();
  }

  public static @NotNull GsonSerializer gson(@NotNull Gson gson) {
    return new GsonSerializer(gson);
  }
}
