import { useState } from "react";
import { Plus } from "lucide-react";
import { profileKey } from "../sync/sharedFolderName";
import { profileDisplayLabel } from "../sync/profileLabels";
import {
  activeProfileFromRecord,
  canPublishRole,
  findProfile,
  type SharedSyncState,
} from "../sync/sharedTypes";

type Props = {
  sharedSyncState: SharedSyncState;
  onSwitchProfile: (profileKeyValue: string) => void;
  onCreateProfile?: (displayName: string) => void;
  disabled?: boolean;
};

export function ProfileSwitcher({
  sharedSyncState,
  onSwitchProfile,
  onCreateProfile,
  disabled,
}: Props) {
  const [newProfileName, setNewProfileName] = useState("");
  const profiles = sharedSyncState.profiles.map((record) => {
    const key = profileKey(record.ownerEmail, record.datasetId);
    const active = activeProfileFromRecord(record, key);
    return { key, record, active };
  });

  return (
    <div className="profile-switcher" aria-label="Encrypted sync profiles">
      <p className="eyebrow">Active profile</p>
      <div className="profile-switcher-list">
        {profiles.map(({ key, record }) => (
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
              {profileDisplayLabel(sharedSyncState, record)}
            </span>
            <span className="profile-switcher-meta">
              {record.ownerEmail}
              {!canPublishRole(record.role) ? " · read-only" : ""}
            </span>
          </button>
        ))}
      </div>
      {onCreateProfile && (
        <div className="profile-switcher-add">
          <input
            type="text"
            value={newProfileName}
            onChange={(event) => setNewProfileName(event.target.value)}
            placeholder="New profile name (e.g. Daughter)"
            disabled={disabled}
            aria-label="New profile name"
          />
          <button
            type="button"
            className="btn-secondary"
            disabled={disabled || !newProfileName.trim()}
            onClick={() => {
              const name = newProfileName.trim();
              if (!name) return;
              onCreateProfile(name);
              setNewProfileName("");
            }}
          >
            <Plus aria-hidden />
            Add profile
          </button>
        </div>
      )}
      {(() => {
        const current = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
        if (!current || canPublishRole(current.role)) return null;
        return (
          <p className="sync-notice sync-notice-info" role="status">
            Viewing <strong>{profileDisplayLabel(sharedSyncState, current)}</strong> in read-only
            mode. Switch to your profile to edit.
          </p>
        );
      })()}
    </div>
  );
}
