---
name: spring-boot-helper
description: Scaffolding and best practices for Spring Boot applications (Controllers, Services, Repositories, Tests).
---

# Spring Boot Helper Skill

Use this skill to generate standardized Spring Boot components or configure application properties.

## Capabilities

1.  **Component Scaffolding**: Generate `@RestController`, `@Service`, `@Repository` classes with proper annotations.
2.  **Data Access**: Setup Spring Data JPA/JDBC repositories and Entity classes.
3.  **Configuration**: Manage `application.yml` or `application.properties`.
4.  **Testing**: Generate `@WebMvcTest` or `@SpringBootTest` scenarios.

## Code Standards

### 1. Controller Pattern
```java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.findById(id));
    }
}
```

### 2. Service Pattern
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserDto findById(Long id) {
        return userRepository.findById(id)
                .map(UserMapper::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }
}
```

### 3. Repository Pattern
```java
@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
}
```

## Configuration Rules

1.  **Prefer YAML**: Use `application.yml` for hierarchical configuration unless `application.properties` is already heavily used.
2.  **Externalize Config**: Don't hardcode sensitive data (passwords); use `${ENV_VAR}` placeholders.
3.  **Lombok**: Assume Lombok is available (`@Data`, `@RequiredArgsConstructor`, `@Slf4j`) unless told otherwise.
4.  **Constructor Injection**: Always use constructor injection (via `@RequiredArgsConstructor`) instead of `@Autowired` on fields.

## Action Steps

1.  **Identify Requirement**: Determine which layer (Web, Service, Data) needs implementation.
2.  **Check Dependencies**: Ensure `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, etc., are in `build.gradle.kts`. If not, invoke `gradle-expert` skill or suggest adding them.
3.  **Generate Code**: Create files in the correct package structure.
