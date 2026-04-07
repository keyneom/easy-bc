//! Default narrative scenario: age 34, 20y horizon, 5% cumulative target, 3.5 acts/week, perfect-use condoms.

use planner_core::fertility_risk_planner;
use planner_core::types::{CondomMode, RecommendedAction, UserOptions};

fn main() {
    let opts = UserOptions {
        age_years: 34,
        horizon_years: 20,
        target_cumulative_failure: 0.05,
        acts_per_week: 3.5,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    };

    let out = fertility_risk_planner(opts).expect("valid options");

    println!("=== Inputs ===");
    println!(
        "age {} | horizon {}y | target cumulative {:.1}% | acts/week {:.1} | condoms {:?}",
        out.options_used.age_years,
        out.options_used.horizon_years,
        out.options_used.target_cumulative_failure * 100.0,
        out.options_used.acts_per_week,
        out.options_used.condom_mode
    );

    println!("\n=== Result ===");
    println!(
        "achieved_cumulative_risk: {:.6} ({:.2}%)",
        out.achieved_cumulative_risk,
        out.achieved_cumulative_risk * 100.0
    );
    println!("target_met: {}", out.target_met);
    println!(
        "selected_condom_residual: {:.6}",
        out.validation.selected_condom_residual
    );

    println!("\n=== Warnings ===");
    if out.warnings.is_empty() {
        println!("(none)");
    } else {
        for w in &out.warnings {
            println!("{:?}", w);
        }
    }

    println!("\n=== Year 0 (age {}) ===", out.years[0].age);
    println!(
        "cycle {}d | SD {:.2} | acts/wk {:.2} | cycle_risk {:.4} | annual_risk {:.4}",
        out.years[0].cycle_length_days,
        out.years[0].cycle_sd_days,
        out.years[0].acts_per_week,
        out.years[0].cycle_risk,
        out.years[0].annual_risk
    );
    println!(
        "counts: U={} C={} A={}",
        out.years[0].counts.unprotected, out.years[0].counts.condom, out.years[0].counts.abstain
    );
    let plan0: String = out.years[0]
        .day_weights
        .iter()
        .map(|d| match d.recommended_action {
            RecommendedAction::U => 'U',
            RecommendedAction::W => 'W',
            RecommendedAction::C => 'C',
            RecommendedAction::A => 'A',
        })
        .collect();
    println!("plan (day 1..{}): {}", plan0.len(), plan0);

    println!("\n=== Sample day weights (year 0) ===");
    for d in [1, 8, 12, 14, 20, 28] {
        if let Some(dw) = out.years[0].day_weights.iter().find(|x| x.day == d) {
            println!(
                "  day {:2}: {:?} | rawRisk {} | {}",
                dw.day, dw.recommended_action, dw.raw_risk_score, dw.override_cost.note
            );
        }
    }

    println!(
        "\n=== Last horizon year (age {}) ===",
        out.years.last().unwrap().age
    );
    let yl = out.years.last().unwrap();
    println!(
        "cycle {}d | SD {:.2} | acts/wk {:.2} | counts U={} C={} A={}",
        yl.cycle_length_days,
        yl.cycle_sd_days,
        yl.acts_per_week,
        yl.counts.unprotected,
        yl.counts.condom,
        yl.counts.abstain
    );
}
