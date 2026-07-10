/**
 * Living component gallery for the EasyBC UI kit.
 * Open the app with `?uikit` (e.g. http://localhost:5173/?uikit) to view.
 * Every component below is presentational; state here is demo-only.
 */
import { useState } from "react";
import { Bell, CalendarDays, Droplets, RefreshCw, Settings, User } from "lucide-react";
import {
  EbAccessSegmented,
  EbAvatar,
  EbBanner,
  EbButton,
  EbDatasetRow,
  EbExpanderRow,
  EbGroupLabel,
  EbModeCard,
  EbNavRow,
  EbPersonCard,
  EbPresetChip,
  EbProfileChip,
  EbProfileHeaderCard,
  EbStatusRow,
  EbStepDots,
  EbThemeModeToggle,
  type EbAccessLevel,
  type EbStorageMode,
} from "./Kit";

export function UiKitGallery() {
  const [mode, setMode] = useState<EbStorageMode>("shared");
  const [access, setAccess] = useState<EbAccessLevel>("edit");
  const [preset, setPreset] = useState("Cycle only");
  const [dangerOpen, setDangerOpen] = useState(false);

  return (
    <div style={{ maxWidth: 560, margin: "0 auto", padding: "24px 16px 80px" }}>
      <h1 style={{ marginBottom: 4 }}>EasyBC UI kit</h1>
      <p style={{ color: "var(--eb-muted)", marginTop: 0 }}>
        Tokens: <code>ui/tokens.css</code> · Components: <code>ui/Kit.tsx</code> · Spec:{" "}
        <code>docs/ui-kit.md</code>
      </p>

      <EbGroupLabel>Theme</EbGroupLabel>
      <EbThemeModeToggle />

      <EbGroupLabel>Avatars & profile identity</EbGroupLabel>
      <div style={{ display: "flex", gap: 14, alignItems: "center", flexWrap: "wrap" }}>
        <EbAvatar name="Leslie" size="xl" badge="shared" />
        <EbAvatar name="Emma" size="lg" badge="local" />
        <EbAvatar name="Rachel Green" size="md" badge="readonly" />
        <EbAvatar name="Mark T" size="sm" badge="waiting" />
        <EbProfileChip name="Leslie" badge="shared" onClick={() => {}} />
        <EbProfileChip name="Emma" badge="local" compact onClick={() => {}} />
      </div>

      <EbGroupLabel>Profile header</EbGroupLabel>
      <EbProfileHeaderCard
        name="Leslie"
        meta="Shared — 2 people · synced 2 min ago"
        badge="shared"
        actionLabel="Switch"
        onAction={() => {}}
      />

      <EbGroupLabel>Settings hub rows</EbGroupLabel>
      <EbNavRow icon={<User />} title="Plan basics" value="Age 34 · 28-day cycle" onClick={() => {}} />
      <EbNavRow icon={<Droplets />} title="Protection" value="Condoms (typical) + withdrawal" onClick={() => {}} />
      <EbNavRow icon={<Settings />} title="Storage & sharing" value="Shared with 2 people" tone="shared" onClick={() => {}} />
      <EbNavRow icon={<Bell />} title="Reminders" value="Daily reconcile · 9:00 PM" onClick={() => {}} />

      <EbGroupLabel>Storage mode (radio semantics)</EbGroupLabel>
      <EbModeCard
        mode="local"
        title="This device"
        description="Stays on this phone. No account needed."
        selected={mode === "local"}
        onSelect={() => setMode("local")}
      />
      <EbModeCard
        mode="private"
        title="Private cloud"
        description="Encrypted in your Google Drive; your other devices unlock it with your passkey. Only you."
        selected={mode === "private"}
        onSelect={() => setMode("private")}
      />
      <EbModeCard
        mode="shared"
        title="Shared"
        description="Private cloud, plus invited people can view or edit what you choose."
        selected={mode === "shared"}
        onSelect={() => setMode("shared")}
      />
      <EbStatusRow tone="ok">Last encrypted update 2 min ago · you are the owner</EbStatusRow>
      <EbStatusRow tone="busy">Merging encrypted changes…</EbStatusRow>

      <EbGroupLabel>Datasets</EbGroupLabel>
      <EbDatasetRow dataset="cycle" title="Cycle & periods" summary="You + Mark edit · Rachel views" />
      <EbDatasetRow dataset="plan" title="Plan & settings" summary="You edit · Mark views" />
      <EbDatasetRow dataset="intimacy" title="Intimacy log" summary="Only you" />
      <EbDatasetRow dataset="sensitive" title="Sensitive events" summary="Only you" />

      <EbGroupLabel>People & access</EbGroupLabel>
      <EbPersonCard name="Mark" email="mark.t@gmail.com" trust="verified">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>Cycle & periods</span>
          <EbAccessSegmented value={access} onChange={setAccess} label="Mark's access to Cycle & periods" />
        </div>
      </EbPersonCard>
      <EbPersonCard name="Rachel" email="rachel@gmail.com" trust="invite" />

      <EbGroupLabel>Invite presets</EbGroupLabel>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {["Cycle only", "Cycle partner", "Full partner", "Everything", "Custom…"].map((label) => (
          <EbPresetChip key={label} selected={preset === label} onClick={() => setPreset(label)}>
            {label}
          </EbPresetChip>
        ))}
      </div>

      <EbGroupLabel>Banners</EbGroupLabel>
      <EbBanner tone="warn" title="Legacy encrypted sync found" actionLabel="Migrate" onAction={() => {}}>
        Migrating merges it into the current format — nothing is lost.
      </EbBanner>
      <EbBanner tone="info">Waiting for the owner to accept this profile.</EbBanner>
      <EbBanner tone="error" actionLabel="Retry" onAction={() => {}}>
        Drive permission update failed for mark.t@gmail.com — encryption was already updated.
      </EbBanner>
      <EbBanner tone="success">Invite accepted — Mark can now see Cycle & periods.</EbBanner>

      <EbGroupLabel>Buttons & expander</EbGroupLabel>
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 10 }}>
        <EbButton>
          <RefreshCw size={16} /> Sync now
        </EbButton>
        <EbButton variant="outline">Join a shared profile</EbButton>
        <EbButton variant="danger-text">Delete local profile</EbButton>
      </div>
      <EbExpanderRow
        label="Danger zone — disconnect, reset, delete"
        tone="danger"
        open={dangerOpen}
        onToggle={() => setDangerOpen((open) => !open)}
      >
        <EbButton variant="danger-text">Keep local copy & disconnect</EbButton>
      </EbExpanderRow>

      <EbGroupLabel>Onboarding dots</EbGroupLabel>
      <EbStepDots count={5} active={1} />

      <EbGroupLabel>Calendar today emphasis</EbGroupLabel>
      <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
        <span
          className="tracker-cell-today"
          style={{
            width: 40,
            height: 40,
            borderRadius: "50%",
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          8
        </span>
        <button type="button" className="today-button eb-btn eb-btn-outline eb-btn-sm">
          <CalendarDays size={14} /> Today
        </button>
        <span style={{ fontSize: 12.5, color: "var(--eb-muted)" }}>
          Returning to the Calendar tab always re-centers on the current month.
        </span>
      </div>
    </div>
  );
}
