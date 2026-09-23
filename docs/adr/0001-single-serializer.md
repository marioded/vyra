# The serializer issue

## Context

Initially we had a custom binary envelope codec (it was called `MessageCodec`) so the `Message` envelope (but the
payload was still handled by the user's selected serializer) never depended on the user's serializer.

Two requirements drove the new direction:

1. No custom codec because the user's `Serializer` handles both the envelope and the payload.
2. The envelope must be inspectable in the broker (e.g. redis-cli) for debugging, observability or just some other
   non-Java software that needs to read the messages. The JSON serializers embed the payload as readable JSON, not
   base64.

## Decision

The builder's `serializer(...)` option now configures the serializer for both the `Message` envelope and application
payloads. The old `MessageCodec` is deleted.

- **Transport is a dumb byte mover**: `send(MessageKind kind, String
  destination, byte[] envelope)` and `setMessageHandler(Function<byte[],
  CompletionStage<Void>>)`. Core serializes/deserializes the envelope; transports only move bytes and use the kind to
  pick the delivery mechanism.
- **Protocol owns envelope serialization**: `encodeEnvelope(Message)` ->
  `serializer.serialize(message)`; `decodeEnvelope(byte[])` ->
  `serializer.deserialize(bytes, Message.class)`. The `RemoteError` error envelope is serialized with the same
  serializer (reserved `messageType`
  `"vyra.error"`).
- **Message-aware JSON serializers** (`vyra-jackson`, `vyra-gson`): when serializing a `Message`, they build the
  envelope JSON manually with fixed camelCase fields as a real JSON value then the payload bytes are JSON produced by
  the same serializer, so they are parsed and embedded. Result: fully readable JSON, like
  `"payload":{"playerId":"p1","score":42}` instead of base64. The envelope field names are fixed regardless of custom
  mapper/Gson settings; the payload follows the custom settings.
- **Other Jackson use cases**: Smile (binary) and other Jackson modules (CBOR) where created for the envelope and
  payload with up to 40% smaller payloads and comparable serialization performance.

## Consequences

### Positive

- One serializer, one mental model; no hand-rolled codec to maintain.
- Fully readable envelopes in redis-cli with the JSON serializers.
- Smile gives a compact binary option with zero new paradigms.
- The wire format is a deliberate per-application choice (JSON readable / Smile compact / custom).

### Negative

- The envelope JSON is larger than the old binary format but Jackson Smile literally mitigates this for the speed and
  size-sensitive use cases.

### Neutral

- Before there was a `MessageKind.id()/fromId()` and the `RemoteError.Code` was a `byte` (0-255). Now they are encoded
  as a simple string, but it's a minor performance issue.

## Alternatives Considered

- We would create a **binary serializer module** using tools like Java's `DataOutputStream` and `DataInputStream` to
  encode the envelope and payload in a compact binary format. This was rejected because it would be slower than Jackson Smile.