import type { DatasetPart } from "./datasets";
import { partForDatasetId } from "./datasets";
import { SyncPayloadParseError } from "./types";

type AdoptController = {
  adoptDataset(
    datasetId: string,
    options?: { requireOwned?: boolean },
  ): Promise<unknown>;
};

export type ProfileDatasetStage = "adopt" | "load" | "sync";
export type ProfileDatasetPart = DatasetPart | "control" | "unknown";

export type ProfileDatasetFailure = {
  datasetId: string;
  datasetPart: ProfileDatasetPart;
  stage: ProfileDatasetStage;
  reasonCode: string;
  path?: string;
  observedType?: string;
};

export class ProfileDatasetOperationError extends Error {
  constructor(
    readonly failure: ProfileDatasetFailure,
    options?: { cause?: unknown },
  ) {
    super(
      `Could not ${failure.stage} EasyBC dataset ${failure.datasetId} (${failure.datasetPart}).`,
      options,
    );
    this.name = "ProfileDatasetOperationError";
  }
}

export function datasetPartForFailure(
  baseDatasetId: string,
  datasetId: string,
  controlDatasetId?: string,
): ProfileDatasetPart {
  if (controlDatasetId && datasetId === controlDatasetId) return "control";
  return partForDatasetId(baseDatasetId, datasetId) ?? "unknown";
}

export function wrapProfileDatasetError(
  error: unknown,
  input: {
    baseDatasetId: string;
    datasetId: string;
    controlDatasetId?: string;
    stage: ProfileDatasetStage;
  },
): ProfileDatasetOperationError {
  if (error instanceof ProfileDatasetOperationError) return error;
  const parseError = error instanceof SyncPayloadParseError ? error : undefined;
  const errorCode = typeof error === "object" && error !== null && "code" in error
    ? String((error as { code?: unknown }).code ?? "operation-failed")
    : "operation-failed";
  return new ProfileDatasetOperationError(
    {
      datasetId: input.datasetId,
      datasetPart: datasetPartForFailure(
        input.baseDatasetId,
        input.datasetId,
        input.controlDatasetId,
      ),
      stage: input.stage,
      reasonCode: parseError?.reasonCode ?? errorCode,
      ...(parseError ? { path: parseError.path, observedType: parseError.observedType } : {}),
    },
    { cause: error },
  );
}

/** Attach a safe dataset/part/stage context to sync-kit adoption failures. */
export async function adoptProfileDataset(input: {
  controller: AdoptController;
  baseDatasetId: string;
  datasetId: string;
  controlDatasetId?: string;
  requireOwned: boolean;
}): Promise<void> {
  try {
    await input.controller.adoptDataset(input.datasetId, { requireOwned: input.requireOwned });
  } catch (error) {
    throw wrapProfileDatasetError(error, { ...input, stage: "adopt" });
  }
}
