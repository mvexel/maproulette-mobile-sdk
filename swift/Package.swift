// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "MapRoulette",
  platforms: [.iOS(.v15), .macOS(.v12)],
  products: [
    .library(name: "MapRoulette", targets: ["MapRoulette"]),
    .executable(name: "maproulette-example", targets: ["Example"]),
  ],
  targets: [
    .target(name: "MapRoulette"),
    .executableTarget(name: "Example", dependencies: ["MapRoulette"]),
    .testTarget(name: "MapRouletteTests", dependencies: ["MapRoulette"]),
  ]
)
