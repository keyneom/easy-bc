/**
 * Central copy for optional i18n extraction (phase 8).
 * UI components should import from here when showing user-facing paragraphs.
 */
export const EC_COPY = {
  title: "Emergency contraception (education only)",
  body: [
    "Emergency contraception has time windows that depend on the product and when unprotected sex occurred. A pharmacist or clinician can help you choose an appropriate option quickly.",
    "This app does not model drug pharmacokinetics, efficacy math, or interactions. Nothing here replaces medical advice or emergency care.",
    "If you may be pregnant or have urgent symptoms, seek in-person care.",
  ],
  journalLabel: "Journal only: I used emergency contraception (optional)",
  journalHint:
    "Stored locally; not sent anywhere. Does not change risk numbers — consider discussing timing with a clinician when replanning.",
} as const;
