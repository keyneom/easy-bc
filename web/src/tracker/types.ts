/** Wall-calendar period tracking (local dates, YYYY-MM-DD). */

export type PeriodRecord = {
  /** First day of bleeding (inclusive). */
  start: string;
  /** Last day of bleeding (inclusive). Omit if still flowing or not logged yet. */
  end?: string;
};
