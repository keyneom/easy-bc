/**
 * Central copy for optional i18n extraction (phase 8).
 * UI components should import from here when showing user-facing paragraphs.
 */
export const EC_COPY = {
  title: "Emergency contraception (education only)",
  body: [
    "Emergency contraception has time windows that depend on the product and when unprotected sex occurred. A pharmacist or clinician can help you choose an appropriate option quickly.",
    "When a timed Plan B / ella / copper-IUD event can be matched to a logged incident, the planner uses the model's least-effective scenario to estimate its effect. Missing or contradictory timing receives no numeric credit. This is a planning estimate, not clinical efficacy or medical advice.",
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
