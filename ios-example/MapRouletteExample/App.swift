import MapRoulette
import SwiftUI

@main
struct MapRouletteExampleApp: App {
  @State private var session: AppSession = Self.makeSession()
  @State private var refresh = TaskChanges()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environment(session)
        .environment(refresh)
    }
  }

  @MainActor private static func makeSession() -> AppSession {
    #if DEBUG
      // `-demo`: an in-process mock backend for screenshots and offline demos. No network.
      if ProcessInfo.processInfo.arguments.contains("-demo") { return AppSession(demo: DemoBackend()) }
    #endif
    return AppSession(config: .current)
  }
}

enum Route: Hashable {
  case map
  /// `order` is the list or map order, used for "Next task".
  case task(id: Int64, order: [Int64])
}

/// Counts task screens that may have changed a task; lists and maps reload when it changes.
@MainActor @Observable final class TaskChanges {
  private(set) var version = 0
  func taskChanged() { version += 1 }
}

struct RootView: View {
  @State private var path: [Route] = []

  var body: some View {
    NavigationStack(path: $path) {
      HomeView(path: $path)
        .navigationDestination(for: Route.self) { route in
          switch route {
          case .map: NearbyMapView(path: $path)
          case .task(let id, let order): TaskView(taskID: id, order: order)
          }
        }
    }
    #if DEBUG
      .onAppear {
        // `-demoTask <id>` opens a task directly (screenshots).
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-demoTask"), i + 1 < args.count, let id = Int64(args[i + 1]) {
          path = [.task(id: id, order: DemoBackend.taskIDs)]
        } else if args.contains("-demoMap") {
          path = [.map]
        }
      }
    #endif
  }
}
