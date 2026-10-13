import { api } from "@/lib/api";

/**
 * The faces a visitor can try on the public sample event: one portrait of each Crew-1 astronaut, cut from
 * NASA photos that are NOT in the sample album, so a match is a real match and not the same picture.
 */
export const SAMPLE_FACES = [
  { id: "glover", src: "/sample/faces/glover.jpg", name: "Victor Glover", first: "Victor" },
  { id: "walker", src: "/sample/faces/walker.jpg", name: "Shannon Walker", first: "Shannon" },
  { id: "hopkins", src: "/sample/faces/hopkins.jpg", name: "Mike Hopkins", first: "Mike" },
  { id: "noguchi", src: "/sample/faces/noguchi.jpg", name: "Soichi Noguchi", first: "Soichi" },
] as const;

export type SampleFace = (typeof SAMPLE_FACES)[number];

export type SampleSummary = { title: string; photoCount: number; readyCount: number };

/** A personal invite to the sample event. Returns the raw invite token. */
export async function newSampleInvite(): Promise<string> {
  const { token } = await api<{ token: string }>("/api/sample/invite", { method: "POST" });
  return token;
}
