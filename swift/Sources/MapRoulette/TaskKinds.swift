import Foundation

/// Challenge-level hint only; the task's `work()` decides how a task behaves.
public enum CooperativeType: Sendable, Equatable {
  case none, tags, changeFile, unknown(Int)
}
extension Challenge {
  public var cooperativeKind: CooperativeType {
    switch cooperativeType {
    case nil, 0: return .none
    case 1: return .tags
    case 2: return .changeFile
    case let value?: return .unknown(value)
    }
  }
}

/// How a task is meant to be worked, decoded from its `cooperativeWork` payload.
public enum TaskWork: Sendable, Equatable {
  case standard
  /// Proposed OSM tag changes. Applying them is an OSM write and is not offered by the SDK.
  case tagFix(version: Int, edits: [ElementTagEdit])
  /// An OSC change file for an external editor. Content is not decoded.
  case changeFile(format: String?, encoding: String?)
  /// Unrecognized version/type or malformed payload, preserved as received.
  case unknown(JSONValue)
  /// Multiple-choice questions about one OSM element (`meta.type` 3,
  /// docs/mobile-choice-challenges.md). `match` is the identity guard (empty when absent);
  /// `outcomes` are the declared ones, decoded with the deletion setting passed to `work`. Use
  /// `choiceOutcomes` to include the built-in Too hard.
  case choice(
    element: OSMElementRef, match: [String: String], questions: [ChoiceQuestion],
    outcomes: [ChoiceOutcome])
}
public struct OSMElementRef: Sendable, Hashable {
  public enum ElementType: String, Sendable { case node, way, relation }
  public let type: ElementType, id: Int64
}
/// `element` is nil for created elements, which have no OSM id yet.
public struct ElementTagEdit: Sendable, Equatable {
  public enum Kind: Sendable, Equatable { case modify, create, delete, unknown(String?) }
  public let element: OSMElementRef?, kind: Kind
  public let setTags: [String: String], unsetTags: [String]
}
/// Display-only form fields. Their answers (`completionResponses`) are not submitted.
public enum FormField: Sendable, Equatable {
  case select(name: String, label: String, values: [String])
  case checkbox(name: String, label: String)
}
/// Instruction markdown (not rendered by the SDK) with the form fields it declares.
public struct Instruction: Sendable, Equatable {
  public let markdown: String, formFields: [FormField]
  /// Replaces `{{name}}` with property values (missing → ""), matching the web UI.
  /// `{{{…}}}` short codes and map-viewport properties are left to the app.
  public func render(_ properties: [String: String]) -> String {
    let text = markdown as NSString
    var result = ""
    var position = 0
    for match in propertyTag.matches(in: markdown, range: NSRange(location: 0, length: text.length)) {
      let whole = match.range
      result += text.substring(with: NSRange(location: position, length: whole.location - position))
      result += text.substring(with: match.range(at: 1))
      result += properties[text.substring(with: match.range(at: 2))] ?? ""
      position = whole.location + whole.length
    }
    return result + text.substring(from: position)
  }
}
/// Mobile offers only tasks that are completed in place: valid, unbundled choice tasks that are
/// still actionable. Standard, tag-fix and change-file tasks are unsupported.
public enum MobileSupport: Sendable, Equatable { case inPlace, unsupported }

// Same expressions as the MapRoulette web UI templating.
private func regex(_ pattern: String) -> NSRegularExpression {
  try! NSRegularExpression(pattern: pattern)
}
private let propertyTag = regex(#"(^|[^{])\{\{([^{][^}]*)\}\}"#)
private let shortCode = regex(#"\{\{\{[^}]+\}\}\}|\[[^\]]+\](?=[^(]|$)"#)
private let checkbox = regex(#"checkbox[/ ]?"([^"]+)"\s+name="([^"]+)""#)
private let select = regex(
  #"select[/ ]?"([^"]+)"\s+name="([^"]+)"\s+values="([^"]+)""#)
private let elementID = regex(#"^(node|way|relation)/(\d+)$"#)
private let featureID = regex(#"^(node|way|relation|n|r|w)?/?\d+$"#)
private let featureType = regex(#"^(node|way|relation|n|r|w)"#)
private let digits = regex(#"\d+"#)
private let actionable: Set<Int> = [0, 3, 6]

private func groups(_ expression: NSRegularExpression, _ text: String) -> [String]? {
  let ns = text as NSString
  guard let match = expression.firstMatch(in: text, range: NSRange(location: 0, length: ns.length))
  else { return nil }
  return (0..<match.numberOfRanges).map {
    match.range(at: $0).location == NSNotFound ? "" : ns.substring(with: match.range(at: $0))
  }
}

private struct Malformed: Error {}

extension MapRouletteTask {
  /// Decodes the task kind. The task payload wins over the challenge's cooperativeType. Choice
  /// payloads are validated strictly: any broken rule gives `.unknown`. Pass the client's
  /// `allowElementDeletion` so delete outcomes match what `submitChoice` sends.
  public func work(allowElementDeletion: Bool = false) -> TaskWork {
    guard let raw = cooperativeWork, case .object(let o) = raw else { return .standard }
    do {
      var meta: [String: JSONValue]? = nil
      if case .object(let m) = o["meta"] { meta = m }
      let version = try meta.flatMap { try int($0["version"]) }
      let type = try meta.flatMap { try int($0["type"]) }
      if version == 1 || (version == 2 && type == 1) {
        return .tagFix(version: version!, edits: try edits(o))
      }
      if version == 2 && type == 2 {
        var file: [String: JSONValue] = [:]
        if case .object(let f) = o["file"] { file = f }
        return .changeFile(format: string(file["format"]), encoding: string(file["encoding"]))
      }
      if version == 2 && type == 3 {
        return try choiceWork(o, allowElementDeletion: allowElementDeletion)
      }
      return .unknown(raw)
    } catch {
      return .unknown(raw)
    }
  }

  /// Task instruction when non-blank, otherwise the challenge instruction, with its form fields.
  public func resolvedInstruction(challenge: Challenge?) -> Instruction {
    let markdown =
      instruction.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
      ?? challenge?.instruction ?? ""
    let text = markdown as NSString
    let fields: [FormField] = shortCode.matches(
      in: markdown, range: NSRange(location: 0, length: text.length)
    ).compactMap { match in
      let code = text.substring(with: match.range)
      if let g = groups(checkbox, code) { return .checkbox(name: g[2], label: g[1]) }
      if let g = groups(select, code) {
        // Equivalent to the web's split(/,\s*/).
        let parts = g[3].components(separatedBy: ",")
        let values = [parts[0]] + parts.dropFirst().map { String($0.drop(while: \.isWhitespace)) }
        return .select(name: g[2], label: g[1], values: values)
      }
      return nil
    }
    return Instruction(markdown: markdown, formFields: fields)
  }

  /// Substitution values: primitive properties of all features (later features win) plus
  /// `#mrTaskId`, and `#osmId`/`#osmType` identified from the first feature's id fields.
  /// Map-viewport properties (`#map…`) depend on the app's map and are not supplied.
  public func templateProperties() -> [String: String] {
    var features: [[String: JSONValue]] = []
    if case .object(let g) = geometry, case .array(let list) = g["features"] {
      features = list.compactMap { if case .object(let f) = $0 { return f } else { return nil } }
    }
    var result = ["#mrTaskId": String(id.value)]
    if let feature = features.first {
      var properties: [String: JSONValue] = [:]
      if case .object(let p) = feature["properties"] { properties = p }
      let names = ["@id", "osmid", "osmIdentifier", "id"]
      let raw =
        names.lazy.compactMap { identifier(feature[$0]) }.first
        ?? names.lazy.compactMap { identifier(properties[$0]) }.first
      if let raw, let id = groups(digits, raw)?.first { result["#osmId"] = id }
      let type =
        raw.flatMap { groups(featureType, $0) == nil ? nil : $0 }
        ?? ["type", "@type", "@osm_type"].lazy.compactMap { primitive(properties[$0]) }.first
      if let prefix = type.flatMap({ groups(featureType, $0)?.first }) {
        result["#osmType"] =
          switch prefix {
          case "n", "node": "node"
          case "w", "way": "way"
          default: "relation"
          }
      }
    }
    for feature in features {
      guard case .object(let properties) = feature["properties"] else { continue }
      for (key, value) in properties {
        if let text = primitive(value) { result[key] = text }
      }
    }
    return result
  }

  /// Derived suitability for mobile; the backend stores no such flag. `.inPlace` only for a valid,
  /// unbundled choice task whose status is Created, Skipped or Too hard.
  public func mobileSupport() -> MobileSupport {
    guard bundleID == nil, status.map({ actionable.contains($0.code) }) == true,
      case .choice = work()
    else { return .unsupported }
    return .inPlace
  }

  /// Statuses to offer as bare status writes. Always empty since choice challenges: non-choice
  /// tasks are unsupported on mobile and choice tasks resolve through `submitChoice` (offer
  /// `choiceOutcomes` instead). Kept for source compatibility.
  public func allowedResolutions() -> Set<TaskResolution> { [] }

  /// Whether to offer Skip (`POST task/{id}/skip`): only for `.inPlace` tasks.
  public func canSkip() -> Bool { mobileSupport() == .inPlace }

  /// Applies the verification rules to a fresh `getTask` read after an interrupted status write.
  /// `me` is the caller's MapRoulette user id. A missing `completedBy` is treated as unknown.
  public func verifyResolution(target: TaskResolution, me: Int64) -> ResolutionCheck {
    let code = status?.code
    let byOther = completedBy.map { $0 != me } ?? false
    if code == target.rawValue && !byOther && lockedBy == nil { return .applied }
    if code == target.rawValue && byOther { return .resolvedByOther }
    if code != target.rawValue && !(code.map { actionable.contains($0) } ?? false) {
      return .resolvedByOther
    }
    if let holder = lockedBy, holder != me { return .lockedByOther }
    return lockedBy == me ? .notAppliedLockHeld : .notAppliedUnlocked
  }
}

private func edits(_ raw: [String: JSONValue]) throws -> [ElementTagEdit] {
  var operations: [JSONValue] = []
  if case .array(let list) = raw["operations"] { operations = list }
  return try operations.map { item in
    guard case .object(let operation) = item else { throw Malformed() }
    var data: [String: JSONValue]? = nil
    if case .object(let d) = operation["data"] { data = d }
    let name = string(operation["operationType"])
    let kind: ElementTagEdit.Kind =
      switch name {
      case "modifyElement": .modify
      case "createElement": .create
      case "deleteElement": .delete
      default: .unknown(name)
      }
    var element: OSMElementRef? = nil
    if let id = string(data?["id"]) {
      guard let g = groups(elementID, id), let type = OSMElementRef.ElementType(rawValue: g[1]),
        let number = Int64(g[2])
      else { throw Malformed() }
      element = OSMElementRef(type: type, id: number)
    }
    switch kind {
    case .modify, .delete: if element == nil { throw Malformed() }
    default: break
    }
    var set: [String: String] = [:]
    var unset: [String] = []
    if case .array(let dependents) = data?["operations"] {
      for dependent in dependents {
        guard case .object(let o) = dependent else { throw Malformed() }
        switch string(o["operation"]) {
        case "setTags":
          guard case .object(let tags) = o["data"] else { throw Malformed() }
          for (key, value) in tags {
            guard let text = string(value) else { throw Malformed() }
            set[key] = text
          }
        case "unsetTags":
          guard case .array(let keys) = o["data"] else { throw Malformed() }
          unset += try keys.map { key in
            guard let text = string(key) else { throw Malformed() }
            return text
          }
        default: break
        }
      }
    }
    return ElementTagEdit(element: element, kind: kind, setTags: set, unsetTags: unset)
  }
}

private func int(_ value: JSONValue?) throws -> Int? {
  switch value {
  case nil, .null?: return nil
  case .integer(let v)?:
    guard let result = Int(exactly: v) else { throw Malformed() }
    return result
  default: throw Malformed()
  }
}
private func string(_ value: JSONValue?) -> String? {
  if case .string(let v) = value { return v }
  return nil
}
private func primitive(_ value: JSONValue?) -> String? {
  switch value {
  case .string(let v)?: return v
  case .integer(let v)?: return String(v)
  case .number(let v)?: return String(v)
  case .bool(let v)?: return String(v)
  default: return nil
  }
}
// Valid feature ids are numeric or prefixed (node/1, n1).
private func identifier(_ value: JSONValue?) -> String? {
  primitive(value).flatMap { groups(featureID, $0) == nil ? nil : $0 }
}
