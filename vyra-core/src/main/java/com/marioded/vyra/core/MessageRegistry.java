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

import com.marioded.vyra.core.exception.VyraSerializationException;
import org.jetbrains.annotations.NotNull;

/**
 * Maps stable protocol-level message type identifiers to Java classes and back. Identifiers are
 * application-defined strings and are what travels on the wire
 */
public interface MessageRegistry {

  void register(@NotNull String messageType, @NotNull Class<?> type);

  /**
   * @throws VyraSerializationException if {@code type} is not registered
   */
  @NotNull
  String getMessageType(@NotNull Class<?> type);

  /**
   * @throws VyraSerializationException if {@code messageType} is not registered
   */
  @NotNull
  Class<?> getClass(@NotNull String messageType);
}
