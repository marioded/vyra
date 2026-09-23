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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marioded.vyra.core.exception.VyraRemoteException;
import com.marioded.vyra.core.exception.VyraSerializationException;
import com.marioded.vyra.core.exception.VyraTimeoutException;
import com.marioded.vyra.jackson.JacksonSerializer;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class DefaultVyraTest {

  private static final JacksonSerializer SERIALIZER = JacksonSerializer.jackson();

  private Vyra node(String id, FakeTransport transport) {
    Vyra v = Vyra.builder().transport(transport).nodeId(id).serializer(SERIALIZER).build();
    v.register("echo.req", EchoRequest.class);
    v.register("echo.res", EchoResponse.class);
    v.register("joined", JoinedEvent.class);
    v.register("echo.other", OtherRequest.class);
    return v;
  }

  private static Message decode(byte[] envelope) {
    return SERIALIZER.deserialize(envelope, Message.class);
  }

  @Test
  void requestResponseRoundTrip() throws Exception {
    FakeTransport bus = new FakeTransport();
    Vyra backend = node("backend-1", bus);
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse(req.text())));

    Vyra client = node("client-1", bus);
    EchoResponse response =
        client
            .request("backend", new EchoRequest("hi"), EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    assertEquals("hi", response.text());

    backend.close();
    client.close();
  }

  @Test
  void requestTimesOut() {
    FakeTransport bus = new FakeTransport();
    Vyra client = node("client-2", bus);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "nowhere", new EchoRequest("x"), EchoResponse.class, Duration.ofMillis(200))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraTimeoutException.class, ex.getCause());
    client.close();
  }

  @Test
  void lateResponseIsDiscarded() throws Exception {
    FakeTransport bus = new FakeTransport();
    Vyra client = node("client-3", bus);

    CompletableFuture<EchoResponse> future =
        client
            .request("backend", new EchoRequest("x"), EchoResponse.class, Duration.ofMillis(100))
            .toCompletableFuture();
    Thread.sleep(300); // make the request time out
    ExecutionException first =
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    assertInstanceOf(VyraTimeoutException.class, first.getCause());

    Message requestMessage =
        decode(
            bus.sent.stream()
                .filter(e -> e.kind() == MessageKind.REQUEST && e.destination().equals("backend"))
                .findFirst()
                .orElseThrow()
                .envelope());
    Message late =
        new Message(
            UUID.randomUUID(),
            requestMessage.messageId(),
            "backend-1",
            MessageKind.RESPONSE,
            "client-3",
            "echo.res",
            System.currentTimeMillis(),
            new EchoResponse("late"),
            Map.of());
    bus.send(late.kind(), late.destination(), SERIALIZER.serialize(late));

    // The future is still the timed-out one, not overwritten by the late response.
    ExecutionException second =
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    assertInstanceOf(VyraTimeoutException.class, second.getCause());
    client.close();
  }

  @Test
  void remoteHandlerErrorPropagates() {
    FakeTransport bus = new FakeTransport();
    Vyra backend = node("backend-4", bus);
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.failedFuture(new IllegalStateException("boom")));

    Vyra client = node("client-4", bus);
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraRemoteException.class, ex.getCause());
    assertEquals(RemoteError.Code.HANDLER_ERROR, ((VyraRemoteException) ex.getCause()).getCode());
    backend.close();
    client.close();
  }

  @Test
  void unknownMessageTypeGetsErrorResponse() {
    FakeTransport bus = new FakeTransport();
    // the backend is on backend but has no handler for "echo.req"
    Vyra backend = node("backend-5", bus);
    backend.handle(
        "backend",
        OtherRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse("other")));

    Vyra client = node("client-5", bus);
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraRemoteException.class, ex.getCause());
    assertEquals(
        RemoteError.Code.UNKNOWN_MESSAGE_TYPE, ((VyraRemoteException) ex.getCause()).getCode());
    backend.close();
    client.close();
  }

  @Test
  void publishSubscribeRoundTrip() throws Exception {
    FakeTransport bus = new FakeTransport();
    Vyra backend = node("backend-6", bus);
    BlockingQueue<JoinedEvent> received = new LinkedBlockingQueue<>();
    backend.subscribe("game-events", JoinedEvent.class, received::add);

    Vyra client = node("client-6", bus);
    client
        .publish("game-events", new JoinedEvent("p1"))
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS);

    JoinedEvent event = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(event);
    assertEquals("p1", event.playerId());
    backend.close();
    client.close();
  }

  @Test
  void handlerRunsOnHandlerExecutorNotTransportThread() throws Exception {
    FakeTransport bus = new FakeTransport();
    String mainThread = Thread.currentThread().getName();
    Vyra backend = node("backend-7", bus);
    BlockingQueue<String> handlerThreads = new LinkedBlockingQueue<>();
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> {
          handlerThreads.add(Thread.currentThread().getName());
          return CompletableFuture.completedFuture(new EchoResponse(req.text()));
        });

    Vyra client = node("client-7", bus);
    client
        .request("backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS);

    String threadName = handlerThreads.poll(5, TimeUnit.SECONDS);
    assertNotNull(threadName);
    assertNotEquals(mainThread, threadName);
    backend.close();
    client.close();
  }

  @Test
  void closeCompletesPendingRequestsExceptionally() {
    FakeTransport bus = new FakeTransport();
    Vyra client = node("client-8", bus);
    CompletableFuture<EchoResponse> future =
        client
            .request("backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(30))
            .toCompletableFuture();
    client.close();
    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
  }

  @Test
  void unregisteredRequestClassFailsFast() {
    FakeTransport bus = new FakeTransport();
    Vyra client = node("client-9", bus);
    CompletableFuture<EchoResponse> future =
        client
            .request("backend", "not-a-registered-type", EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture();
    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraSerializationException.class, ex.getCause());
    client.close();
  }

  @Test
  void malformedErrorEnvelopeSurfacesAsSerializationException() {
    FakeTransport bus = new FakeTransport();
    Vyra client = node("client-14", bus);
    // no node is on backend so the request stays pending.
    CompletableFuture<EchoResponse> future =
        client
            .request("backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture();

    // in this case we've got the reserved "vyra.error" and garbage payload (its not a serialized
    // error).
    // it must fail cleanly through RequestManager.handleResponse ->
    // Protocol.deserialize(payload, RemoteError.class): the request future
    // completes with VyraSerializationException, not a crash and other exceptions
    Message requestMessage =
        decode(
            bus.sent.stream()
                .filter(e -> e.kind() == MessageKind.REQUEST && e.destination().equals("backend"))
                .findFirst()
                .orElseThrow()
                .envelope());
    Message malformedError =
        new Message(
            UUID.randomUUID(),
            requestMessage.messageId(),
            "backend-1",
            MessageKind.RESPONSE,
            "client-14",
            "vyra.error",
            System.currentTimeMillis(),
            new byte[] {0x7F, 0x7F},
            Map.of());
    bus.send(
        malformedError.kind(), malformedError.destination(), SERIALIZER.serialize(malformedError));

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraSerializationException.class, ex.getCause());
    client.close();
  }

  @Test
  void registeringReservedMessageTypeFailsFast() {
    FakeTransport bus = new FakeTransport();
    Vyra client = node("client-15", bus);
    assertThrows(
        IllegalArgumentException.class, () -> client.register("vyra.error", EchoRequest.class));
    client.close();
  }

  @Test
  void nullHandlerResultGetsErrorResponse() {
    FakeTransport bus = new FakeTransport();
    Vyra backend = node("backend-12", bus);
    backend.handle("backend", EchoRequest.class, req -> CompletableFuture.completedFuture(null));

    Vyra client = node("client-12", bus);
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraRemoteException.class, ex.getCause());
    assertEquals(RemoteError.Code.HANDLER_ERROR, ((VyraRemoteException) ex.getCause()).getCode());
    backend.close();
    client.close();
  }

  @Test
  void closeDoesNotShutdownSuppliedExecutor() {
    FakeTransport bus = new FakeTransport();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Vyra client =
        Vyra.builder()
            .transport(bus)
            .nodeId("client-13")
            .handlerExecutor(executor)
            .serializer(SERIALIZER)
            .build();
    client.register("echo.req", EchoRequest.class);
    client.register("echo.res", EchoResponse.class);
    client.close();
    assertFalse(executor.isShutdown());
    executor.shutdownNow();
  }

  @Test
  void autoGeneratedNodeIdsAreUnique() {
    FakeTransport bus = new FakeTransport();
    Vyra a = Vyra.builder().transport(bus).serializer(SERIALIZER).build();
    Vyra b = Vyra.builder().transport(bus).serializer(SERIALIZER).build();
    a.register("joined", JoinedEvent.class);
    b.register("joined", JoinedEvent.class);
    a.publish("ch", new JoinedEvent("x")).toCompletableFuture().join();
    b.publish("ch", new JoinedEvent("y")).toCompletableFuture().join();
    assertNotEquals(
        decode(bus.sent.get(0).envelope()).source(), decode(bus.sent.get(1).envelope()).source());
    a.close();
    b.close();
  }

  @Test
  void sameMessageTypeOnDifferentTargetsHasIndependentHandlers() throws Exception {
    FakeTransport bus = new FakeTransport();
    Vyra backend = node("backend-11", bus);
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse("a:" + req.text())));
    backend.handle(
        "admin",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse("b:" + req.text())));

    Vyra client = node("client-11", bus);
    EchoResponse viaBackend =
        client
            .request("backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    EchoResponse viaAdmin =
        client
            .request("admin", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    assertEquals("a:x", viaBackend.text());
    assertEquals("b:x", viaAdmin.text());
    backend.close();
    client.close();
  }

  @Test
  void saturatedHandlerExecutorReturnsServerBusy() throws Exception {
    FakeTransport bus = new FakeTransport();
    // two thread executor with a zero capacity
    ExecutorService executor =
        new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    Vyra backend =
        Vyra.builder()
            .transport(bus)
            .nodeId("backend-13")
            .handlerExecutor(executor)
            .serializer(SERIALIZER)
            .build();
    backend.register("echo.req", EchoRequest.class);
    backend.register("echo.res", EchoResponse.class);
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse(req.text())));

    // saturate the executor
    CountDownLatch blockingStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocking = new CountDownLatch(1);
    executor.execute(
        () -> {
          blockingStarted.countDown();
          try {
            releaseBlocking.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(blockingStarted.await(5, TimeUnit.SECONDS), "blocking task never started");

    // the decode task is admitted on the free thread and submitting the user
    // handler from it must be rejected, so the client receives SERVER_BUSY.
    Vyra client = node("client-13", bus);
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .request(
                        "backend",
                        new EchoRequest("one"),
                        EchoResponse.class,
                        Duration.ofSeconds(5))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
    assertInstanceOf(VyraRemoteException.class, ex.getCause());
    assertEquals(RemoteError.Code.SERVER_BUSY, ((VyraRemoteException) ex.getCause()).getCode());

    releaseBlocking.countDown();
    backend.close();
    client.close();
    executor.shutdownNow();
  }

  @Test
  void userCallbacksRunOnHandlerExecutorNotTransportThread() throws Exception {
    ThreadedTransport transport = new ThreadedTransport();
    ExecutorService handlerExecutor = Executors.newSingleThreadExecutor();
    BlockingQueue<String> executorThread = new LinkedBlockingQueue<>();
    handlerExecutor.execute(() -> executorThread.add(Thread.currentThread().getName()));
    String executorThreadName = executorThread.poll(5, TimeUnit.SECONDS);
    assertNotNull(executorThreadName);

    Vyra client =
        Vyra.builder()
            .transport(transport)
            .nodeId("client-thread-test")
            .handlerExecutor(handlerExecutor)
            .serializer(SERIALIZER)
            .build();
    client.register("echo.req", EchoRequest.class);
    client.register("echo.res", EchoResponse.class);
    client.register("joined", JoinedEvent.class);

    // publish: the transport completes its send stage on its own thread; the
    // user callback must run on the handler executor. The send completion is
    // gated so the callback is attached before the stage completes.
    CountDownLatch releasePublish = new CountDownLatch(1);
    transport.releaseLatches.add(releasePublish);
    BlockingQueue<String> publishThread = new LinkedBlockingQueue<>();
    CompletableFuture<Void> publishFuture =
        client
            .publish("ch", new JoinedEvent("p1"))
            .whenComplete((v, t) -> publishThread.add(Thread.currentThread().getName()))
            .toCompletableFuture();
    releasePublish.countDown();
    publishFuture.get(5, TimeUnit.SECONDS);
    assertEquals(executorThreadName, publishThread.poll(5, TimeUnit.SECONDS));

    // failing request send: the transport fails the send stage on its own
    // thread; the user callback must still run on the handler executor.
    CountDownLatch releaseRequest = new CountDownLatch(1);
    transport.releaseLatches.add(releaseRequest);
    transport.failSends = true;
    BlockingQueue<String> requestThread = new LinkedBlockingQueue<>();
    CompletableFuture<EchoResponse> requestFuture =
        client
            .request("backend", new EchoRequest("x"), EchoResponse.class, Duration.ofSeconds(5))
            .whenComplete((v, t) -> requestThread.add(Thread.currentThread().getName()))
            .toCompletableFuture();
    releaseRequest.countDown();
    assertThrows(ExecutionException.class, () -> requestFuture.get(5, TimeUnit.SECONDS));
    assertEquals(executorThreadName, requestThread.poll(5, TimeUnit.SECONDS));

    client.close();
    handlerExecutor.shutdownNow();
    transport.close();
  }

  @Test
  void garbageEnvelopeIsDroppedAndNodeStaysAlive() throws Exception {
    FakeTransport bus = new FakeTransport();
    Vyra backend = node("backend-16", bus);
    backend.handle(
        "backend",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse(req.text())));

    Vyra client = node("client-16", bus);

    // feed garbage bytes through the transport handler: the node must drop
    // them with a warning and keep running.
    bus.handler.apply(new byte[] {0x00, 0x01, 0x7F, (byte) 0xFF});

    EchoResponse response =
        client
            .request("backend", new EchoRequest("hi"), EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    assertEquals("hi", response.text());

    backend.close();
    client.close();
  }

  @Test
  void broadcastRequestFindsRespondingNodeAndIgnoresSilentNodes() throws Exception {
    FakeTransport bus = new FakeTransport();
    Vyra backend1 = node("backend-1", bus);
    backend1.handle("cluster", EchoRequest.class, req -> CompletableFuture.completedFuture(null));

    Vyra backend2 = node("backend-2", bus);
    backend2.handle(
        "cluster",
        EchoRequest.class,
        req -> CompletableFuture.completedFuture(new EchoResponse("found-on-node-2")));

    Vyra backend3 = node("backend-3", bus);
    backend3.handle(
        "cluster",
        EchoRequest.class,
        req -> CompletableFuture.failedFuture(new RuntimeException("not here!")));

    Vyra client = node("client-broadcast", bus);

    EchoResponse response =
        client
            .broadcastRequest(
                "cluster", new EchoRequest("who-has-it"), EchoResponse.class, Duration.ofSeconds(3))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

    assertEquals("found-on-node-2", response.text());

    backend1.close();
    backend2.close();
    backend3.close();
    client.close();
  }

  @Test
  void broadcastRequestTimesOutWhenAllNodesAreSilent() {
    FakeTransport bus = new FakeTransport();
    Vyra backend1 = node("backend-1", bus);
    backend1.handle("cluster", EchoRequest.class, req -> CompletableFuture.completedFuture(null));

    Vyra backend2 = node("backend-2", bus);
    backend2.handle("cluster", EchoRequest.class, req -> CompletableFuture.completedFuture(null));

    Vyra client = node("client-broadcast-timeout", bus);

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .broadcastRequest(
                        "cluster",
                        new EchoRequest("not-found"),
                        EchoResponse.class,
                        Duration.ofMillis(200))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));

    assertInstanceOf(VyraTimeoutException.class, ex.getCause());

    backend1.close();
    backend2.close();
    client.close();
  }

  record EchoRequest(String text) {}

  record EchoResponse(String text) {}

  record JoinedEvent(String playerId) {}

  record OtherRequest(String text) {}

  /**
   * Transport whose {@code send} completes on a dedicated thread, never on the caller's thread the
   * analogue of a real transport's thread. Each send waits for a release latch (consumed from
   * {@link #releaseLatches}) so tests can attach user callbacks before the stage completes.
   */
  private static final class ThreadedTransport implements Transport {

    private final ExecutorService sendExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "transport-send");
              t.setDaemon(true);
              return t;
            });
    private final BlockingQueue<CountDownLatch> releaseLatches = new LinkedBlockingQueue<>();
    private volatile boolean failSends;

    @Override
    public @NotNull CompletionStage<Void> send(
        @NotNull MessageKind kind, @NotNull String destination, byte @NotNull [] envelope) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      sendExecutor.execute(
          () -> {
            try {
              CountDownLatch release = releaseLatches.poll(5, TimeUnit.SECONDS);
              if (release != null) {
                release.await(5, TimeUnit.SECONDS);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            if (failSends) {
              result.completeExceptionally(new IOException("simulated send failure"));
            } else {
              result.complete(null);
            }
          });
      return result;
    }

    @Override
    public void subscribe(@NotNull MessageKind kind, @NotNull String destination) {}

    @Override
    public void setMessageHandler(@NotNull Function<byte[], CompletionStage<Void>> handler) {}

    @Override
    public void close() {
      sendExecutor.shutdownNow();
    }
  }
}
