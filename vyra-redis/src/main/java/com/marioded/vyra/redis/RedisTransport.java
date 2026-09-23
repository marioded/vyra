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

import com.marioded.vyra.core.MessageKind;
import com.marioded.vyra.core.Transport;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Redis {@link Transport} backed by Lettuce.
 *
 * <p>Mechanism is chosen by the {@link MessageKind}:
 *
 * <ul>
 *   <li>{@link MessageKind#REQUEST} using Redis Lists ({@code LPUSH} + {@code BRPOP}) for
 *       best-effort worker-queue load balancing.
 *   <li>{@link MessageKind#RESPONSE} / {@link MessageKind#EVENT} using Redis Pub/Sub for
 *       point-to-point responses and at-most-once event broadcast.
 * </ul>
 *
 * <p>Redis keys and channels are namespaced with the kind prefix ({@code req:}/{@code res:}/{@code
 * evt:}) so different kinds never collide; the prefix is a transport-internal detail and is not
 * part of the wire destination.
 *
 * <p>The transport moves raw envelope bytes; the envelope is serialized by the core with the
 * user-configured serializer.
 *
 * <p>One dedicated connection is used solely for the blocking {@code BRPOP} loops (one thread per
 * subscribed {@code REQUEST} destination), keeping blocking commands off the async command
 * connection.
 *
 * <p>{@link #close()} closes the connections owned by this transport. The {@link RedisClient} is
 * owned by the caller and is never shut down here.
 */
public final class RedisTransport implements Transport {

  private static final Logger LOG = Logger.getLogger(RedisTransport.class.getName());
  private static final long BRPOP_POLL_TIMEOUT_SECONDS = 1;

  private final @NotNull RedisClient client;
  private final @NotNull StatefulRedisConnection<byte[], byte[]> connection;
  private final @NotNull StatefulRedisPubSubConnection<byte[], byte[]> pubSubConnection;
  private final @NotNull RedisAsyncCommands<byte[], byte[]> asyncCommands;
  private final @NotNull RedisPubSubAsyncCommands<byte[], byte[]> pubSubCommands;
  private final @NotNull ExecutorService blockingExecutor;
  private final @NotNull Set<String> reqDestinations = ConcurrentHashMap.newKeySet();
  private final @NotNull AtomicBoolean closed = new AtomicBoolean(false);
  private @Nullable Function<byte[], CompletionStage<Void>> messageHandler;

  public RedisTransport(@NotNull RedisClient client) {
    this.client = Objects.requireNonNull(client, "client");
    this.connection = client.connect(ByteArrayCodec.INSTANCE);
    this.pubSubConnection = client.connectPubSub(ByteArrayCodec.INSTANCE);
    this.asyncCommands = connection.async();
    this.pubSubCommands = pubSubConnection.async();
    this.blockingExecutor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "vyra-redis-brpop");
              t.setDaemon(true);
              return t;
            });
    pubSubConnection.addListener(
        new RedisPubSubAdapter<>() {
          @Override
          public void message(byte @NotNull [] channel, byte @NotNull [] message) {
            try {
              dispatch(message);
            } catch (Exception e) {
              LOG.log(
                  Level.WARNING,
                  "Dropping unparseable message on channel %s: %s"
                      .formatted(new String(channel, StandardCharsets.UTF_8), snippet(message)),
                  e);
            }
          }
        });
  }

  @Override
  public @NotNull CompletionStage<Void> send(
      @NotNull MessageKind kind, @NotNull String destination, byte @NotNull [] envelope) {
    ensureOpen();
    String channel = kind.prefix() + destination;

    return switch (kind) {
      case REQUEST -> asyncCommands.lpush(keyBytes(channel), envelope).thenApply(v -> null);
      case RESPONSE, EVENT, BROADCAST_REQUEST ->
          pubSubCommands.publish(channelBytes(channel), envelope).thenApply(v -> null);
    };
  }

  @Override
  public void subscribe(@NotNull MessageKind kind, @NotNull String destination) {
    ensureOpen();
    String key = kind.prefix() + destination;

    switch (kind) {
      case REQUEST -> {
        if (reqDestinations.add(key)) {
          startBlockingLoop(key);
        }
      }
      case RESPONSE, EVENT, BROADCAST_REQUEST -> pubSubCommands.subscribe(channelBytes(key));
      default -> throw new IllegalArgumentException("Unsupported message kind: " + kind);
    }
  }

  @Override
  public void setMessageHandler(@NotNull Function<byte[], CompletionStage<Void>> handler) {
    this.messageHandler = Objects.requireNonNull(handler, "handler");
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      blockingExecutor.shutdownNow();
      pubSubConnection.close();
      connection.close();
    }
  }

  private void startBlockingLoop(@NotNull String key) {
    byte[] keyBytes = keyBytes(key);

    blockingExecutor.execute(
        () -> {
          while (!closed.get()) {
            try (StatefulRedisConnection<byte[], byte[]> loopConnection =
                client.connect(ByteArrayCodec.INSTANCE)) {
              RedisCommands<byte[], byte[]> sync = loopConnection.sync();
              while (!closed.get()) {
                try {
                  KeyValue<byte[], byte[]> kv = sync.brpop(BRPOP_POLL_TIMEOUT_SECONDS, keyBytes);
                  if (kv != null && kv.hasValue()) {
                    try {
                      dispatch(kv.getValue());
                    } catch (Exception e) {
                      LOG.log(
                          Level.WARNING,
                          "Dropping unparseable message from queue %s".formatted(key),
                          e);
                    }
                  }
                } catch (Exception e) {
                  if (!closed.get()) {
                    break;
                  }
                }
              }
            } catch (Exception e) {
              if (!closed.get()) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            }
          }
        });
  }

  private void dispatch(byte @NotNull [] bytes) {
    if (messageHandler != null) {
      messageHandler.apply(bytes);
    }
  }

  private static @NotNull String snippet(byte @Nullable [] bytes) {
    if (bytes == null) {
      return "<null>";
    }
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.length() > 128) {
      text = text.substring(0, 128) + "...";
    }
    return text.replaceAll("\\p{Cntrl}", "?");
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("RedisTransport is closed");
    }
  }

  private static byte @NotNull [] keyBytes(@NotNull String key) {
    return key.getBytes(StandardCharsets.UTF_8);
  }

  private static byte @NotNull [] channelBytes(@NotNull String channel) {
    return channel.getBytes(StandardCharsets.UTF_8);
  }
}
