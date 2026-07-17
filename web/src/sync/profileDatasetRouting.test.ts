import { describe, expect, it, vi } from "vitest";
import {
  adoptProfileDataset,
  datasetPartForFailure,
  ProfileDatasetOperationError,
  wrapProfileDatasetError,
} from "./profileDatasetRouting";
import { SyncPayloadParseError } from "./types";

describe("profile dataset codec routing", () => {
  it("passes a control dataset ID to the rc19-configured application controller", async () => {
    const controller = { adoptDataset: vi.fn().mockResolvedValue(undefined) };

    await adoptProfileDataset({
      controller,
      baseDatasetId: "primary.g2",
      datasetId: "primary.control",
      controlDatasetId: "primary.control",
      requireOwned: true,
    });

    expect(controller.adoptDataset).toHaveBeenCalledWith(
      "primary.control",
      { requireOwned: true },
    );
  });

  it("passes ordinary split parts through the same application controller", async () => {
    const controller = { adoptDataset: vi.fn().mockResolvedValue(undefined) };

    await adoptProfileDataset({
      controller,
      baseDatasetId: "primary.g2",
      datasetId: "primary.g2.cycle",
      controlDatasetId: "primary.control",
      requireOwned: false,
    });

    expect(controller.adoptDataset).toHaveBeenCalledWith(
      "primary.g2.cycle",
      { requireOwned: false },
    );
  });

  it("preserves redacted parser diagnostics without payload values", () => {
    const wrapped = wrapProfileDatasetError(
      new SyncPayloadParseError(
        "missing-field",
        "$.schemaVersion",
        "missing",
      ),
      {
        baseDatasetId: "primary.g2",
        datasetId: "primary.g2.sensitive",
        controlDatasetId: "primary.control",
        stage: "load",
      },
    );

    expect(wrapped).toBeInstanceOf(ProfileDatasetOperationError);
    expect(wrapped.failure).toEqual({
      datasetId: "primary.g2.sensitive",
      datasetPart: "sensitive",
      stage: "load",
      reasonCode: "missing-field",
      path: "$.schemaVersion",
      observedType: "missing",
    });
    expect(wrapped.message).not.toContain("schemaVersion");
  });

  it("recognizes generated base dataset parts", () => {
    expect(datasetPartForFailure("primary.g2", "primary.g2.cycle")).toBe("cycle");
  });
});
