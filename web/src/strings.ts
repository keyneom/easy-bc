/**
 * Central copy for optional i18n extraction (phase 8).
 * UI components should import from here when showing user-facing paragraphs.
 */
export const EC_COPY = {
  title: "Emergency contraception (education only)",
  body: [
    "Emergency contraception has time windows that depend on the product and when unprotected sex occurred. A pharmacist or clinician can help you choose an appropriate option quickly.",
    "When you log a Plan B / ella / copper-IUD event with its type and timing on a day with an incident, the planner makes a rough, clearly-bounded estimate of how much it lowers that cycle's risk — sooner-before-ovulation doses count for more. This is a planning estimate, not clinical efficacy or medical advice.",
    "If you may be pregnant or have urgent symptoms, seek in-person care.",
  ],
  journalLabel: "Journal only: I used emergency contraception (optional)",
  journalHint:
    "Stored locally; not sent anywhere. To have it affect the risk estimate, log it as a day event with its timing.",
} as const;

/**
 * Guidance shown when an emergency-contraception event is logged on a day.
 */
export const EC_CYCLE_EFFECTS_COPY = {
  heading: "After emergency contraception",
  points: [
    "Your next period may arrive earlier or later than expected, and spotting can occur.",
    "Do not use spotting alone as a new period start in the tracker.",
    "Calendar-based fertile-window estimates are less reliable for the rest of this cycle.",
    "Follow the product instructions or a clinician's advice about pregnancy testing and ongoing contraception.",
  ],
} as const;
