<div align="center">
<img alt="Vyra" src=".github/banner.png" />

<h3>
  Lightweight messaging infrastructure for request/response communication and event-driven architectures.
</h3>
  
<img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-blue?logo=openjdk&logoColor=white" />
<img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue" />
<img alt="Zero dependencies" src="https://img.shields.io/badge/Core%20deps-zero-success" />
</div>


**Vyra turns a message broker into a request/response and event bus.** You define your wire contract as plain Java
records, register handlers on named service targets, and call them from anywhere in the cluster with an explicit
timeout. No code generation, no annotations, no runtime reflection.

```java
// Worker: answer requests on the "game-service" target
vyra.handle("game-service", GetPlayerRequest.class,
            req -> CompletableFuture.completedFuture(new PlayerResponse(req.playerId(), "Mario", 1200)));

// Client: async request with a mandatory timeout
vyra.request("game-service", new GetPlayerRequest("p-100"), PlayerResponse.class, Duration.ofSeconds(3))
        .thenAccept(player -> System.out.println(player.name() + " has " + player.score() + " points"));
```

The transport only moves bytes. Routing, correlation, timeouts and type conversion all live in the core. You can swap
the broker and your code does not change.

## Contents

- [Why Vyra](#why-vyra)
- [Quick start](#quick-start)
- [Messaging patterns](#messaging-patterns)
- [Modules](#modules)
- [Documentation](#documentation)
- [Building from source](#building-from-source)
- [Contributing](#contributing)
- [License](#license)

## Why Vyra

Most Java services that talk to each other end up hand-rolling one of these: a REST client with retries, a JMS wrapper,
a custom socket protocol. Vyra gives you a simple, type-safe, asynchronous request/response and event bus with a small
API and a clear concurrency model.

- **Typed contracts**: requests, responses and events are plain Java records. No code generation, no annotation
  processing.
- **Async by default**: every operation returns a standard `CompletionStage`. Nothing blocks.
- **Explicit timeouts**: every request carries one. A request either completes or fails with `VyraTimeoutException` so
  it never hangs forever.
- **Worker load balancing**: requests to a service target are distributed across all instances listening on it. Add a
  worker, get more throughput.
- **Broadcast queries**: ask every instance of a service at once; the first non-null answer wins, silent nodes stay
  silent.
- **Small API**: just eight methods on `Vyra`, five options on the builder. You can read the whole framework in an hour.

## Quick start

Add the modules you need: core, one transport, one serializer.

```kotlin
dependencies {
    implementation("com.marioded.vyra:vyra-core:0.1.0")
    implementation("com.marioded.vyra:vyra-redis:0.1.0")        // or vyra-inmemory for local dev
    implementation("com.marioded.vyra:vyra-jackson:0.1.0")      // or vyra-gson
}
```

A serializer is required: `vyra-core` deliberately ships without one so it stays dependency-free. All nodes in a network
must use the same serializer.

The in-memory transport runs the whole thing in a single JVM, no Redis needed:

```java
record GetPlayerRequest(String playerId) {}
record PlayerResponse(String playerId, String name, int score) {}

// In production this is your broker (for example Redis).
InMemoryBus bus = new InMemoryBus();

Vyra backend = InMemoryVyra.inMemory(bus).serializer(JacksonSerializer.jackson()).build();
Vyra client  = InMemoryVyra.inMemory(bus).serializer(JacksonSerializer.jackson()).build();

// Stable wire identifiers — these travel on the wire, not class names
backend.register("player.get", GetPlayerRequest.class);
backend.register("player.info", PlayerResponse.class);
client.register("player.get", GetPlayerRequest.class);
client.register("player.info", PlayerResponse.class);

// handle() registers the handler and starts listening
backend.handle("game-service", GetPlayerRequest.class, req ->
CompletableFuture.completedFuture(new PlayerResponse(req.playerId(), "Mario", 1200)));

// request() returns a CompletionStage and requires a timeout
client.request("game-service", new GetPlayerRequest("p-100"), PlayerResponse.class, Duration.ofSeconds(3))
    .thenAccept(player -> System.out.println("Player: " + player.name() + " (" + player.score() + " pts)"))
    .exceptionally(err -> {
        System.err.println("Request failed: " + err.getMessage());
        return null;
    });
```

## Messaging patterns

| Pattern                | API                                                             | Semantics                                                |
|------------------------|-----------------------------------------------------------------|----------------------------------------------------------|
| Point-to-point request | `request(target, req, type, timeout)`                           | Round-robin across the workers listening on the target   |
| Broadcast query        | `broadcastRequest(target, req, type, timeout)`                  | Every worker on the target; first non-null response wins |
| Event                  | `publish(channel, event)` / `subscribe(channel, type, handler)` | At-most-once broadcast to all subscribers                |

## Modules

| Module          | What it does                                                  | Dependencies |
|-----------------|---------------------------------------------------------------|--------------|
| `vyra-core`     | Message bus, routing, correlation, timeouts, handler dispatch | none         |
| `vyra-redis`    | Redis transport (Lettuce): worker queues + Pub/Sub            | Lettuce      |
| `vyra-inmemory` | In-memory transport for tests and local dev                   | none         |
| `vyra-jackson`  | Jackson serializer: JSON, Smile, CBOR                         | Jackson      |
| `vyra-gson`     | Gson serializer: JSON                                         | Gson         |

## Documentation

**Start here**

- [Getting started](https://github.com/marioded/vyra/wiki/getting-started): the full tutorial, from zero to a working cluster
- [Architecture](ARCHITECTURE.md): how Vyra works under the hood handling routing, correlation, timeouts and concurrency
- [FAQ](https://github.com/marioded/vyra/wiki/faq): quick answers to common questions

**Core concepts**

- [Request / response](https://github.com/marioded/vyra/wiki/request-response): timeouts, broadcast, correlation
- [Events](https://github.com/marioded/vyra/wiki/events): publish/subscribe semantics
- [Transports](https://github.com/marioded/vyra/wiki/transports): Redis setup, in-memory, writing a custom transport
- [Serialization](https://github.com/marioded/vyra/wiki/serialization): JSON, Smile, CBOR and the uniform network constraint

**Operations**

- [Configuration](https://github.com/marioded/vyra/wiki/configuration): node IDs, thread pools, builder options
- [Concurrency](https://github.com/marioded/vyra/wiki/concurrency): the threading model
- [Timeouts & errors](https://github.com/marioded/vyra/wiki/timeouts-and-errors): the exception hierarchy
- [Best practices](https://github.com/marioded/vyra/wiki/best-practices): production checklist

**Design**

- [ADRs](docs/adr): the recorded design decisions

## Building from source

Java 17 and Gradle 9.7.1 (wrapper included):

```bash
.\gradlew.bat build        # Windows
./gradlew build            # Linux/macOS
```

Module-scoped tests: `.\gradlew.bat :vyra-core:test` and so on. The `vyra-redis` integration tests use Testcontainers
and need Docker.

## Contributing

Bug reports and feature requests go in the issue tracker. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development
workflow.

## License

Apache License 2.0. See [LICENSE](LICENSE).