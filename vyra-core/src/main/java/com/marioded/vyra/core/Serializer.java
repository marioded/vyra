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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Converts application payloads to and from {@code byte[]} for the wire. Implementations must be
 * thread-safe.
 */
public interface Serializer {

  /**
   * Serializes a value to its wire bytes.
   *
   * @param value the value to serialize; never {@code null}
   * @return the serialized bytes; never {@code null}
   */
  byte @NotNull [] serialize(@NotNull Object value);

  /**
   * Deserializes wire bytes back to a value.
   *
   * @param bytes the wire bytes; never {@code null} or empty
   * @param type the target type
   * @return the deserialized value
   */
  <T> @NotNull T deserialize(byte @NotNull [] bytes, @NotNull Class<T> type);

  /**
   * Converts a decoded generic value to the target type (from the original message).
   *
   * <p>The default implementation is serialize + deserialize round-trip; type-preserving
   * serializers benefit from the {@code isInstance} short-circuit.
   *
   * @param value the decoded generic value;
   * @param type the target type
   * @return the converted value, or {@code null} for a {@code null} input
   */
  default <T> @Nullable T convert(@NotNull Object value, @NotNull Class<T> type) {
    if (type.isInstance(value)) {
      return type.cast(value);
    }

    return deserialize(serialize(value), type);
  }
}
