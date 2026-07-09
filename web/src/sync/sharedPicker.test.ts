import { describe, expect, it } from "vitest";
import { validatePickedDatasetFiles } from "./sharedPicker";

describe("validatePickedDatasetFiles", () => {
  it("requires the exact invited dataset files", () => {
    expect(() =>
      validatePickedDatasetFiles(
        [{ fileId: "primary-file" }],
        [{ fileId: "another-file" }],
      ),
    ).toThrow(/did not match/i);
  });

  it("rejects picker cancellation", () => {
    expect(() =>
      validatePickedDatasetFiles([{ fileId: "primary-file" }], []),
    ).toThrow(/select the shared/i);
  });

  it("accepts all invited files in any order", () => {
    expect(() =>
      validatePickedDatasetFiles(
        [{ fileId: "one" }, { fileId: "two" }],
        [{ fileId: "two" }, { fileId: "one" }],
      ),
    ).not.toThrow();
  });
});
