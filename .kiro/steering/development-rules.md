# NavSync Development Rules

## General

- Work incrementally.
- Preserve existing working functionality.
- Do not rewrite the application unnecessarily.
- Do not delete existing functionality without approval.
- Do not make unrelated changes.
- Keep each change focused on the requested feature.

## Android

- Kotlin
- Jetpack Compose
- Material 3
- MVVM

Prefer modern Android APIs and lifecycle-aware patterns.

## UI

The application should look like a professional
navigation application.

Design principles:

- Navigation first
- Map is the primary visual element
- Dark navy / blue visual language
- Clean typography
- Minimal visual clutter
- Subtle technical telemetry
- Professional automotive/navigation aesthetic

Avoid:

- excessive cards
- excessive gradients
- glassmorphism
- neon-heavy UI
- generic AI dashboard layouts
- unnecessary animations
- emoji-based vehicle markers
- large "AI" labels dominating the screen

## Code

- Prefer small focused composables.
- Keep business logic out of composables.
- Keep NavigationState immutable.
- Use clear naming.
- Avoid magic numbers where practical.
- Do not duplicate existing models.

## Dependencies

Before adding a dependency:

1. Explain why it is required.
2. Check whether the existing project can solve the
   requirement without it.
3. Do not modify dependency versions unnecessarily.

## Gradle

Do not change:

- Gradle version
- Android Gradle Plugin version
- Kotlin version
- Compose BOM

unless explicitly requested or required to fix a verified
compatibility problem.

## Testing

After significant implementation:

- Build the project.
- Run relevant tests.
- Verify the application in the Android emulator when
  appropriate.

## Git

Do not commit changes automatically unless explicitly asked.

Do not modify the main branch as part of feature development.

Keep changes suitable for review through a pull request.