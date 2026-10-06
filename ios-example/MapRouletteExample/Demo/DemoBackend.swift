#if DEBUG
  import Foundation
  import MapRoulette

  /// In-process mock backend for `-demo` (screenshots and offline demos). It answers the SDK's
  /// requests from memory and never touches the network. Tasks: 184 eligible; 185 eligible but
  /// its node is in a way (no deletion); 186 no longer eligible; 187 check fails (OSM unavailable);
  /// 188 locked by another user, and OSM refuses its delete (element_in_use); 189 not a choice
  /// task (hidden from the list).
  actor DemoBackend: Transport {
    static let taskIDs: [Int64] = [184, 185, 186, 187, 188]
    private static let me: Int64 = 7
    private var status: [Int64: Int] = [184: 0, 185: 0, 186: 0, 187: 0, 188: 0, 189: 0]
    private var completedBy: [Int64: Int64] = [:]
    private var changesets: [Int64: Int64] = [:]
    private var nextChangeset: Int64 = 9001

    func execute(_ request: HTTPRequest) async throws -> HTTPResponse {
      try await Task.sleep(for: .milliseconds(250))
      let parts = request.url.path.split(separator: "/").dropFirst(2).map(String.init)  // drop api/v2
      switch (request.method, parts) {
      case (.get, ["challenge", "3"]): return ok(challenge)
      case (.get, ["challenge", "3", "tasks"]): return ok((184...189).map { task($0) })
      case (.put, let p) where p.first == "markers": return ok(Self.taskIDs.map(marker))
      case (.get, let p) where p.count == 2 && p[0] == "task":
        return id(p[1]).map { ok(task($0)) } ?? notFound
      case (.get, let p) where p.count == 4 && p[2] == "choice" && p[3] == "check":
        switch id(p[1]) {
        case 186?: return ok(["eligible": false, "reason": "key_changed"])
        case 187?: return HTTPResponse(status: 502, body: json(["error": "osm_unavailable"]))
        case let id?: return ok(["eligible": true, "deleteAllowed": id != 185])
        case nil: return notFound
        }
      case (.get, let p) where p.count == 3 && p[2] == "start":
        guard let id = id(p[1]) else { return notFound }
        var body = task(id)
        body["lockPrimaryTaskId"] = id
        body["lockBundledTasks"] = [Int64]()
        return ok(body)
      case (.get, let p) where p.count == 3 && p[2] == "release": return ok([String: Any]())
      case (.post, let p) where p.count == 3 && p[2] == "skip": return ok([String: Any]())
      case (.post, let p) where p.count == 3 && p[2] == "choice":
        guard let id = id(p[1]), let body = request.body,
          let o = try JSONSerialization.jsonObject(with: body) as? [String: Any]
        else { return notFound }
        return choice(id, o)
      default: return notFound
      }
    }

    private func choice(_ id: Int64, _ body: [String: Any]) -> HTTPResponse {
      var changeset: Int64?
      let result: Int
      if body["answers"] != nil {
        result = 1
        changeset = nextChangeset
      } else {
        let outcome = body["outcome"] as? String
        let delete = body["delete"] as? Bool == true
        if delete && id == 188 { return HTTPResponse(status: 409, body: json(["error": "element_in_use"])) }
        result = outcome == "too-hard" ? 6 : delete ? 1 : 2
        if delete { changeset = nextChangeset }
      }
      if changeset != nil { nextChangeset += 1 }
      status[id] = result
      completedBy[id] = Self.me
      changesets[id] = changeset
      return ok(["status": result, "changesetId": changeset.map { $0 as Any } ?? NSNull()])
    }

    private func id(_ text: String) -> Int64? { Int64(text).flatMap { status[$0] != nil ? $0 : nil } }

    private var notFound: HTTPResponse { HTTPResponse(status: 404, body: json(["message": "Not found"])) }
    private func ok(_ value: Any) -> HTTPResponse { HTTPResponse(status: 200, body: json(value)) }
    private func json(_ value: Any) -> Data { try! JSONSerialization.data(withJSONObject: value) }

    private var challenge: [String: Any] {
      [
        "id": 3, "parent": 1, "name": "Salt Lake City benches",
        "description": "Disposable demo benches around Library Square.",
        "instruction": "Look at the bench and answer what you can see. Leave a question as “Can't tell” if you are not sure.",
        "enabled": true,
      ]
    }

    private func coordinate(_ id: Int64) -> (Double, Double) {
      let i = Double(id - 184)
      return (40.7597 + 0.0012 * (i - 2), -111.8840 + 0.0015 * (i.truncatingRemainder(dividingBy: 2) == 0 ? i : -i))
    }

    private func marker(_ id: Int64) -> [String: Any] {
      let (lat, lng) = coordinate(id)
      return [
        "id": id, "parentId": 3, "title": "Bench node/\(id + 1000)", "status": status[id] ?? 0,
        "point": ["lat": lat, "lng": lng],
      ]
    }

    private func task(_ id: Int64) -> [String: Any] {
      let (lat, lng) = coordinate(id)
      var o: [String: Any] = [
        "id": id, "parent": 3, "name": id == 189 ? "Bench survey (web only)" : "Bench node/\(id + 1000)",
        "instruction": "", "status": status[id] ?? 0,
        "geometries": [
          "type": "FeatureCollection",
          "features": [
            [
              "type": "Feature", "id": "node/\(id + 1000)",
              "geometry": ["type": "Point", "coordinates": [lng, lat]],
              "properties": ["amenity": "bench"],
            ]
          ],
        ],
      ]
      if id != 189 { o["cooperativeWork"] = payload(element: id + 1000) }
      if id == 188 { o["lockedBy"] = 99 }
      if let by = completedBy[id] { o["completedBy"] = by }
      if let changeset = changesets[id] { o["changesetId"] = changeset }
      return o
    }

    /// The pilot payload (docs/mobile-choice-challenges.md §3).
    private func payload(element: Int64) -> [String: Any] {
      func options(_ key: String, _ values: [(String, String)]) -> [[String: Any]] {
        values.map { ["id": $0.0, "label": $0.1, "setTags": [key: $0.0.hasPrefix("c") ? String($0.0.dropFirst()) : $0.0]] }
      }
      return [
        "meta": ["version": 2, "type": 3, "choiceVersion": 1],
        "element": "node/\(element)", "match": ["amenity": "bench"],
        "questions": [
          [
            "id": "backrest", "prompt": "Does the bench have a backrest?",
            "description": "Look at the seat from the side.", "expect": ["backrest": NSNull()],
            "options": [
              ["id": "yes", "label": "Yes", "description": "Seat has back support.", "setTags": ["backrest": "yes"]],
              ["id": "no", "label": "No", "setTags": ["backrest": "no"]],
            ],
          ],
          [
            "id": "material", "prompt": "What is the seat mainly made of?", "expect": ["material": NSNull()],
            "options": options(
              "material", [("wood", "Wood"), ("metal", "Metal"), ("concrete", "Concrete"), ("stone", "Stone")]),
          ],
          [
            "id": "capacity", "prompt": "How many adults can sit on it?", "expect": ["capacity": NSNull()],
            "options": options("capacity", [("c2", "2"), ("c3", "3"), ("c4", "4")]),
          ],
        ],
        "outcomes": [
          ["id": "not-a-bench", "label": "Not a bench", "description": "Something else is here.", "status": 2],
          ["id": "gone", "label": "Bench is gone", "delete": true],
        ],
      ]
    }
  }
#endif
