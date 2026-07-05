import { useEffect, useState } from "react";
import { Download, RefreshCw, X } from "lucide-react";
import { APP_VERSION } from "../generated/appVersion";
import {
  dismissUpdate,
  fetchPublishedVersion,
  isNewerVersion,
  isUpdateDismissed,
} from "../version";

export function UpdateBanner() {
  const [latestVersion, setLatestVersion] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void fetchPublishedVersion()
      .then((info) => {
        if (cancelled || !info) return;
        if (isNewerVersion(info.version, APP_VERSION)) {
          setLatestVersion(info.version);
          setDismissed(isUpdateDismissed(info.version));
        }
      })
      .catch(() => {
        /* offline or blocked fetch */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!latestVersion || dismissed) return null;

  return (
    <div className="update-banner" role="status">
      <Download aria-hidden />
      <div className="update-banner-copy">
        <strong>EasyBC {latestVersion} is available.</strong>
        <span>You are on {APP_VERSION}. Refresh to load the latest web app.</span>
      </div>
      <div className="update-banner-actions">
        <button type="button" className="btn-secondary" onClick={() => window.location.reload()}>
          <RefreshCw aria-hidden />
          Refresh
        </button>
        <button
          type="button"
          className="btn-icon"
          aria-label="Dismiss update notice"
          onClick={() => {
            dismissUpdate(latestVersion);
            setDismissed(true);
          }}
        >
          <X aria-hidden />
        </button>
      </div>
    </div>
  );
}
