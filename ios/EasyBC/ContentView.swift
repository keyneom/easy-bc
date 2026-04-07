import SwiftUI

/// SwiftUI shell: same JSON strings as web/WASM. After `ios/build-xcframework.sh` + `uniffi-bindgen` Swift output, replace `planJsonPlaceholder` with the generated `planFertilityRiskPlannerJson`.
struct ContentView: View {
    @State private var input: String = """
    {"ageYears":34,"horizonYears":2,"targetCumulativeFailure":0.05,"cycleLengthDays":28,
    "actsPerWeek":3.5,"condomMode":"perfect","streakAversion":0.5,"holdLifecycleConstant":false,
    "realizedCumulativeRisk":0,"withdrawalRelativeRisk":0.35,"ovulationSdDays":3}
    """
    @State private var output: String = ""
    @State private var errorMessage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("easy-bc").font(.title)
            Text("Build XCFramework (see ios/build-xcframework.sh), add uniFFI Swift, then call plan JSON.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            TextEditor(text: $input)
                .font(.system(.body, design: .monospaced))
                .frame(minHeight: 160)
                .accessibilityLabel("UserOptions JSON")
            Button("Plan (native)") {
                errorMessage = nil
                output = ""
                do {
                    output = try planJsonPlaceholder(input.trimmingCharacters(in: .whitespacesAndNewlines))
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
            if let err = errorMessage {
                Text(err).foregroundStyle(.red)
            }
            if !output.isEmpty {
                ScrollView {
                    Text(output).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
                .frame(maxHeight: 240)
            }
            Spacer()
        }
        .padding()
    }

    private func planJsonPlaceholder(_ json: String) throws -> String {
        throw NSError(
            domain: "easy-bc",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Wire uniFFI planFertilityRiskPlannerJson; input size \(json.count) chars."]
        )
    }
}

#Preview {
    ContentView()
}
