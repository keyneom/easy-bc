//! Read JSON `UserOptions` from stdin, print planner JSON (for Node parity / fixtures).
use std::io::Read;

fn main() {
    let mut buf = String::new();
    std::io::stdin()
        .read_to_string(&mut buf)
        .expect("read stdin");
    let trimmed = buf.trim();
    match planner_core::plan_from_json(trimmed) {
        Ok(s) => print!("{s}"),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
    }
}
