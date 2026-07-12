import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { EbAvatar, EbProfileChip, type EbProfileBadge } from "../ui/Kit";

/**
 * One row in the global switcher sheet. Rows are precomputed by the caller
 * (App) so this component stays presentational and the label/badge/meta
 * derivations live next to the registry helpers that own them.
 */
export type SwitcherProfileRow = {
  key: string;
  name: string;
  meta: string;
  badge: EbProfileBadge;
  photoUrl?: string;
  active: boolean;
};

/**
 * Global profile chip + switcher sheet (docs/settings-profiles-redesign.md §1).
 *
 * The chip renders the active profile's avatar, name, and storage-mode badge
 * in the top bar of every screen; tapping it opens a bottom sheet listing all
 * profiles plus the Manage action. Switching runs the caller's
 * publish-before-switch routine and shows a progress row until it confirms —
 * the sheet only closes on success, and errors stay visible inside it.
 */
export function ProfileChipSwitcher({
  profiles,
  switchingKey,
  notice,
  onSwitch,
  onManageProfiles,
}: {
  profiles: SwitcherProfileRow[];
  /** Key of the profile a switch is in flight for, or null when idle. */
  switchingKey: string | null;
  /** Error from the last switch attempt; shown inside the open sheet. */
  notice: string | null;
  /** Resolves true when the switch confirmed (closes the sheet). */
  onSwitch: (key: string) => Promise<boolean>;
  onManageProfiles: () => void;
}) {
  const [open, setOpen] = useState(false);
  const active = profiles.find((profile) => profile.active);
  const busy = switchingKey !== null;

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && switchingKey === null) setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, switchingKey]);

  if (!active) return null;

  return (
    <>
      <EbProfileChip
        name={active.name}
        colorKey={active.key}
        photoUrl={active.photoUrl}
        badge={active.badge}
        onClick={() => setOpen(true)}
      />
      {/* Portal: the sticky topbar's backdrop-filter would otherwise become
          the containing block for position:fixed and trap the sheet. */}
      {open &&
        createPortal(
        <div
          className="eb-switcher-overlay"
          onClick={(event) => {
            if (event.target === event.currentTarget && !busy) setOpen(false);
          }}
        >
          <div
            className="eb-switcher-sheet"
            role="dialog"
            aria-modal="true"
            aria-label="Switch profile"
          >
            <div className="eb-switcher-grab" aria-hidden />
            <p className="eb-group-label">Profiles</p>
            {profiles.map((profile) => (
              <button
                key={profile.key}
                type="button"
                className={`eb-switcher-row${profile.active ? " is-active" : ""}`}
                aria-label={
                  profile.active
                    ? `${profile.name} — active profile`
                    : `Switch to ${profile.name}`
                }
                disabled={busy}
                onClick={() => {
                  if (profile.active) {
                    setOpen(false);
                    return;
                  }
                  void onSwitch(profile.key).then((switched) => {
                    if (switched) setOpen(false);
                  });
                }}
              >
                <EbAvatar
                  name={profile.name}
                  colorKey={profile.key}
                  photoUrl={profile.photoUrl}
                  size="md"
                  badge={profile.badge}
                />
                <span className="eb-switcher-text">
                  <span className="eb-switcher-name">{profile.name}</span>
                  <span className="eb-switcher-meta">
                    {switchingKey === profile.key ? "Switching…" : profile.meta}
                  </span>
                </span>
                {profile.active && (
                  <span className="eb-switcher-check" aria-label="Active profile">
                    ✓
                  </span>
                )}
              </button>
            ))}
            {notice && (
              <p className="eb-switcher-notice" role="alert">
                {notice}
              </p>
            )}
            <hr className="eb-switcher-divider" />
            <button
              type="button"
              className="eb-switcher-action"
              disabled={busy}
              onClick={() => {
                setOpen(false);
                onManageProfiles();
              }}
            >
              Manage profiles — new, join, storage & sharing
            </button>
          </div>
        </div>,
          document.body,
        )}
    </>
  );
}
