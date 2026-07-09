import { describe, expect, it } from "vitest";
import { easyBcSyncFolderName, profileKey, sanitizeOwnerLabel } from "./sharedFolderName";
import { parseJoinLinkParams, stripJoinLinkParams } from "./sharedJoin";

describe("shared folder naming", () => {
  it("builds owner-scoped folder names", () => {
    expect(easyBcSyncFolderName("Alice@Example.com")).toBe(
      "EasyBC — alice@example.com",
    );
  });

  it("builds stable profile keys", () => {
    expect(profileKey("alice@example.com", "primary")).toBe(
      "alice@example.com/primary",
    );
    expect(sanitizeOwnerLabel(" Alice@Example.com ")).toBe("alice@example.com");
  });

  it("clears link-carried invitation and response parameters", () => {
    const result = stripJoinLinkParams(
      new URL(
        "https://example.com/?sk-inv=invite&sk-files=files&sk-resp=1&sk-kr=response&grant-files=1&owner=a",
      ),
    );
    expect(result.search).toBe("");
  });
});

describe("join link parsing", () => {
  it("parses join deeplink params", () => {
    const parsed = parseJoinLinkParams(
      "?sync=join&exchange=ex-1&folder=folder-1&owner=sarah@example.com&invitation=inv-1",
    );
    const syncKitStyle = parseJoinLinkParams(
      "?sync-kit-join=1&sync-kit-folder=folder-1&sync-kit-exchange=ex-1&owner=sarah%40example.com&invitation=inv-1",
    );
    expect(syncKitStyle).toEqual({
      exchangeId: "ex-1",
      appFolderId: "folder-1",
      ownerEmail: "sarah@example.com",
      invitationFileId: "inv-1",
    });
    expect(parsed).toEqual({
      exchangeId: "ex-1",
      appFolderId: "folder-1",
      ownerEmail: "sarah@example.com",
      invitationFileId: "inv-1",
    });
  });
});
