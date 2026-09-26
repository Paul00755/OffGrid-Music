# Project Directives & Operating Memory

## Core Engineering Directives
- **Zero-Regression Mandate**: Always ensure new updates DO NOT crash, make existing functions unable to work, or reduce the efficiency and performance of already existing functions.
- **State Efficiency**: Ensure high-frequency UI updates (e.g. playback tracking, slider adjustments) are debounced and decoupled from heavy disk or database operations.
- **Real-Time Accuracy**: Keep playback timestamps and progress meters in Now Playing themes and launcher widgets updated in real-time without requiring pause/resume cycles.
