# Contributing to android_media_store

Thank you for your interest in contributing to the `android_media_store` Flutter plugin! This document outlines the guidelines for contributing. By participating in this project, you agree to abide by these guidelines.

## Code of Conduct

This project adheres to the [Contributor Covenant](https://www.contributor-covenant.org/version/2/1/CODE_OF_CONDUCT.md). Please read it before participating.

## How to Contribute

We welcome contributions in the following areas:

*   **Bug fixes:** Fixing identified issues.
*   **Feature enhancements:** Adding new features or improving existing ones.
*   **Documentation improvements:** Clarifying code and usage.
*   **Code reviews:** Providing feedback on pull requests.
*   **Testing:** Helping to ensure the plugin is robust.

### 1.  Getting Started

*   **Fork the repository:** Create a fork of the main repository on GitHub.
*   **Clone the repository:** Clone your forked repository to your local machine.
*   **Set up your development environment:** Ensure you have Flutter, Android Studio (or VS Code with Flutter extensions), and the Android SDK set up correctly.
*   **Install dependencies:** Run `flutter pub get` in the `example/` directory of the project.
*   **Create a branch:** Create a new branch for your changes.  Use a descriptive branch name (e.g., `feature/add_new_method`, `fix/bug_description`).

### 2.  Making Changes

*   **Write clean, readable code:** Follow the Flutter style guide and Dart language conventions.
*   **Comment your code:** Write clear and concise comments to explain the purpose of your code.
*   **Add tests:** Write unit tests and/or integration tests to ensure your changes work as expected and don't break existing functionality.
    *   For Kotlin code, place tests in `android/src/test/kotlin/`. Use Robolectric for Android framework mocking.
    *   For Dart code, place tests in the `test/` directory.
    *   For integration tests, place them in the `example/integration_test/` directory, testing on a real Android device.
*   **Update documentation:** If your changes introduce new features or modify existing functionality, update the `README.md` and/or the code comments to reflect the changes.
*   **Commit your changes:** Use meaningful commit messages. Follow a consistent format:
    *   `feat: Add new feature`
    *   `fix: Resolve a bug`
    *   `docs: Improve documentation`
    *   `test: Add unit tests`
    *   `refactor: Improve code readability`
*   **Squash commits:** For complex changes, consider squashing your commits before submitting a pull request.

### 3. Submitting a Pull Request

*   **Push your branch:** Push your changes to your forked repository.
*   **Create a pull request:** From your forked repository on GitHub, create a pull request to the `main` branch of the original repository.
*   **Describe your changes:** Provide a clear and concise description of your changes in the pull request. Include:
    *   The purpose of your changes.
    *   Any relevant issues or pull requests.
    *   How you tested your changes.
*   **Get reviewed:**  Your pull request will be reviewed by project maintainers.  Be prepared to address feedback and make revisions.
*   **Merge:** Once your pull request is approved, it will be merged into the main branch.

### 4.  Testing

*   **Run existing tests:** Before submitting a pull request, run all existing tests to ensure your changes haven't broken anything.
*   **Create new tests:** Add tests for new functionality or bug fixes.
*   **Test on multiple devices:** If possible, test your changes on different Android devices and API levels.

### 5. Code Style

*   **Follow the Flutter style guide:** [https://docs.flutter.dev/style-guide/effective-dart](https://docs.flutter.dev/style-guide/effective-dart)
*   **Use an IDE with code formatting:** Android Studio and VS Code with the Flutter extensions will automatically format your code according to the style guide.

### 6. Communication

*   **Use GitHub Issues:**  Use GitHub Issues to report bugs, suggest features, or ask questions.
*   **Be respectful:**  Treat other contributors with respect and professionalism.
*   **Be patient:**  The project maintainers are volunteers and may not be able to respond to your contributions immediately.

## Project Structure

*   `android/` : Android native code (Kotlin)
*   `example/` : Flutter example app.
*   `lib/` : Dart code for the plugin.
*   `test/` : Dart unit tests
*   `example/integration_test/`: Integration Tests
*   `android/src/test/kotlin`: Kotlin unit tests

## Thank you!

Your contributions are valuable and help make this plugin better for everyone. Thank you for helping improve `android_media_store`!