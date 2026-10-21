# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Spring Boot 4.1 / Java 17 (Gradle toolchain) / Thymeleaf / Spring Data JPA / H2. Use the Gradle wrapper (`gradlew.bat` on Windows).

- Run the app: `./gradlew bootRun` (dev DB is the file `./data/battery`, H2 `AUTO_SERVER=TRUE`)
- All tests: `./gradlew test`
- Single test class: `./gradlew test --tests com.example.demo.simulation.PlannerTest`
- Single test method: `./gradlew test --tests "com.example.demo.simulation.PlannerTest.<method>"`

Tests use an in-memory H2 DB (`src/test/resources/application.properties`, `create-drop`). No linter is configured.

## Architecture

A battery-cell order planning tool: given the quantity (in K cells) ordered from each overseas factory (China, Japan, Vietnam, Indonesia), it simulates ship routes back to Korea and lets the user confirm one plan as an order. UI text and comments are in Korean.

- `simulation/` — pure logic, no Spring/JPA. `Planner.plan(Master, orders)` returns three `Plan`s: `COST` (min shipping cost), `FAST` (min total days), `NEAR` (nearest-first heuristic). COST/FAST come from brute force: enumerate partitions of countries across ships → per-ship trip structures → `schedule()` for departure days. Key rules are in the `Planner` Javadoc (all factories start on day 0, a ship takes the whole stock on arrival, number of visits per country = ceil(order / stockLimit), ships depart as late as possible since waiting in Korea is free). `toPlan` replays daily production and throws `IllegalStateException` if a pickup doesn't equal the stock at that time, so it works as a self-check of the schedule.
- `Master` is an immutable snapshot of factory parameters, route days, ship count and ship daily cost, carrying a `version`.
- `service/BatteryService` — the only service. Loads `Master` from the DB, validates input, runs the planner, and manages orders.
  - `confirm()` never trusts client-sent plans: it re-runs `simulate()` on the server, rejects if the master `version` changed since the user simulated, and stores the whole plan plus the master snapshot as JSON (`StoredPlan`) on `Order`.
  - `changeStatus()` uses a version-based optimistic check (`expectedVersion`) plus JPA optimistic locking. Both raise `BusinessException(…, true)` to signal a conflict. Status transitions are defined by `OrderStatus.canChangeTo`. Moving to `CANCELED` deletes the order row, and only the history row remains.
  - `updateMaster()` validates all form fields first, saves nothing if any error is found, and bumps `masterVersion`. Money is stored in won; the UI works in 억 원 (`WON_PER_EOK`).
- `controller/AppController` — a single controller serving Thymeleaf pages (`/`, `/orders`, `/orders/new`, `/orders/{id}`, `/master`, `/login`) and JSON APIs (`/api/master`, `/api/simulate`, `/api/orders`) that the JS in `static/js/` calls.
- `config/` — `LoginInterceptor` guards everything except login: unauthenticated `/api/**` gets 401, everything else redirects to `/login`. `DataInitializer` seeds default master data on startup via `BatteryService.initDefaults()` (only when the settings table is empty).
- Front end is plain JS + Thymeleaf fragments (`templates/fragments.html`), with no build step. `planview.js` / `anim.js` render the route plans and the ship animation over `static/images/map.png`.

Entity PKs for factories and routes are the `Country` enum (`Country.KOREA` is the hub and not a factory; use `Country.factories()` to iterate factories).
