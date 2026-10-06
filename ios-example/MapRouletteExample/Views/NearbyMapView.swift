import CoreLocation
import MapKit
import MapRoulette
import SwiftUI

/// Spatial task summaries use lat/lng objects, not GeoJSON geometry objects.
func taskPoint(_ value: JSONValue?) -> CLLocationCoordinate2D? {
  guard case .object(let o)? = value else { return nil }
  func coordinate(_ key: String) -> Double? {
    switch o[key] {
    case .number(let v)?: v.isFinite ? v : nil
    case .integer(let v)?: Double(v)
    default: nil
    }
  }
  guard let lat = coordinate("lat"), let lng = coordinate("lng"), (-90...90).contains(lat),
    (-180...180).contains(lng)
  else { return nil }
  return CLLocationCoordinate2D(latitude: lat, longitude: lng)
}

/// Online map of nearby choice tasks; location is requested only when the user asks.
struct NearbyMapView: View {
  @Binding var path: [Route]
  @Environment(AppSession.self) private var session
  @Environment(TaskChanges.self) private var changes

  private struct Marker: Identifiable {
    let id: Int64
    let title: String
    let coordinate: CLLocationCoordinate2D
  }

  @State private var client: SessionClient?
  @State private var position: MapCameraPosition = .region(
    MKCoordinateRegion(
      center: CLLocationCoordinate2D(latitude: 40.7608, longitude: -111.8910),
      span: MKCoordinateSpan(latitudeDelta: 0.03, longitudeDelta: 0.03)))
  @State private var region: MKCoordinateRegion?
  @State private var markers: [Marker] = []
  @State private var status = "Loading map…"
  @State private var request: Task<Void, Never>?
  @State private var requestToken = UUID()
  @State private var locator = Locator()

  var body: some View {
    VStack(spacing: 0) {
      HStack {
        Button("Search this area") { searchArea() }.disabled(region == nil)
        Spacer()
        Button("My location", systemImage: "location") { locate() }
      }
      .buttonStyle(.bordered)
      .padding(.horizontal)
      .padding(.vertical, 8)
      Text(status)
        .font(.subheadline)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .padding(.bottom, 8)
        .accessibilityAddTraits(.updatesFrequently)
      Map(position: $position) {
        ForEach(markers) { marker in
          Annotation(marker.title, coordinate: marker.coordinate, anchor: .center) {
            Button {
              path.append(.task(id: marker.id, order: markers.map(\.id)))
            } label: {
              Circle()
                .fill(Color(red: 0x17 / 255, green: 0x6B / 255, blue: 0x52 / 255))
                .stroke(.white, lineWidth: 2)
                .frame(width: 18, height: 18)
                .padding(9)  // A larger tap target than the dot.
                .contentShape(Circle())
            }
            .accessibilityLabel(Text(verbatim: "Task \(marker.id): \(marker.title)"))
          }
          .annotationTitles(.hidden)
        }
        UserAnnotation()
      }
      .onMapCameraChange(frequency: .onEnd) { context in
        let first = region == nil
        region = context.region
        if first {
          status = "Pan or zoom, then tap Search this area."
        } else {
          request?.cancel()
          requestToken = UUID()
          request = nil
          status = "Tap Search this area to load tasks here."
        }
      }
    }
    .navigationTitle("Nearby tasks")
    .navigationBarTitleDisplayMode(.inline)
    .onAppear {
      if client == nil { client = session.newClient() }
    }
    .onDisappear { locator.stop() }
    .onChange(of: session.view.generation) {
      request?.cancel()
      requestToken = UUID()
      request = nil
      client = session.newClient()
      markers = []
      status = "Session changed. Tap Search this area."
    }
    .onChange(of: changes.version) {
      // A resolved or skipped task changes the markers: search the same area again.
      searchArea()
    }
  }

  private func searchArea() {
    guard let region, let client = client?.client else { return }
    // About zoom 12 on a phone; wider areas would return too many tasks.
    if region.span.longitudeDelta > 0.15 {
      status = "Zoom in closer to search nearby tasks."
      return
    }
    let bounds: Bounds
    do {
      bounds = try Bounds(
        west: region.center.longitude - region.span.longitudeDelta / 2,
        south: region.center.latitude - region.span.latitudeDelta / 2,
        east: region.center.longitude + region.span.longitudeDelta / 2,
        north: region.center.latitude + region.span.latitudeDelta / 2)
    } catch {
      status = "Zoom in or move away from the date line, then search again."
      return
    }
    request?.cancel()
    markers = []
    status = "Loading tasks…"
    let token = UUID()
    requestToken = token
    request = Task {
      do {
        // Choice tasks only, without ones found stale (cct=3&excludeStale=true). Markers carry no
        // payload, so the task screen re-checks and says "Not available on mobile" otherwise.
        let tasks = try await client.findTaskMarkers(
          filter: TaskFilter(bounds: bounds, choiceOnly: true), limit: 100)
        guard requestToken == token, !Task.isCancelled else { return }
        markers = tasks.compactMap { task in
          taskPoint(task.point).map { Marker(id: task.id.value, title: task.title, coordinate: $0) }
        }
        let extra = tasks.count == 100 ? " Showing up to 100 tasks; more may exist. Zoom in." : ""
        let missing = markers.count < tasks.count ? " Some tasks have no usable point." : ""
        status =
          tasks.isEmpty
          ? "No multiple-choice tasks here. Try another area."
          : "\(markers.count) tasks shown. Tap a dot.\(extra)\(missing)"
      } catch {
        guard requestToken == token, !(error is CancellationError) else { return }
        let kind = (error as? MapRouletteError).map { " (\($0.kind.rawValue))" } ?? ""
        status = "Could not load tasks\(kind). Tap Search this area to retry."
      }
      if requestToken == token { request = nil }
    }
  }

  private func locate() {
    status = "Finding your location…"
    locator.locate { result in
      switch result {
      case .found(let coordinate):
        withAnimation {
          position = .region(
            MKCoordinateRegion(
              center: coordinate, span: MKCoordinateSpan(latitudeDelta: 0.03, longitudeDelta: 0.03)))
        }
        status = "Location found. Tap Search this area."
      case .denied: status = "Location permission declined. Pan and zoom to choose an area."
      case .unavailable: status = "Location is unavailable. Pan and zoom to choose an area."
      case .timedOut: status = "No location received. Try again or pan to your area."
      }
    }
  }
}

/// One-shot location on request, with a 20 s timeout.
@MainActor final class Locator: NSObject, CLLocationManagerDelegate {
  enum Result { case found(CLLocationCoordinate2D), denied, unavailable, timedOut }

  private let manager = CLLocationManager()
  private var completion: ((Result) -> Void)?
  private var timeout: Task<Void, Never>?

  override init() {
    super.init()
    manager.delegate = self
    manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
  }

  func locate(_ completion: @escaping (Result) -> Void) {
    stop()
    self.completion = completion
    timeout = Task { [weak self] in
      try? await Task.sleep(for: .seconds(20))
      if !Task.isCancelled { self?.finish(.timedOut) }
    }
    switch manager.authorizationStatus {
    case .notDetermined: manager.requestWhenInUseAuthorization()
    case .denied, .restricted: finish(.denied)
    default: manager.requestLocation()
    }
  }

  func stop() {
    timeout?.cancel()
    timeout = nil
    completion = nil
  }

  private func finish(_ result: Result) {
    let completion = self.completion
    stop()
    completion?(result)
  }

  nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    let status = manager.authorizationStatus
    Task { @MainActor in
      guard completion != nil else { return }
      switch status {
      case .notDetermined: break
      case .denied, .restricted: finish(.denied)
      default: self.manager.requestLocation()
      }
    }
  }

  nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let coordinate = locations.last?.coordinate else { return }
    Task { @MainActor in finish(.found(coordinate)) }
  }

  nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
    let denied = (error as? CLError)?.code == .denied
    Task { @MainActor in finish(denied ? .denied : .unavailable) }
  }
}
