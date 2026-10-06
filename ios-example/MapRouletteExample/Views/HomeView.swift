import AuthenticationServices
import MapRoulette
import SwiftUI

/// A deliberately small SDK consumer: account, demo setting, map entry and a challenge's tasks.
struct HomeView: View {
  @Binding var path: [Route]
  @Environment(AppSession.self) private var session
  @Environment(TaskChanges.self) private var changes
  @Environment(\.webAuthenticationSession) private var webAuth

  @State private var client: SessionClient?
  @State private var challengeInput = ""
  @State private var inputError: String?
  @State private var loaded: ChallengeID?
  @State private var request: Task<Void, Never>?
  @State private var requestToken = UUID()
  @State private var status = "Enter a challenge ID to begin."
  @State private var canRetry = false
  @State private var result: ChallengeResult?
  @State private var signingIn = false

  private struct ChallengeResult {
    let challenge: Challenge
    let tasks: [MapRouletteTask]
  }

  var body: some View {
    @Bindable var session = session
    Form {
      Section {
        Text("Answer multiple-choice tasks about nearby features.")
        Text(session.view.message).font(.subheadline).foregroundStyle(.secondary)
          .accessibilityAddTraits(.updatesFrequently)
        Button(signInLabel) { accountAction() }
          .disabled(!session.signInAvailable || signingIn)
        if session.signInAvailable {
          Text(session.accountHint).font(.footnote).foregroundStyle(.secondary)
        }
      } header: {
        Text("Account")
      }
      if session.writesConfigured {
        Section {
          Toggle("Allow deleting OSM elements", isOn: $session.allowElementDeletion)
        } footer: {
          let server = session.osmServer.map(TaskText.host) ?? "the backend's OpenStreetMap server"
          Text(verbatim: "Demo setting. On: a “gone” outcome deletes the element on \(server). Off (default): it is recorded as Not an issue.")
        }
      }
      Section {
        NavigationLink("Nearby task map", value: Route.map)
      }
      Section {
        TextField("Challenge ID", text: $challengeInput)
          .keyboardType(.numberPad)
          .disabled(request != nil)
        if let inputError { Text(inputError).foregroundStyle(.red).font(.footnote) }
        Button("Load challenge") { loadChallenge() }.disabled(request != nil)
        HStack {
          if request != nil { ProgressView() }
          Text(status).font(.subheadline).accessibilityAddTraits(.updatesFrequently)
        }
        if canRetry { Button("Retry") { loadChallenge() } }
      } header: {
        Text("Challenge")
      }
      if let result { challengeSections(result) }
    }
    .navigationTitle("MapRoulette")
    .onAppear {
      if client == nil {
        client = session.newClient()
        if challengeInput.isEmpty { challengeInput = session.writesConfigured ? "3" : "16441" }
      }
    }
    .onChange(of: session.view.generation) {
      // Reload the list as the new account (or anonymously) after a switch.
      request?.cancel()
      request = nil
      requestToken = UUID()
      client = session.newClient()
      result = nil
      canRetry = false
      if loaded != nil { loadChallenge() }
    }
    .onChange(of: changes.version) {
      // A resolution changes the task list; reload it so the new status shows.
      if loaded != nil, request == nil { loadChallenge() }
    }
  }

  @ViewBuilder private func challengeSections(_ result: ChallengeResult) -> some View {
    Section {
      Text(result.challenge.name).font(.title2.bold())
      Text(verbatim: "Challenge \(result.challenge.id.value)").font(.subheadline).foregroundStyle(.secondary)
      if let description = result.challenge.description, !description.trimmingCharacters(in: .whitespaces).isEmpty {
        Text(description)
      }
    }
    Section("Instructions") {
      let instruction = result.challenge.instruction ?? ""
      Text(instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "No challenge instructions." : instruction)
        .textSelection(.enabled)
    }
    Section("Tasks") {
      if result.tasks.isEmpty { Text("No open multiple-choice tasks in this challenge.") }
      ForEach(result.tasks, id: \.id.value) { task in
        NavigationLink(value: Route.task(id: task.id.value, order: result.tasks.map(\.id.value))) {
          VStack(alignment: .leading) {
            Text(task.name)
            Text(verbatim: "Task \(task.id.value) · \(TaskText.status(task.status?.code))")
              .font(.footnote).foregroundStyle(.secondary)
          }
        }
      }
    }
  }

  private var needsReconsent: Bool {
    var granted = Set<String>()
    if session.view.canWriteTasks { granted.insert(AppSession.writeScope) }
    if session.view.canEditOsm { granted.insert(AppSession.tagfixScope) }
    return AppSession.needsReconsent(writesConfigured: session.writesConfigured, granted: granted)
  }

  private var signInLabel: String {
    if !session.view.signedIn { return "Sign in" }
    return needsReconsent ? "Sign in again to enable editing" : "Sign out"
  }

  private func accountAction() {
    signingIn = true
    Task {
      defer { signingIn = false }
      if session.view.signedIn && !needsReconsent {
        await session.signOut()
      } else {
        await session.signIn { url in
          try await webAuth.authenticate(
            using: url, callbackURLScheme: AuthEndpoints.callbackScheme, preferredBrowserSession: .ephemeral)
        }
      }
    }
  }

  private func loadChallenge() {
    guard request == nil else { return }
    guard let value = Int64(challengeInput.trimmingCharacters(in: .whitespaces)), value > 0,
      let id = try? ChallengeID(value)
    else {
      inputError = "Enter a positive challenge ID"
      return
    }
    guard let client = client?.client else { return }
    inputError = nil
    result = nil
    loaded = id
    canRetry = false
    status = "Loading challenge…"
    let token = UUID()
    requestToken = token
    request = Task {
      defer { if requestToken == token { request = nil } }
      do {
        let challenge = try await client.getChallenge(id)
        let page = try await client.listTasks(id, pageSize: 50)
        try Task.checkCancellation()
        guard requestToken == token else { return }
        // Mobile shows only tasks it can complete in place: valid, open choice tasks.
        let shown = page.items.filter { $0.mobileSupport() == .inPlace }
        result = ChallengeResult(challenge: challenge, tasks: shown)
        let hidden = page.items.count - shown.count
        let hiddenText = hidden > 0 ? " \(hidden) other tasks are hidden: they cannot be completed on mobile." : ""
        status =
          page.next != nil
          ? "Showing \(shown.count) tasks from the first \(page.items.count). This example loads one page.\(hiddenText)"
          : "Loaded \(shown.count) tasks. Tap a task to answer it.\(hiddenText)"
      } catch is CancellationError {
      } catch {
        guard requestToken == token else { return }
        status = Self.errorMessage(error)
        canRetry = true
      }
    }
  }

  private static func errorMessage(_ error: any Error) -> String {
    guard let error = error as? MapRouletteError else { return "Could not load the data. Please retry." }
    switch error.kind {
    case .notFound: return "Not found. Check the challenge ID or try another task."
    case .authentication, .permission: return "This request is not available anonymously. Try a public challenge."
    case .network: return "Could not connect to MapRoulette. Check your connection and retry."
    case .rateLimit: return "MapRoulette is limiting requests. Wait a moment and retry."
    case .server: return "MapRoulette is temporarily unavailable. Please retry."
    default: return "MapRoulette returned an unexpected response. Please retry."
    }
  }
}
