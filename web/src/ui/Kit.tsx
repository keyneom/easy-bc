/**
 * EasyBC UI kit — presentational components matching the profiles/settings
 * redesign mockups (docs/settings-profiles-redesign.md). No data fetching,
 * no sync logic: screens compose these and wire their own handlers.
 *
 * Styles live in kit.css; every color comes from tokens.css, so all
 * components are automatically correct in light, dark, and system themes.
 */
import type { ReactNode } from "react";
import {
  BadgeCheck,
  ChevronDown,
  ChevronRight,
  Cloud,
  Compass,
  Droplets,
  Eye,
  HardDrive,
  Heart,
  Hourglass,
  Link2,
  Loader2,
  Monitor,
  Moon,
  Shield,
  Sun,
  Users,
} from "lucide-react";
import { avatarColor, avatarInitials } from "./avatarColor";
import { useThemeMode, type ThemeMode } from "./theme";

/* ---------- Avatar & profile identity ---------- */

export type EbProfileBadge = "local" | "private" | "shared" | "readonly" | "waiting";
export type EbAvatarSize = "sm" | "md" | "lg" | "xl";

const BADGE_ICONS: Record<EbProfileBadge, typeof Cloud> = {
  local: HardDrive,
  private: Cloud,
  shared: Users,
  readonly: Eye,
  waiting: Hourglass,
};

export function EbAvatar({
  name,
  colorKey,
  photoUrl,
  size = "md",
  badge,
}: {
  name: string;
  /** Stable key for the deterministic color (profileKey); defaults to name. */
  colorKey?: string;
  photoUrl?: string;
  size?: EbAvatarSize;
  badge?: EbProfileBadge;
}) {
  const BadgeIcon = badge ? BADGE_ICONS[badge] : null;
  return (
    <span className={`eb-avatar eb-avatar-${size}`} aria-hidden>
      {photoUrl ? (
        <img src={photoUrl} alt="" />
      ) : (
        <span className="eb-avatar-initials" style={{ background: avatarColor(colorKey ?? name) }}>
          {avatarInitials(name)}
        </span>
      )}
      {BadgeIcon && (
        <span className={`eb-avatar-badge eb-badge-${badge}`}>
          <BadgeIcon />
        </span>
      )}
    </span>
  );
}

export function EbProfileChip({
  name,
  colorKey,
  photoUrl,
  badge,
  onClick,
  compact = false,
}: {
  name: string;
  colorKey?: string;
  photoUrl?: string;
  badge?: EbProfileBadge;
  onClick?: () => void;
  /** Hide the name on narrow layouts; the avatar stays. */
  compact?: boolean;
}) {
  return (
    <button
      type="button"
      className="eb-profile-chip"
      onClick={onClick}
      aria-label={`Active profile: ${name}. Switch profiles`}
    >
      <EbAvatar name={name} colorKey={colorKey} photoUrl={photoUrl} size="sm" badge={badge} />
      {!compact && <span className="eb-profile-chip-name">{name}</span>}
    </button>
  );
}

/* ---------- Layout primitives ---------- */

export function EbGroupLabel({ children }: { children: ReactNode }) {
  return <p className="eb-group-label">{children}</p>;
}

export function EbNavRow({
  icon,
  title,
  value,
  onClick,
  tone = "default",
  trailing,
}: {
  icon?: ReactNode;
  title: string;
  /** Current-value summary — every hub row must render one. */
  value?: string;
  onClick?: () => void;
  tone?: "default" | "shared" | "danger";
  trailing?: ReactNode;
}) {
  return (
    <button type="button" className={`eb-nav-row eb-nav-${tone}`} onClick={onClick}>
      {icon && <span className="eb-nav-icon">{icon}</span>}
      <span className="eb-nav-text">
        <span className="eb-nav-title">{title}</span>
        {value && <span className="eb-nav-value">{value}</span>}
      </span>
      {trailing ?? <ChevronRight className="eb-nav-chevron" aria-hidden />}
    </button>
  );
}

export function EbProfileHeaderCard({
  name,
  meta,
  colorKey,
  photoUrl,
  badge,
  actionLabel,
  onAction,
}: {
  name: string;
  meta: string;
  colorKey?: string;
  photoUrl?: string;
  badge?: EbProfileBadge;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <div className="eb-header-card">
      <EbAvatar name={name} colorKey={colorKey} photoUrl={photoUrl} size="lg" badge={badge} />
      <span className="eb-header-text">
        <span className="eb-header-name">{name}</span>
        <span className="eb-header-meta">{meta}</span>
      </span>
      {actionLabel && (
        <button type="button" className="eb-btn eb-btn-outline eb-btn-sm" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  );
}

/* ---------- Storage mode ---------- */

export type EbStorageMode = "local" | "private" | "shared";

const MODE_ICONS: Record<EbStorageMode, typeof Cloud> = {
  local: HardDrive,
  private: Cloud,
  shared: Users,
};

export function EbModeCard({
  mode,
  title,
  description,
  selected = false,
  pending = false,
  disabled = false,
  onSelect,
}: {
  mode: EbStorageMode;
  title: string;
  description: string;
  selected?: boolean;
  /** Operation in flight: card shows a spinner and must not flip to selected until confirmed. */
  pending?: boolean;
  disabled?: boolean;
  onSelect?: () => void;
}) {
  const Icon = MODE_ICONS[mode];
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      className={`eb-mode-card eb-mode-${mode}${selected ? " eb-mode-selected" : ""}`}
      disabled={disabled || pending}
      onClick={onSelect}
    >
      <span className="eb-mode-radio" aria-hidden />
      <span className="eb-mode-icon" aria-hidden>
        {pending ? <Loader2 className="eb-spin" /> : <Icon />}
      </span>
      <span className="eb-mode-text">
        <span className="eb-mode-title">{title}</span>
        <span className="eb-mode-desc">{description}</span>
      </span>
    </button>
  );
}

/* ---------- Datasets & access ---------- */

export type EbDataset = "cycle" | "plan" | "intimacy" | "sensitive";

const DATASET_ICONS: Record<EbDataset, typeof Cloud> = {
  cycle: Droplets,
  plan: Compass,
  intimacy: Heart,
  sensitive: Shield,
};

export function EbDatasetIcon({ dataset }: { dataset: EbDataset }) {
  const Icon = DATASET_ICONS[dataset];
  return (
    <span className={`eb-ds-icon eb-ds-${dataset}`} aria-hidden>
      <Icon />
    </span>
  );
}

export function EbDatasetRow({
  dataset,
  title,
  summary,
  trailing,
}: {
  dataset: EbDataset;
  title: string;
  summary: string;
  trailing?: ReactNode;
}) {
  return (
    <div className="eb-ds-row">
      <EbDatasetIcon dataset={dataset} />
      <span className="eb-ds-text">
        <span className="eb-ds-title">{title}</span>
        <span className="eb-ds-summary">{summary}</span>
      </span>
      {trailing}
    </div>
  );
}

export type EbAccessLevel = "none" | "view" | "edit";

export function EbAccessSegmented({
  value,
  onChange,
  disabled = false,
  label,
}: {
  value: EbAccessLevel;
  onChange?: (level: EbAccessLevel) => void;
  disabled?: boolean;
  /** Accessible name, e.g. "Mark's access to Cycle & periods". */
  label: string;
}) {
  const levels: { level: EbAccessLevel; text: string }[] = [
    { level: "none", text: "None" },
    { level: "view", text: "View" },
    { level: "edit", text: "Edit" },
  ];
  return (
    <span className="eb-seg" role="radiogroup" aria-label={label}>
      {levels.map(({ level, text }) => (
        <button
          key={level}
          type="button"
          role="radio"
          aria-checked={value === level}
          className={value === level ? "eb-seg-on" : ""}
          disabled={disabled}
          onClick={() => onChange?.(level)}
        >
          {text}
        </button>
      ))}
    </span>
  );
}

/* ---------- People ---------- */

/**
 * Trust levels follow sync-kit rc.11: "verified" = the participant's key is
 * bound to their Google account (ID-token/passkey binding mirrored into the
 * control dataset); "invite" = key only asserted by the invite-link exchange.
 */
export type EbTrust = "verified" | "invite";

export function EbTrustBadge({ trust }: { trust: EbTrust }) {
  return trust === "verified" ? (
    <span className="eb-trust eb-trust-ok">
      <BadgeCheck aria-hidden /> Account-verified
    </span>
  ) : (
    <span className="eb-trust eb-trust-warn">
      <Link2 aria-hidden /> Key from invite link
    </span>
  );
}

export function EbPersonCard({
  name,
  email,
  trust,
  colorKey,
  children,
}: {
  name: string;
  email?: string;
  trust?: EbTrust;
  colorKey?: string;
  /** Expanded content: access grid rows, role actions. */
  children?: ReactNode;
}) {
  return (
    <div className="eb-person">
      <div className="eb-person-top">
        <EbAvatar name={name} colorKey={colorKey ?? email ?? name} size="md" />
        <span className="eb-person-text">
          <span className="eb-person-name">{name}</span>
          {email && <span className="eb-person-email">{email}</span>}
        </span>
        {trust && <EbTrustBadge trust={trust} />}
      </div>
      {children}
    </div>
  );
}

/* ---------- Feedback ---------- */

export type EbBannerTone = "info" | "warn" | "error" | "success";

export function EbBanner({
  tone,
  title,
  actionLabel,
  onAction,
  children,
}: {
  tone: EbBannerTone;
  title?: string;
  actionLabel?: string;
  onAction?: () => void;
  children: ReactNode;
}) {
  return (
    <div className={`eb-banner eb-banner-${tone}`} role={tone === "error" ? "alert" : "status"}>
      <span className="eb-banner-body">
        {title && <span className="eb-banner-title">{title}</span>}
        <span className="eb-banner-text">{children}</span>
      </span>
      {actionLabel && (
        <button type="button" className="eb-banner-action" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  );
}

export function EbStatusRow({
  tone = "ok",
  children,
}: {
  tone?: "ok" | "busy" | "warn" | "error";
  children: ReactNode;
}) {
  return (
    <p className={`eb-status eb-status-${tone}`} role="status">
      {tone === "busy" ? <Loader2 className="eb-spin" aria-hidden /> : <span className="eb-status-dot" aria-hidden />}
      {children}
    </p>
  );
}

/* ---------- Controls ---------- */

export function EbButton({
  variant = "primary",
  disabled,
  onClick,
  children,
}: {
  variant?: "primary" | "outline" | "danger-text";
  disabled?: boolean;
  onClick?: () => void;
  children: ReactNode;
}) {
  const cls =
    variant === "primary"
      ? "eb-btn eb-btn-primary"
      : variant === "outline"
        ? "eb-btn eb-btn-outline"
        : "eb-btn eb-btn-danger-text";
  return (
    <button type="button" className={cls} disabled={disabled} onClick={onClick}>
      {children}
    </button>
  );
}

export function EbPresetChip({
  selected = false,
  disabled = false,
  onClick,
  children,
}: {
  selected?: boolean;
  disabled?: boolean;
  onClick?: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      className={`eb-preset${selected ? " eb-preset-on" : ""}`}
      aria-pressed={selected}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

export function EbExpanderRow({
  label,
  icon,
  tone = "default",
  open = false,
  onToggle,
  children,
}: {
  label: string;
  icon?: ReactNode;
  tone?: "default" | "danger";
  open?: boolean;
  onToggle?: () => void;
  children?: ReactNode;
}) {
  return (
    <div className={`eb-expander eb-expander-${tone}`}>
      <button type="button" className="eb-expander-head" aria-expanded={open} onClick={onToggle}>
        {icon}
        <span>{label}</span>
        <ChevronDown className={`eb-expander-chevron${open ? " eb-open" : ""}`} aria-hidden />
      </button>
      {open && <div className="eb-expander-body">{children}</div>}
    </div>
  );
}

export function EbStepDots({ count, active }: { count: number; active: number }) {
  return (
    <span className="eb-dots" aria-label={`Step ${active + 1} of ${count}`}>
      {Array.from({ length: count }, (_, index) => (
        <span key={index} className={index === active ? "eb-dot-on" : ""} />
      ))}
    </span>
  );
}

/* ---------- Theme mode ---------- */

export function EbThemeModeToggle() {
  const [mode, setMode] = useThemeMode();
  const options: { value: ThemeMode; label: string; icon: typeof Sun }[] = [
    { value: "system", label: "System", icon: Monitor },
    { value: "light", label: "Light", icon: Sun },
    { value: "dark", label: "Dark", icon: Moon },
  ];
  return (
    <span className="eb-seg eb-theme-toggle" role="radiogroup" aria-label="App theme">
      {options.map(({ value, label, icon: Icon }) => (
        <button
          key={value}
          type="button"
          role="radio"
          aria-checked={mode === value}
          className={mode === value ? "eb-seg-on" : ""}
          onClick={() => setMode(value)}
        >
          <Icon aria-hidden /> {label}
        </button>
      ))}
    </span>
  );
}
