import assert from "node:assert/strict";
import test from "node:test";

import { verifyForkRequest } from "./verifyForkRequest.mjs";

const TEST_SECRET = "worker-test-secret";
const encoder = new TextEncoder();

async function sign(telegramId, event, timestamp, secret = TEST_SECRET) {
    const key = await crypto.subtle.importKey(
        "raw",
        encoder.encode(secret),
        { name: "HMAC", hash: "SHA-256" },
        false,
        ["sign"],
    );
    const signature = await crypto.subtle.sign(
        "HMAC",
        key,
        encoder.encode(`${telegramId}:${event}:${timestamp}`),
    );
    return Array.from(new Uint8Array(signature), value => value.toString(16).padStart(2, "0")).join("");
}

async function createRequest(overrides = {}, signatureSource = {}) {
    const timestamp = overrides.timestamp ?? Math.floor(Date.now() / 1000);
    const telegramId = overrides.telegram_id ?? "123456789";
    const event = overrides.event ?? "login";
    const signature = overrides.signature ?? await sign(
        signatureSource.telegram_id ?? telegramId,
        signatureSource.event ?? event,
        signatureSource.timestamp ?? timestamp,
    );

    return new Request("https://worker.example/fork-notify", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            telegram_id: telegramId,
            event,
            timestamp,
            signature,
            first_name: "Test",
            ...overrides,
        }),
    });
}

test("uses the canonical HMAC-SHA256 protocol vector", async () => {
    const signature = await sign("123456789", "login", 1700000000);

    assert.equal(
        signature,
        "3f946110d6503fd312b9b999d80ea6f8e6a91df154c857111876db135b68f0bf",
    );
});

test("accepts a valid signed request", async () => {
    const result = await verifyForkRequest(
        await createRequest(),
        { FORK_API_SECRET: TEST_SECRET },
    );

    assert.equal(result.ok, true);
    assert.equal(result.payload.telegram_id, "123456789");
    assert.equal(result.payload.first_name, "Test");
});

test("rejects an expired timestamp", async () => {
    const timestamp = Math.floor(Date.now() / 1000) - 301;
    const result = await verifyForkRequest(
        await createRequest({ timestamp }),
        { FORK_API_SECRET: TEST_SECRET },
    );

    assert.equal(result.ok, false);
    assert.equal(result.response.status, 401);
});

test("rejects a timestamp too far in the future", async () => {
    const timestamp = Math.floor(Date.now() / 1000) + 301;
    const result = await verifyForkRequest(
        await createRequest({ timestamp }),
        { FORK_API_SECRET: TEST_SECRET },
    );

    assert.equal(result.ok, false);
    assert.equal(result.response.status, 401);
});

test("rejects payload tampering", async () => {
    const result = await verifyForkRequest(
        await createRequest({ telegram_id: "987654321" }, { telegram_id: "123456789" }),
        { FORK_API_SECRET: TEST_SECRET },
    );

    assert.equal(result.ok, false);
    assert.equal(result.response.status, 401);
});

test("rejects a malformed signature", async () => {
    const result = await verifyForkRequest(
        await createRequest({ signature: "not-a-signature" }),
        { FORK_API_SECRET: TEST_SECRET },
    );

    assert.equal(result.ok, false);
    assert.equal(result.response.status, 401);
});

test("fails closed when the Worker secret is missing", async () => {
    const result = await verifyForkRequest(await createRequest(), {});

    assert.equal(result.ok, false);
    assert.equal(result.response.status, 500);
});
