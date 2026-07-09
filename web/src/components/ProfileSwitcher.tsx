import { useState } from "react";
import { Cloud, HardDrive, Plus, Users } from "lucide-react";
import { profileKey } from "../sync/sharedFolderName";
import { profileDisplayLabel } from "../sync/profileLabels";
import {
  activeProfileFromRecord,
  canPublishRole,
  findProfile,
  isLocalProfile,
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

  const profileMeta = (record: (typeof profiles)[number]["record"]): string => {
    if (isLocalProfile(record)) return "Local only · this device";
    if (record.ownerEmail.toLowerCase() !== sharedSyncState.ownerEmail.toLowerCase()) {
      return `Shared with you · ${record.role} · ${record.ownerEmail}`;
    }
    const participantCount = Object.keys(record.participantEmails ?? {}).length;
    return participantCount > 0
      ? `Shared encrypted · ${participantCount} ${participantCount === 1 ? "person" : "people"}`
      : "Private encrypted · your devices";
  };

  return (
    <div className="profile-switcher" aria-label="Profiles">
      <div className="profile-manager-heading">
        <div>
          <p className="eyebrow">Profile management</p>
          <h4>Your profiles</h4>
        </div>
        <p>Each profile chooses its own storage and sharing. The choices do not affect other profiles.</p>
      </div>
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
            <span className="profile-switcher-icon" aria-hidden>
              {isLocalProfile(record) ? (
                <HardDrive />
              ) : Object.keys(record.participantEmails ?? {}).length > 0 ||
                record.ownerEmail.toLowerCase() !== sharedSyncState.ownerEmail.toLowerCase() ? (
                <Users />
              ) : (
                <Cloud />
              )}
            </span>
            <span className="profile-switcher-copy">
              <span className="profile-switcher-label">
                {profileDisplayLabel(sharedSyncState, record)}
              </span>
              <span className="profile-switcher-meta">
                {profileMeta(record)}
                {record.needsInitialLoad
                  ? " · waiting for owner"
                  : !isLocalProfile(record) && !canPublishRole(record.role)
                    ? " · read-only"
                    : ""}
              </span>
            </span>
            {key === sharedSyncState.activeProfileKey && (
              <span className="profile-active-badge">Active</span>
            )}
          </button>
        ))}
      </div>
      {onCreateProfile && (
        <div className="profile-switcher-add">
          <input
            type="text"
            value={newProfileName}
            onChange={(event) => setNewProfileName(event.target.value)}
            placeholder="New local profile name"
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
            New local profile
          </button>
        </div>
      )}
      {(() => {
        const current = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
        if (!current) return null;
        if (current.needsInitialLoad) {
          return (
            <p className="sync-notice sync-notice-info" role="status">
              Waiting for the owner to accept this profile. After they finish, choose{" "}
              <strong>Merge encrypted changes</strong> to load their data without merging
              data from another profile.
            </p>
          );
        }
        if (canPublishRole(current.role)) return null;
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
