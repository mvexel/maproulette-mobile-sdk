// swift-tools-version: 6.0
import PackageDescription

// MapRoulette mobile SDK. Copyright 2026 Martijn van Exel. Licensed under the Apache License,
// Version 2.0: see LICENSE and NOTICE at the repository root.
//
// The manifest lives at the repository root so SwiftPM can consume the package by URL; the
// sources stay under swift/. The Example executable is deliberately not an exported product:
// run it with `swift run Example`.
let package = Package(
  name: "MapRoulette",
  platforms: [.iOS(.v15), .macOS(.v12)],
  products: [
    .library(name: "MapRoulette", targets: ["MapRoulette"])
  ],
  targets: [
    .target(name: "MapRoulette", path: "swift/Sources/MapRoulette"),
    .executableTarget(
      name: "Example", dependencies: ["MapRoulette"], path: "swift/Sources/Example"),
    .testTarget(
      name: "MapRouletteTests", dependencies: ["MapRoulette"], path: "swift/Tests/MapRouletteTests"),
  ]
)
