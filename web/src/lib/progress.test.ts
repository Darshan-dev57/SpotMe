import { describe, expect, it } from "vitest";
import { parseSse } from "./progress";

describe("parseSse", () => {
  it("returns the data of complete events and keeps the unfinished rest", () => {
    const buffer = 'event:progress\ndata:{"faces":1}\n\nevent:progress\ndata:{"fa';
    expect(parseSse(buffer)).toEqual({ data: ['{"faces":1}'], rest: 'event:progress\ndata:{"fa' });
  });

  it("skips keep-alive comments", () => {
    expect(parseSse(":keep-alive\n\n").data).toEqual([]);
  });

  it("joins multi-line data and accepts CRLF and a space after the colon", () => {
    expect(parseSse("data: a\r\ndata: b\r\n\r\n").data).toEqual(["a\nb"]);
  });
});
