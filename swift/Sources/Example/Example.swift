import Foundation
import MapRoulette

@main struct Example {
  static func main() async throws {
    // The example reads the environment; the SDK never reads files or environment credentials.
    let key = ProcessInfo.processInfo.environment["MAPROULETTE_API_KEY"]
    let client = try MapRouletteClient(apiKey: { key })
    let rawID = CommandLine.arguments.dropFirst().first ?? "16441"
    guard let number = Int64(rawID) else { throw MapRouletteError(.validation) }
    let id = try ChallengeID(number)
    let challenge = try await client.getChallenge(id)
    print("\(challenge.id.value): \(challenge.name)")
    let tasks = try await client.listTasks(id, pageSize: 2)
    for task in tasks.items { print("Task \(task.id.value): \(task.name)") }
    if key != nil {
      let identity = try await client.getCurrentUser()
      print("Authenticated: \(!identity.guest)")
    }
  }
}
