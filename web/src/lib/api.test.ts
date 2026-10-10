import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "./api";

function mockFetch(response: Response | Error) {
  const fn = vi.fn(() => (response instanceof Error ? Promise.reject(response) : Promise.resolve(response)));
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => vi.unstubAllGlobals());

describe("api", () => {
  it("sends JSON with the bearer token and parses the answer", async () => {
    const fetch = mockFetch(new Response(JSON.stringify({ id: "e1" }), { status: 201 }));
    const out = await api<{ id: string }>("/api/events", { token: "t0k", json: { title: "Party" } });

    expect(out).toEqual({ id: "e1" });
    const [path, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe("/api/events");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ Authorization: "Bearer t0k", "Content-Type": "application/json" });
    expect(init.body).toBe(JSON.stringify({ title: "Party" }));
  });

  it("turns the API's error JSON into an ApiError with its code and message", async () => {
    mockFetch(new Response(JSON.stringify({ code: "event_full", message: "This event is full." }), { status: 409 }));
    const err = await api("/api/events/e1/photos", { method: "POST" }).catch((e) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: "event_full", message: "This event is full." });
  });

  it("keeps a generic message when the error body is not JSON", async () => {
    mockFetch(new Response("<html>Bad gateway</html>", { status: 502 }));
    const err = await api("/api/demo").catch((e) => e);
    expect(err).toMatchObject({ status: 502, code: "error", message: "Request failed (502)." });
  });

  it("reports a network failure as offline", async () => {
    mockFetch(new TypeError("Failed to fetch"));
    const err = await api("/api/demo").catch((e) => e);
    expect(err).toMatchObject({ status: 0, code: "offline" });
  });

  it("returns nothing for 204 No Content", async () => {
    mockFetch(new Response(null, { status: 204 }));
    await expect(api("/api/auth/logout", { method: "POST" })).resolves.toBeUndefined();
  });
});
