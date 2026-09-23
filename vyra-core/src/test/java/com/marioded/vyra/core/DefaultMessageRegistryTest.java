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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.marioded.vyra.core.exception.VyraSerializationException;
import org.junit.jupiter.api.Test;

class DefaultMessageRegistryTest {

  @Test
  void roundTrip() {
    DefaultMessageRegistry registry = new DefaultMessageRegistry();
    registry.register("player.get", PlayerGetRequest.class);
    assertEquals("player.get", registry.getMessageType(PlayerGetRequest.class));
    assertEquals(PlayerGetRequest.class, registry.getClass("player.get"));
  }

  @Test
  void unknownClassThrows() {
    DefaultMessageRegistry registry = new DefaultMessageRegistry();
    assertThrows(VyraSerializationException.class, () -> registry.getMessageType(String.class));
  }

  @Test
  void unknownMessageTypeThrows() {
    DefaultMessageRegistry registry = new DefaultMessageRegistry();
    assertThrows(VyraSerializationException.class, () -> registry.getClass("nope"));
  }

  @Test
  void registerReservedMessageTypeThrows() {
    DefaultMessageRegistry registry = new DefaultMessageRegistry();
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register("vyra.error", PlayerGetRequest.class));
  }

  record PlayerGetRequest(String playerId) {}
}
