# Contributing to S Engine 2D

Thank you for your interest in contributing to S Engine 2D! This document provides guidelines and information for contributors.

## 🎯 How to Contribute

### Reporting Bugs
- Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.md)
- Include device information (Android version, device model)
- Provide steps to reproduce the issue
- Attach screenshots or screen recordings if possible
- Include relevant logs from the debug console

### Suggesting Features
- Use the [Feature Request template](.github/ISSUE_TEMPLATE/feature_request.md)
- Explain the use case and why it's important
- Describe how you envision the feature working
- Consider the impact on mobile performance

### Submitting Pull Requests
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Write or update tests as needed
5. Ensure all tests pass (`./gradlew test`)
6. Commit your changes (`git commit -m 'Add amazing feature'`)
7. Push to the branch (`git push origin feature/amazing-feature`)
8. Open a Pull Request

## 🛠 Development Setup

### Prerequisites
- Android Studio Arctic Fox or later
- JDK 17
- Android SDK (API 26+)
- Kotlin 1.9+

### Building from Source
```bash
# Clone the repository
git clone https://github.com/surafel5509-del/2D-Game-Engine-Mobile.git
cd 2D-Game-Engine-Mobile

# Build the project
./gradlew assembleDebug

# Run tests
./gradlew test
```

### Project Structure
```
app/src/main/java/com/sengine/
├── engine/
│   ├── core/           # Core engine components
│   ├── physics/        # Physics system
│   ├── render/         # Rendering system
│   ├── animation/      # Animation system
│   ├── script/         # Scripting system
│   ├── ui/             # In-game UI
│   ├── resource/       # Resource management
│   ├── save/           # Save/load system
│   ├── debug/          # Debug tools
│   ├── math/           # Math utilities
│   ├── Engine.kt       # Main engine class
│   ├── AudioSystem.kt  # Audio system
│   └── Input.kt        # Input system
├── ui/                 # Editor UI
└── project/            # Project management
```

## 📝 Coding Standards

### Kotlin Style Guide
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add documentation comments for public APIs
- Keep functions focused and concise
- Use `val` instead of `var` when possible

### Code Organization
- One class per file
- Group related functionality together
- Use packages to organize code
- Keep imports minimal and organized

### Naming Conventions
```kotlin
// Classes and interfaces: PascalCase
class PhysicsWorld { }

// Functions and properties: camelCase
fun calculateVelocity() { }
val maxSpeed = 10f

// Constants: UPPER_SNAKE_CASE
const val MAX_FPS = 60

// Private members: _prefix (optional)
private var _internalState = 0
```

## 🧪 Testing

### Writing Tests
- Write unit tests for all new features
- Test edge cases and error conditions
- Use descriptive test names
- Keep tests independent and isolated

### Running Tests
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.sengine.EngineSimulationTest"

# Run tests with detailed output
./gradlew test --info
```

### Test Coverage
Aim for at least 80% code coverage for new features. Use the following command to generate a coverage report:
```bash
./gradlew test jacocoTestReport
```

## 📚 Documentation

### Code Documentation
- Document public APIs with KDoc comments
- Include examples for complex features
- Explain the "why" not just the "what"

```kotlin
/**
 * Calculates the trajectory of a projectile under gravity.
 *
 * @param velocity Initial velocity in units/second
 * @param angle Launch angle in degrees
 * @param gravity Gravity acceleration (default: -9.81)
 * @return Pair of (x, y) positions at time t
 *
 * @example
 * ```kotlin
 * val (x, y) = calculateTrajectory(10f, 45f)
 * ```
 */
fun calculateTrajectory(velocity: Float, angle: Float, gravity: Float = -9.81f): Pair<Float, Float>
```

### Updating Documentation
- Update README.md for user-facing changes
- Update inline documentation for API changes
- Add examples for new features
- Keep the documentation in sync with code

## 🔄 Pull Request Process

### Before Submitting
1. Ensure your code compiles without errors
2. Run all tests and ensure they pass
3. Check for lint warnings
4. Update documentation if needed
5. Add yourself to the contributors list (optional)

### PR Review Process
1. Automated checks will run (CI, linting, tests)
2. At least one maintainer will review your code
3. Address any requested changes
4. Once approved, a maintainer will merge your PR

### Commit Messages
Use conventional commit format:
```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

Examples:
```
feat(physics): add rope joint constraint
fix(render): fix texture caching memory leak
docs(api): add examples for raycasting
```

## 🎨 Asset Guidelines

If you're contributing assets (templates, examples):
- Keep file sizes small (mobile-first)
- Use free/open-source assets only
- Provide attribution where required
- Optimize images and audio files

## 💬 Communication

- Use GitHub Issues for bugs and feature requests
- Use GitHub Discussions for questions and ideas
- Be respectful and constructive in all communications
- Help others when you can

## 🏆 Recognition

Contributors will be:
- Listed in the README.md contributors section
- Mentioned in release notes
- Tagged in relevant discussions

## 📄 License

By contributing to S Engine 2D, you agree that your contributions will be licensed under the project's license.

---

Thank you for contributing to S Engine 2D! 🎮
