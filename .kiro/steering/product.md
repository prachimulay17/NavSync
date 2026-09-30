# NavSync Product Context

## Product

NavSync is an AI-ML based intelligent dead-reckoning
navigation system designed to maintain reliable vehicle
positioning when GNSS becomes unavailable.

The system combines:
- GNSS
- IMU
- AI-based motion estimation
- dead reckoning
- ESKF/IEKF-based state estimation
- confidence estimation
- map matching
- GNSS recovery validation

## Current Prototype Goal

The current Android application is a prototype/demo
interface for the NavSync system.

The immediate goal is NOT to build a complete production
navigation application.

The prototype should demonstrate:

1. Navigation interface
2. Simulated GNSS availability
3. Simulated GNSS outage
4. Continued navigation using simulated NavSync estimates
5. Navigation confidence/state
6. Performance/simulation visualization
7. Future integration with ML-generated navigation data

## Product Philosophy

Navigation first, intelligence second.

The application should visually resemble a professional
navigation application rather than an AI dashboard.

The map/navigation experience should be the primary focus.

Technical information should support the navigation
experience rather than dominate it.