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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/** Default implementation of {@link MessageRegistry} backed by concurrent maps. */
final class DefaultMessageRegistry implements MessageRegistry {

  private final Map<String, Class<?>> byType = new ConcurrentHashMap<>();
  private final Map<Class<?>, String> byClass = new ConcurrentHashMap<>();

  @Override
  public void register(@NotNull String messageType, @NotNull Class<?> type) {
    Objects.requireNonNull(messageType, "messageType");
    Objects.requireNonNull(type, "type");
    if (Protocol.ERROR_MESSAGE_TYPE.equals(messageType)) {
      throw new IllegalArgumentException(
          "messageType '" + messageType + "' is reserved by the framework");
    }
    byType.put(messageType, type);
    byClass.put(type, messageType);
  }

  @Override
  public @NotNull String getMessageType(@NotNull Class<?> type) {
    String messageType = byClass.get(type);
    if (messageType == null) {
      throw new VyraSerializationException("No messageType registered for class " + type.getName());
    }
    return messageType;
  }

  @Override
  public @NotNull Class<?> getClass(@NotNull String messageType) {
    Class<?> type = byType.get(messageType);
    if (type == null) {
      throw new VyraSerializationException(
          "No class registered for messageType '" + messageType + "'");
    }
    return type;
  }
}
