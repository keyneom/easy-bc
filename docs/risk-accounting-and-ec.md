# Risk accounting, ovulation uncertainty, and emergency contraception

EasyBC is a personal planning tool, not a contraceptive or clinical decision
system. Its numbers are posterior-predictive estimates under explicit model
assumptions.

## Ovulation uncertainty broadens risk; it does not raise every day to peak

For an act on calendar day `d`, the planner averages conception probability
over possible ovulation days:

```text
P(conception on d | current information)
  = Σo P(ovulation = o | current information)
       × P(conception | act on d, ovulation = o)
```

This convolution lowers the highest calendar-day estimate and spreads
meaningful risk across more days. That is the statistically coherent result:
not knowing which day is ovulation does not make a particular day as fertile as
a known peak day; it makes more days plausible candidates for the fertile
window. Body signals narrow the posterior and move probability mass back toward
fewer, higher-risk days.

This is consistent with the prospective Wilcox data: ovulation timing and the
fertile window vary substantially even in usually regular cycles, while
day-relative-to-ovulation conception probability remains sharply concentrated
around ovulation.

- [Wilcox et al., NEJM 1995](https://www.nejm.org/doi/full/10.1056/NEJM199512073332301)
- [Wilcox, Dunson, and Baird, BMJ 2000](https://www.bmj.com/content/321/7271/1259)

The current planner still uses a simplified Gaussian ovulation posterior and a
fixed Wilcox-style kernel. It should not be presented as an individualized
clinical probability.

## Discrete incidents are per-act, not frequency-spread

The planner's ordinary day probability spreads expected weekly intercourse
across seven days:

```text
raw_risk_probability
  = per_act_conception_probability × persistent_method_residual
    × acts_per_week / 7
```

That is useful for prospective planning. It is too small for a known,
explicitly logged act. `DayWeight.per_act_conception_probability` therefore
exposes the single-act value before persistent-method residual.

The web and Android aggregators:

1. consider only `condom_broke` and `unplanned_unprotected` events in the
   active cycle;
2. price each as a known act;
3. subtract the plan probability already embedded on each incident day once,
   avoiding double-counting; and
4. sum event marginals as a conservative union bound, capped by the user's
   target.

The union bound is deliberate. Incidents in one cycle share the same unknown
ovulation day, so multiplying marginal survival probabilities would falsely
assume independence. A future core model can integrate all acts jointly over
the latent ovulation day.

## In-flight depletion and release

While the cycle outcome is unknown, incident risk reduces the future-risk
budget. Independent cumulative probabilities compose through survival, so the
core solves:

```text
1 - (1 - realized) × (1 - future) <= target

future_budget = (target - realized) / (1 - realized)
```

Once a new period start is confirmed, the previous cycle is resolved without a
pregnancy. Both clients derive realized risk only from events on or after the
latest period start, so the prior cycle's incident contribution releases
automatically.

## Emergency contraception

Emergency-contraception events are stored with type and optional hours-from-act.
When a dose covers a logged incident this cycle, the planner applies a
**jointly-integrated** efficacy estimate to that incident's per-act risk. The
canonical model is `crates/planner-core/src/ec.rs`. Both clients run that same
Rust code — web via wasm (`ecEffectJson`), Android via the uniFFI native library
(`ecEffectEstimateJson`, called through `PlannerBridge` →
`PlannerRepository.estimateEcEffect`). There is **no** Kotlin reimplementation of
the model; the only client-side EC code is the type/JSON plumbing. The mock
planner bridge (dev builds without the `.so`) reports "no effect" rather than
estimating, so EC is never modeled anywhere but the Rust core.

### Why this is *not* the earlier (rejected) model

An earlier draft multiplied the act's conception probability by an
*unconditional* "ovulation hasn't happened yet" term, treating ovulation timing
and conception probability as independent. They are not — both are functions of
the same latent ovulation day, and Wilcox shows the fertile window is
concentrated just before ovulation. That factorization was rejected.

### The integrated model

For an act on cycle day `a` and a dose on cycle day `t = a + hours/24`:

```text
                 Σ_o P(ov = o) · kernel(a − o) · P(EC fails | o, t, type)
multiplier  =  ──────────────────────────────────────────────────────────
                          Σ_o P(ov = o) · kernel(a − o)
```

`kernel` is the same Wilcox per-act fertility kernel the planner uses and
`P(ov = o)` is the cycle's ovulation posterior, so EC efficacy and the act's own
fertility share one latent ovulation day. `P(EC fails | o, t, type)` is computed
**per ovulation day** from the lead time `o − t`:

- **Levonorgestrel** needs lead time before ovulation to suppress the LH surge;
  it cannot act once ovulation has occurred (`lead ≤ 0 → failure 1`).
- **Ulipristal** acts closer to ovulation (a half-day tolerance past `t`) and is
  generally more effective.
- **Copper IUD** is time-independent within the window (a small flat failure
  floor) — the most effective option.

Dose timing enters only through `t`, so "sooner is better" emerges from the
integral rather than a hand-tuned timeliness table. Three potency presets
(central / optimistic / conservative) bracket parameter uncertainty, so the
estimate is reported as a range, not a false point value. The model also returns
an expected ovulation-delay (the share of ovulation mass still ahead of the dose
× a method maximum), which informs the next-period guidance.

### Calibration and limits

The failure floors and lead-time windows are chosen so a representative dose
lands in published real-world efficacy ranges (levonorgestrel ≈ 50–85%,
ulipristal higher, copper IUD > 99%); `ec.rs` tests assert the central estimate
stays in a defensible band rather than a precise number. **Not modeled:**
body-weight/BMI effects on levonorgestrel, drug interactions, pharmacokinetics.
This is a planning estimate, not clinical efficacy or medical advice.

Sources:

- [FDA: Plan B One-Step information](https://www.fda.gov/drugs/postmarket-drug-safety-information-patients-and-providers/plan-b-one-step-15-mg-levonorgestrel-information)
- [Noé et al., Contraception 2011](https://pubmed.ncbi.nlm.nih.gov/22018122/)
- [ACOG: Emergency Contraception](https://www.acog.org/womens-health/faqs/emergency-contraception)
- [Cleland et al., copper-IUD systematic review](https://pmc.ncbi.nlm.nih.gov/articles/PMC3619968/)

The UI still keeps EC in the cycle history and warns that period timing and
calendar-based predictions may be less reliable afterward. Product labeling or
clinical guidance, not the planner, should drive care decisions.
