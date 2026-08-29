const MAX_REQUEST_AGE_SECONDS = 5 * 60;
const ALLOWED_EVENTS = new Set(["registration", "login"]);
const SIGNATURE_PATTERN = /^[0-9a-f]{64}$/i;
const TELEGRAM_ID_PATTERN = /^[0-9]+$/;
const encoder = new TextEncoder();

export async function verifyForkRequest(request, env) {
    if (request.method !== "POST") {
        return rejected(405, "Method not allowed", { Allow: "POST" });
    }

    let payload;
    try {
        payload = await request.json();
    } catch {
        return rejected(400, "Invalid JSON");
    }

    const telegramId = payload?.telegram_id;
    const event = payload?.event;
    const timestamp = payload?.timestamp;
    const signature = payload?.signature;

    if (typeof telegramId !== "string" || !TELEGRAM_ID_PATTERN.test(telegramId)) {
        return rejected(400, "Invalid telegram_id");
    }
    if (typeof event !== "string" || !ALLOWED_EVENTS.has(event)) {
        return rejected(400, "Invalid event");
    }
    if (typeof timestamp !== "number" || !Number.isSafeInteger(timestamp)) {
        return rejected(400, "Invalid timestamp");
    }
    if (typeof signature !== "string" || !SIGNATURE_PATTERN.test(signature)) {
        return rejected(401, "Unauthorized");
    }

    const secret = env?.FORK_API_SECRET;
    if (typeof secret !== "string" || secret.length === 0) {
        console.error("FORK_API_SECRET is not configured");
        return rejected(500, "Authentication is not configured");
    }

    const now = Math.floor(Date.now() / 1000);
    if (Math.abs(now - timestamp) > MAX_REQUEST_AGE_SECONDS) {
        return rejected(401, "Request timestamp is outside the allowed window");
    }

    const signedData = `${telegramId}:${event}:${timestamp}`;
    let isValid;
    try {
        const key = await crypto.subtle.importKey(
            "raw",
            encoder.encode(secret),
            { name: "HMAC", hash: "SHA-256" },
            false,
            ["verify"],
        );
        const signatureBytes = hexToBytes(signature);
        isValid = await crypto.subtle.verify(
            "HMAC",
            key,
            signatureBytes,
            encoder.encode(signedData),
        );
    } catch (error) {
        console.error("Unable to verify fork request signature", error);
        return rejected(500, "Unable to verify authentication");
    }

    if (!isValid) {
        return rejected(401, "Unauthorized");
    }

    return {
        ok: true,
        payload: {
            ...payload,
            telegram_id: telegramId,
            event,
            timestamp,
        },
    };
}

function hexToBytes(value) {
    const bytes = new Uint8Array(value.length / 2);
    for (let index = 0; index < bytes.length; index++) {
        bytes[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
    }
    return bytes;
}

function rejected(status, error, extraHeaders = {}) {
    return {
        ok: false,
        response: new Response(JSON.stringify({ ok: false, error }), {
            status,
            headers: {
                "Content-Type": "application/json; charset=utf-8",
                "Cache-Control": "no-store",
                ...extraHeaders,
            },
        }),
    };
}
