import Foundation

public struct ChallengeID: Hashable, Sendable {
  public let value: Int64
  public init(_ value: Int64) throws {
    guard value > 0 else { throw MapRouletteError(.validation) }
    self.value = value
  }
}
public struct TaskID: Hashable, Sendable {
  public let value: Int64
  public init(_ value: Int64) throws {
    guard value > 0 else { throw MapRouletteError(.validation) }
    self.value = value
  }
}
public struct ProjectID: Hashable, Sendable {
  public let value: Int64
  public init(_ value: Int64) throws {
    guard value > 0 else { throw MapRouletteError(.validation) }
    self.value = value
  }
}

/// JSON preserved without discarding unknown geometry properties or cooperative-work fields.
public enum JSONValue: Codable, Equatable, Sendable {
  case object([String: JSONValue])
  case array([JSONValue])
  case string(String)
  case integer(Int64)
  case number(Double)
  case bool(Bool)
  case null
  public init(from decoder: any Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() {
      self = .null
    } else if let v = try? c.decode(Bool.self) {
      self = .bool(v)
    } else if let v = try? c.decode(Int64.self) {
      self = .integer(v)
    } else if let v = try? c.decode(Double.self) {
      self = .number(v)
    } else if let v = try? c.decode(String.self) {
      self = .string(v)
    } else if let v = try? c.decode([JSONValue].self) {
      self = .array(v)
    } else {
      self = .object(try c.decode([String: JSONValue].self))
    }
  }
  public func encode(to encoder: any Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .object(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .integer(let v): try c.encode(v)
    case .number(let v): try c.encode(v)
    case .bool(let v): try c.encode(v)
    case .null: try c.encodeNil()
    }
  }
}
public enum LocalSurvey: Int, Sendable {
  case exclude = 0
  case include = 1
  case only = 2
}
/// Tags match ANY supplied challenge label, not OSM key/value tags.
public struct ChallengeFilter: Sendable {
  public var tags: [String]
  public var text: String?
  public var localSurvey: LocalSurvey
  public var includeArchived: Bool
  public var onlyEnabled: Bool
  public init(
    tags: [String] = [], text: String? = nil, localSurvey: LocalSurvey = .include,
    includeArchived: Bool = false, onlyEnabled: Bool = true
  ) {
    self.tags = tags
    self.text = text
    self.localSurvey = localSurvey
    self.includeArchived = includeArchived
    self.onlyEnabled = onlyEnabled
  }
}
public struct Bounds: Sendable {
  public let west: Double, south: Double, east: Double, north: Double
  public init(west: Double, south: Double, east: Double, north: Double) throws {
    guard [west, south, east, north].allSatisfy(\.isFinite), west >= -180, east <= 180, west < east,
      south >= -90, north <= 90, south < north
    else { throw MapRouletteError(.validation) }
    self.west = west
    self.south = south
    self.east = east
    self.north = north
  }
}
/// Filters task locations. Empty challengeIDs means all challenges visible to the caller.
/// nil statuses means all statuses; an empty statuses array is invalid.
public struct TaskFilter: Sendable {
  public var challengeIDs: [ChallengeID]
  public var bounds: Bounds
  public var statuses: [Int]?
  public var includeArchived: Bool
  public init(
    challengeIDs: [ChallengeID] = [], bounds: Bounds, statuses: [Int]? = [0, 3, 6],
    includeArchived: Bool = false
  ) {
    self.challengeIDs = challengeIDs
    self.bounds = bounds
    self.statuses = statuses
    self.includeArchived = includeArchived
  }
}
public struct Challenge: Sendable {
  public let id: ChallengeID, projectID: ProjectID
  public let name: String, instruction: String?, description: String?, tags: [String]?
  public let enabled: Bool?, archived: Bool?, requiresLocal: Bool?
  /// Raw challenge-level hint: 0 none, 1 tag fix, 2 change file. The task's work decides behavior.
  public var cooperativeType: Int? = nil
}
public struct ChallengeTag: Sendable { public let id: Int64, name: String }
public struct TaskStatus: Equatable, Sendable {
  public let code: Int
  public init(code: Int) { self.code = code }
  public var knownName: String? {
    let names = [
      "Created", "Fixed", "NotAnIssue", "Skipped", "Deleted", "AlreadyFixed", "TooHard", "Answered",
      "Validated", "Disabled",
    ]
    return names.indices.contains(code) ? names[code] : nil
  }
}
public struct MapRouletteTask: Sendable {
  public let id: TaskID, challengeID: ChallengeID
  public let name: String, instruction: String?, status: TaskStatus?
  public let geometry: JSONValue, location: JSONValue?, cooperativeWork: JSONValue?
  /// Current lock holder; only the single-task read reports it (fresh from the database).
  public var lockedBy: Int64? = nil
  public var completedBy: Int64? = nil
  public var mappedOn: String? = nil
  public var reviewStatus: Int? = nil
  public var bundleID: Int64? = nil
}
public struct TaskSummary: Sendable {
  public let id: TaskID, challengeID: ChallengeID
  public let title: String, status: TaskStatus?, point: JSONValue?
}
/// `scopes` is the bearer grant's scope set, or nil for API-key and anonymous identities.
public struct UserIdentity: Sendable {
  public let id: Int64, guest: Bool
  public var scopes: Set<String>? = nil
  /// Bearer grants need `tasks:write`; an API key acts with the user's full authority.
  public var canWriteTasks: Bool { scopes.map { $0.contains("tasks:write") } ?? !guest }
}
/// The only statuses the SDK writes. Deleted, Disabled and Skipped-by-status are deliberately absent.
public enum TaskResolution: Int, Sendable, CaseIterable {
  case fixed = 1, notAnIssue = 2, alreadyFixed = 5, tooHard = 6
}
/// A held edit lock. `bundledTaskIDs` is non-empty when the lock covers a task bundle.
public struct TaskLock: Sendable {
  public let task: MapRouletteTask, primaryTaskID: TaskID, bundledTaskIDs: [TaskID]
}
/// Lifecycle detail attached to write failures. Never contains credentials or raw bodies.
public enum WriteProblem: Sendable, Equatable {
  /// 403: another user holds the lock. The message is the server's text and may name that user.
  case lockedByOtherUser(message: String?)
  /// 409: the caller already holds a lock on a different task.
  case alreadyHoldingTask(
    lockedTaskID: TaskID, challengeID: ChallengeID?, challengeName: String?, startedAt: String?)
  /// 403 on refresh: the caller no longer owns the lock.
  case lockLost
  /// 400 on a status write: completed by someone else, invalid status or paused challenge.
  case invalidTransition
  /// 403 insufficient_scope: the bearer grant lacks `tasks:write`; sign in again to grant it.
  case insufficientScope
  /// The request may have been applied (network failure, 5xx or unreadable success). Re-read the
  /// task before acting; never resend a skip or status write blindly.
  case outcomeUnknown
}
/// Interpretation of a fresh task read after an interrupted status write.
public enum ResolutionCheck: Sendable, Equatable {
  /// The target status is recorded and the lock is gone. Do not resend.
  case applied
  /// Not applied and the caller still holds the lock: resending is safe.
  case notAppliedLockHeld
  /// Not applied and unlocked: start again, then resend.
  case notAppliedUnlocked
  /// Another user holds the lock. Do not resend.
  case lockedByOther
  /// Another user completed the task, or it has another final status. Stop.
  case resolvedByOther
}
/// In-memory continuation bound to one client, operation, query and page size.
public struct Continuation: Sendable {
  let owner: UUID
  let key: String
  let position: Int
}
public struct Page<Element: Sendable>: Sendable {
  public let items: [Element], next: Continuation?, total: Int64?
}
public enum ErrorKind: String, Sendable {
  case validation, authentication, permission, notFound, conflict, rateLimit, server, http, network,
    protocolFailure
}
/// Descriptions deliberately omit server bodies, credentials and transport errors.
public struct MapRouletteError: Error, CustomStringConvertible, Sendable {
  public let kind: ErrorKind, status: Int?, retryAfter: String?
  public let problem: WriteProblem?
  public init(
    _ kind: ErrorKind, status: Int? = nil, retryAfter: String? = nil, problem: WriteProblem? = nil
  ) {
    self.kind = kind
    self.status = status
    self.retryAfter = retryAfter
    self.problem = problem
  }
  public var description: String {
    "MapRoulette \(kind.rawValue)" + (status.map { " (HTTP \($0))" } ?? "")
  }
}
