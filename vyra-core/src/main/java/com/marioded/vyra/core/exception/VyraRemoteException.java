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
package com.marioded.vyra.core.exception;

import com.marioded.vyra.core.RemoteError;
import org.jetbrains.annotations.NotNull;

/**
 * The remote handler threw an exception. The framework catches it, sends an error message to the
 * caller and throws it locally.
 */
public class VyraRemoteException extends VyraException {

  private final @NotNull RemoteError error;

  public VyraRemoteException(@NotNull RemoteError error) {
    super(error.message());
    this.error = error;
  }

  /**
   * Returns the stable error code sent by the remote handler. This is a more reliable way to
   * identify the error than parsing the message string.
   */
  public @NotNull RemoteError.Code getCode() {
    return error.code();
  }
}
