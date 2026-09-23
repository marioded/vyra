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

/**
 * Classifies a {@link Message} as a request, response or event. The kind is authoritative on the
 * wire: the core routes on it and the transport uses it to choose the delivery mechanism. The
 * {@code destination} of a message is always the bare logical destination (service target, node ID
 * or channel)
 */
public enum MessageKind {

  /** A request addressed to a logical service target (worker-queue semantics). */
  REQUEST("req:"),

  /** A broadcast request addressed to all worker nodes handling a target. */
  BROADCAST_REQUEST("bcast:"),

  /** A point-to-point response addressed to the originating node ID. */
  RESPONSE("res:"),

  /** A broadcast event addressed to a channel. */
  EVENT("evt:");

  private final @NotNull String prefix;

  MessageKind(@NotNull String prefix) {
    this.prefix = prefix;
  }

  /**
   * Transport-level routing prefix for this kind (e.g. {@code req:}). Transports use it to
   * namespace their underlying keys/channels so that different kinds never collide; it is not part
   * of the wire destination.
   */
  public @NotNull String prefix() {
    return prefix;
  }
}
