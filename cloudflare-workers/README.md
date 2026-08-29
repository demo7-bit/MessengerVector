# Vector fork request authentication

The currently deployed Worker handlers are not present in this repository. The
shared verifier below must be copied into and deployed from both existing Worker
projects before releasing the Android change.

The Android client signs this canonical UTF-8 string with HMAC-SHA256:

```text
telegram_id:event:timestamp
```

- `timestamp` is Unix time in seconds.
- `signature` is the 32-byte HMAC encoded as 64 lowercase hexadecimal characters.
- Requests more than 300 seconds in the past or future are rejected.
- `telegram_id` must contain only decimal digits.
- `event` must be either `registration` or `login`.

## Add the check to both Workers

Copy `shared/verifyForkRequest.mjs` into each Worker project and call it before
the existing bot notification logic:

```js
import { verifyForkRequest } from "./verifyForkRequest.mjs";

export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        if (url.pathname !== "/fork-notify") {
            return new Response("Not found", { status: 404 });
        }

        const verification = await verifyForkRequest(request, env);
        if (!verification.ok) {
            return verification.response;
        }

        // Use the already parsed and verified body. Do not call request.json() again.
        return handleForkNotification(verification.payload, env, ctx);
    },
};
```

Store the shared key as an encrypted Worker secret, separately for the Vekki
and VectorReply Worker projects:

```sh
npx wrangler secret put FORK_API_SECRET
```

Do not place the key in `wrangler.toml`, `wrangler.jsonc`, source code, or
logs. Remove the old `X-Vector-Fork-Secret` header check from both handlers.

## Security limits

The shared HMAC key is still present in the Android APK because the device needs
it to create signatures. An attacker who extracts the key can generate valid
requests. The timestamp rejects old requests but does not stop replay within the
five-minute window. Strong client authentication requires a server-issued
one-time challenge and a hardware-backed per-install key, ideally combined with
app/device attestation.

Run the verifier tests with:

```sh
node --test cloudflare-workers/shared/verifyForkRequest.test.mjs
```
