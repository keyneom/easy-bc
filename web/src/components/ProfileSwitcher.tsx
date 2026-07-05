import { profileKey } from "../sync/sharedFolderName";
import {
  activeProfileFromRecord,
  canPublishRole,
  findProfile,
  type SharedSyncState,
} from "../sync/sharedTypes";

type Props = {
  sharedSyncState: SharedSyncState;
  onSwitchProfile: (profileKeyValue: string) => void;
  disabled?: boolean;
};

export function ProfileSwitcher({ sharedSyncState, onSwitchProfile, disabled }: Props) {
  const profiles = sharedSyncState.profiles.map((record) => {
    const key = profileKey(record.ownerEmail, record.datasetId);
    const active = activeProfileFromRecord(record, key);
    const isSelf =
      record.ownerEmail.toLowerCase() === sharedSyncState.ownerEmail.toLowerCase() &&
      record.role === "owner";
    return { key, record, active, isSelf };
  });

  return (
    <div className="profile-switcher" aria-label="Encrypted sync profiles">
      <p className="eyebrow">Active profile</p>
      <div className="profile-switcher-list">
        {profiles.map(({ key, record, isSelf }) => (
          <button
            key={key}
            type="button"
            className={
              key === sharedSyncState.activeProfileKey
                ? "profile-switcher-item active"
                : "profile-switcher-item"
            }
            disabled={disabled}
            onClick={() => onSwitchProfile(key)}
          >
            <span className="profile-switcher-label">
              {isSelf ? "My data" : record.folderName}
            </span>
            <span className="profile-switcher-meta">
              {record.ownerEmail}
              {!canPublishRole(record.role) ? " · read-only" : ""}
            </span>
          </button>
        ))}
      </div>
      {(() => {
        const current = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
        if (!current || canPublishRole(current.role)) return null;
        return (
          <p className="sync-notice sync-notice-info" role="status">
            Viewing <strong>{current.folderName}</strong> in read-only mode. Switch to your profile
            to edit.
          </p>
        );
      })()}
    </div>
  );
}
