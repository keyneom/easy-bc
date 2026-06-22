/** Wall-calendar period tracking (local dates, YYYY-MM-DD). */

export type PeriodRecord = {
  /** First day of bleeding (inclusive). */
  start: string;
  /** Last day of bleeding (inclusive). Omit if still flowing or not logged yet. */
  end?: string;
  /** Android-compatible free-text context for this bleeding episode. */
  note?: string;
  /** Exclude this episode from prediction statistics without deleting it. */
  excludeFromStats?: boolean;
  /** Used to resolve cross-device edits. */
  updatedAt?: string;
};
