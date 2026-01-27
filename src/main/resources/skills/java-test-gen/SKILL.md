---
name: java-test-gen
description: A specialized skill for generating high-quality JUnit 5 unit tests with Mockito for Java applications.
---

# Java Test Generation Skill

Use this skill when the user asks to generate unit tests for a Java class.
This skill enforces standard testing patterns using JUnit 5 and Mockito.

## Prerequisites
Ensure the project has the following dependencies (check `pom.xml` or `build.gradle`):
- `org.junit.jupiter:junit-jupiter`
- `org.mockito:mockito-core` (or `mockito-junit-jupiter`)

## Test Generation Workflow

1.  **Analyze the Target Class**:
    - Identify public methods to test.
    - Identify dependencies that need to be mocked.
    - Understand the business logic and edge cases.

2.  **Plan Test Cases**:
    - Happy Path: Valid inputs, expected success.
    - Edge Cases: Null inputs, empty collections, boundary values.
    - Exception Handling: Verify expected exceptions are thrown.

3.  **Generate Test Code**:
    - **Class Name**: `<TargetClass>Test`
    - **Package**: Same package as target class, but in `src/test/java`.
    - **Setup**:
        - Use `@ExtendWith(MockitoExtension.class)` at the class level.
        - Use `@Mock` for dependencies.
        - Use `@InjectMocks` for the class under test.
    - **Test Methods**:
        - Use `@Test` annotation.
        - Use `@DisplayName("...")` to describe the test scenario.
        - Pattern: `// Arrange`, `// Act`, `// Assert`.
        - Use `Assertions.assertEquals`, `Assertions.assertThrows`, etc.
        - Use `Mockito.when(...).thenReturn(...)` for stubbing.
        - Use `Mockito.verify(...)` for interaction verification.

## Example Template

```java
package com.example.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("Should return user when user exists")
    void testFindUserById_Success() {
        // Arrange
        Long userId = 1L;
        User mockUser = new User(userId, "John");
        Mockito.when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));

        // Act
        User result = userService.findUserById(userId);

        // Assert
        Assertions.assertNotNull(result);
        Assertions.assertEquals("John", result.getName());
        Mockito.verify(userRepository).findById(userId);
    }
}
```

## Important Rules
- NEVER use `@Autowired` unless writing an integration test.
- ALWAYS prefer `MockitoExtension` over `MockitoAnnotations.openMocks(this)`.
- Keep tests independent and isolated.
