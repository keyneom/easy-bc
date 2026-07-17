import { describe, expect, it, vi } from "vitest";
import { SingleFlightSessionValue } from "./SingleFlightSessionValue";

describe("SingleFlightSessionValue", () => {
  it("coalesces concurrent unlocks and reuses the value for the tab session", async () => {
    const session = new SingleFlightSessionValue<string>();
    let release!: (value: string) => void;
    const unlocked = new Promise<string>((resolve) => {
      release = resolve;
    });
    const loader = vi.fn(() => unlocked);

    const callers = Array.from({ length: 8 }, () => session.getOrLoad(loader));
    expect(loader).toHaveBeenCalledTimes(1);
    release("unlocked");

    await expect(Promise.all(callers)).resolves.toEqual(Array(8).fill("unlocked"));
    await expect(session.getOrLoad(loader)).resolves.toBe("unlocked");
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it("requires one new unlock after an explicit clear", async () => {
    const session = new SingleFlightSessionValue<string>();
    const loader = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce("identity-1")
      .mockResolvedValueOnce("identity-2");

    await expect(session.getOrLoad(loader)).resolves.toBe("identity-1");
    session.clear();
    expect(session.get()).toBeNull();
    await expect(session.getOrLoad(loader)).resolves.toBe("identity-2");
    expect(loader).toHaveBeenCalledTimes(2);
  });
});
