import Foundation

public struct ChallengeID: Hashable, Comparable, Codable, CustomStringConvertible, Sendable {
  public let value: Int64
  public init(_ value: Int64) throws {
    guard value > 0 else { throw MapRouletteError(.validation, reason: "ChallengeID must be positive") }
    self.value = value
  }
  /// Encoded as the bare number; decoding rejects non-positive values.
  public init(from decoder: any Decoder) throws {
    let value = try decoder.singleValueContainer().decode(Int64.self)
    guard value > 0 else {
      throw DecodingError.dataCorrupted(
        .init(codingPath: decoder.codingPath, debugDescription: "ChallengeID must be positive"))
    }
    self.value = value
  }
  public func encode(to encoder: any Encoder) throws {
    var container = encoder.singleValueContainer()
    try container.encode(value)
  }
  public static func < (a: Self, b: Self) -> Bool { a.value < b.value }
  public var description: String { String(value) }
}
public struct TaskID: Hashable, Comparable, Codable, CustomStringConvertible, Sendable {
  public let value: Int64
  public init(_ value: Int64) throws {
    guard value > 0 else { throw MapRouletteError(.validation, reason: "TaskID must be positive") }
    self.value = value
  }
  /// Encoded as the bare number; decoding rejects non-positive values.
  public init(from decoder: any Decoder) throws {
    let value = try decoder.singleValueContainer().decode(Int64.self)
    guard value > 0 else {
      throw DecodingError.dataCorrupted(
        .init(codingPath: decoder.codingPath, debugDescription: "TaskID must be positive"))
    }
    self.value = value
  }
  public func encode(to encoder: any Encoder) throws {
    var container = encoder.singleValueContainer()
    try container.encode(value)
  }
  public static func < (a: Self, b: Self) -> Bool { a.value < b.value }
  public var description: String { String(value) }
}
public struct ProjectID: Hashable, Comparable, Codable, CustomStringConvertible, Sendable {
  public let value: Int64
  public init(_ value: Int64) throws {
    guard value > 0 else { throw MapRouletteError(.validation, reason: "ProjectID must be positive") }
    self.value = value
  }
  /// Encoded as the bare number; decoding rejects non-positive values.
  public init(from decoder: any Decoder) throws {
    let value = try decoder.singleValueContainer().decode(Int64.self)
    guard value > 0 else {
      throw DecodingError.dataCorrupted(
        .init(codingPath: decoder.codingPath, debugDescription: "ProjectID must be positive"))
    }
    self.value = value
  }
  public func encode(to encoder: any Encoder) throws {
    var container = encoder.singleValueContainer()
    try container.encode(value)
  }
  public static func < (a: Self, b: Self) -> Bool { a.value < b.value }
  public var description: String { String(value) }
}

/// JSON preserved without discarding unknown geometry properties or cooperative-work fields.
public enum JSONValue: Codable, Hashable, Sendable {
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
public enum LocalSurvey: Int, Hashable, Sendable {
  case exclude = 0
  case include = 1
  case only = 2
}
/// Tags match ANY supplied challenge label, not OSM key/value tags.
public struct ChallengeFilter: Hashable, Sendable {
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
public struct Bounds: Hashable, Sendable {
  public let west: Double, south: Double, east: Double, north: Double
  public init(west: Double, south: Double, east: Double, north: Double) throws {
    guard [west, south, east, north].allSatisfy(\.isFinite), west >= -180, east <= 180, west < east,
      south >= -90, north <= 90, south < north
    else {
      throw MapRouletteError(
        .validation,
        reason: "bounds must be finite, within -180...180 / -90...90, with west < east and south < north")
    }
    self.west = west
    self.south = south
    self.east = east
    self.north = north
  }
}
/// Filters task locations. Empty challengeIDs means all challenges visible to the caller.
/// nil statuses means all statuses; an empty statuses array is invalid. `choiceOnly` sends
/// `cct=3&excludeStale=true` (fork backend): only choice challenges, without tasks found
/// stale. Servers without the filter ignore it, so still check `mobileSupport()`.
public struct TaskFilter: Hashable, Sendable {
  public var challengeIDs: [ChallengeID]
  public var bounds: Bounds
  public var statuses: [Int]?
  public var includeArchived: Bool
  public var choiceOnly: Bool
  public init(
    challengeIDs: [ChallengeID] = [], bounds: Bounds, statuses: [Int]? = [0, 3, 6],
    includeArchived: Bool = false, choiceOnly: Bool = false
  ) {
    self.challengeIDs = challengeIDs
    self.bounds = bounds
    self.statuses = statuses
    self.includeArchived = includeArchived
    self.choiceOnly = choiceOnly
  }
}
public struct Challenge: Hashable, Identifiable, Sendable {
  public let id: ChallengeID, projectID: ProjectID
  public let name: String, instruction: String?, description: String?, tags: [String]?
  public let enabled: Bool?, archived: Bool?, requiresLocal: Bool?
  /// Raw challenge-level hint: 0 none, 1 tag fix, 2 change file. The task's work decides behavior.
  public let cooperativeType: Int?
  public init(
    id: ChallengeID, projectID: ProjectID, name: String, instruction: String? = nil,
    description: String? = nil, tags: [String]? = nil, enabled: Bool? = nil, archived: Bool? = nil,
    requiresLocal: Bool? = nil, cooperativeType: Int? = nil
  ) {
    self.id = id
    self.projectID = projectID
    self.name = name
    self.instruction = instruction
    self.description = description
    self.tags = tags
    self.enabled = enabled
    self.archived = archived
    self.requiresLocal = requiresLocal
    self.cooperativeType = cooperativeType
  }
}
public struct ChallengeTag: Hashable, Identifiable, Sendable {
  public let id: Int64, name: String
  public init(id: Int64, name: String) {
    self.id = id
    self.name = name
  }
}
public struct TaskStatus: Hashable, Sendable {
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
public struct MapRouletteTask: Hashable, Identifiable, Sendable {
  public let id: TaskID, challengeID: ChallengeID
  public let name: String, instruction: String?, status: TaskStatus?
  /// The task's GeoJSON FeatureCollection, as received.
  public let geometry: JSONValue, location: JSONValue?, cooperativeWork: JSONValue?
  /// Current lock holder; only the single-task read reports it (fresh from the database).
  public let lockedBy: Int64?
  public let completedBy: Int64?
  public let mappedOn: String?
  public let reviewStatus: Int?
  public let bundleID: Int64?
  /// OSM changeset recorded for the task's completion, if any.
  public let changesetID: Int64?
  /// For previews, tests and fakes; the SDK builds tasks from server responses.
  public init(
    id: TaskID, challengeID: ChallengeID, name: String, instruction: String? = nil,
    status: TaskStatus? = nil,
    geometry: JSONValue = .object(["type": .string("FeatureCollection"), "features": .array([])]),
    location: JSONValue? = nil, cooperativeWork: JSONValue? = nil, lockedBy: Int64? = nil,
    completedBy: Int64? = nil, mappedOn: String? = nil, reviewStatus: Int? = nil,
    bundleID: Int64? = nil, changesetID: Int64? = nil
  ) {
    self.id = id
    self.challengeID = challengeID
    self.name = name
    self.instruction = instruction
    self.status = status
    self.geometry = geometry
    self.location = location
    self.cooperativeWork = cooperativeWork
    self.lockedBy = lockedBy
    self.completedBy = completedBy
    self.mappedOn = mappedOn
    self.reviewStatus = reviewStatus
    self.bundleID = bundleID
    self.changesetID = changesetID
  }
}
public struct TaskSummary: Hashable, Identifiable, Sendable {
  public let id: TaskID, challengeID: ChallengeID
  public let title: String, status: TaskStatus?, point: JSONValue?
  public init(
    id: TaskID, challengeID: ChallengeID, title: String, status: TaskStatus? = nil,
    point: JSONValue? = nil
  ) {
    self.id = id
    self.challengeID = challengeID
    self.title = title
    self.status = status
    self.point = point
  }
}
/// `scopes` is the bearer grant's scope set, or nil for API-key and anonymous identities.
public struct UserIdentity: Hashable, Identifiable, Sendable {
  public let id: Int64, guest: Bool
  public let scopes: Set<String>?
  public init(id: Int64, guest: Bool, scopes: Set<String>? = nil) {
    self.id = id
    self.guest = guest
    self.scopes = scopes
  }
  /// Bearer grants need `tasks:write`; an API key acts with the user's full authority.
  public var canWriteTasks: Bool { scopes.map { $0.contains("tasks:write") } ?? !guest }
  /// Whether the grant has `osm:tagfix`, needed for choice answers and deletes (OSM edits).
  /// API-key identities report false: the choice route accepts only mobile bearer credentials.
  public var canEditOsm: Bool { scopes?.contains("osm:tagfix") == true && canWriteTasks }
}
/// The only statuses the SDK writes. Deleted, Disabled and Skipped-by-status are deliberately absent.
public enum TaskResolution: Int, Hashable, Sendable, CaseIterable {
  case fixed = 1, notAnIssue = 2, alreadyFixed = 5, tooHard = 6
}
/// A held edit lock. `bundledTaskIDs` is non-empty when the lock covers a task bundle.
public struct TaskLock: Hashable, Sendable {
  public let task: MapRouletteTask, primaryTaskID: TaskID, bundledTaskIDs: [TaskID]
  public init(task: MapRouletteTask, primaryTaskID: TaskID, bundledTaskIDs: [TaskID] = []) {
    self.task = task
    self.primaryTaskID = primaryTaskID
    self.bundledTaskIDs = bundledTaskIDs
  }
}
/// Lifecycle detail attached to write failures. Never contains credentials or raw bodies.
public enum WriteProblem: Hashable, Sendable {
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
  /// Choice submission or check failures.
  case choice(ChoiceProblem)
}
/// Interpretation of a fresh task read after an interrupted status write.
public enum ResolutionCheck: Hashable, Sendable {
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
/// Opaque, in-memory cursor for the next page, bound to one client, operation, query and page
/// size. Passing it to another client or query is a `.validation` error.
public struct PageCursor: Hashable, Sendable {
  let owner: UUID
  let key: String
  let position: Int
}
/// One page of results. `next` is nil on the last page; `total` is reported only by some reads.
public struct Page<Element: Sendable>: Sendable {
  public let items: [Element], next: PageCursor?, total: Int64?
  /// For previews, tests and fakes. A fake page has no `next` cursor: cursors come only from the
  /// client that read the page.
  public init(items: [Element], total: Int64? = nil) {
    self.init(items: items, next: nil, total: total)
  }
  init(items: [Element], next: PageCursor?, total: Int64?) {
    self.items = items
    self.next = next
    self.total = total
  }
}
extension Page: Equatable where Element: Equatable {}
extension Page: Hashable where Element: Hashable {}
/// May gain cases in a minor release before 1.0; include a default branch when switching.
public enum ErrorKind: String, Hashable, Sendable {
  case validation, authentication, permission, notFound, conflict, rateLimit, server, http, network,
    protocolFailure
}
/// Descriptions deliberately omit server bodies, credentials and transport errors.
public struct MapRouletteError: Error, CustomStringConvertible, Hashable, Sendable {
  public let kind: ErrorKind, status: Int?, retryAfter: String?
  public let problem: WriteProblem?
  /// Why a `.validation` error was raised, e.g. "pageSize must be 1...100". Never contains
  /// credentials or server bodies; nil for other kinds.
  public let reason: String?
  public init(
    _ kind: ErrorKind, status: Int? = nil, retryAfter: String? = nil, problem: WriteProblem? = nil,
    reason: String? = nil
  ) {
    self.kind = kind
    self.status = status
    self.retryAfter = retryAfter
    self.problem = problem
    self.reason = reason
  }
  public var description: String {
    "MapRoulette \(kind.rawValue)" + (status.map { " (HTTP \($0))" } ?? "")
      + (reason.map { ": \($0)" } ?? "")
  }
}
