package com.enterprise.demo.service;

import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.event.UserEvent;
import com.enterprise.demo.event.UserEventPayload;
import com.enterprise.demo.event.UserEventPublisher;
import com.enterprise.demo.event.UserEventType;
import com.enterprise.demo.exception.EventPublishException;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.RefreshTokenRepository;
import com.enterprise.demo.repository.UserRepository;
import com.enterprise.demo.service.AuditService.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Cacheable("users-list")
    public Page<UserDto> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::convertToDto);
    }

    @Cacheable("users-by-id")
    public UserDto getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return convertToDto(user);
    }

    @Transactional
    @CacheEvict(value = {"users-list", "users-search"}, allEntries = true)
    public UserDto createUser(UserDto userDto) {
        User savedUser = userRepository.save(convertToEntity(userDto));
        auditService.logAdminAction(Event.USER_CREATED, savedUser.getUsername(),
                "userId=" + savedUser.getId());
        publishAfterCommit(UserEvent.of(
                UserEventType.USER_CREATED,
                new UserEventPayload(savedUser.getId(), savedUser.getUsername())));
        return convertToDto(savedUser);
    }

    @Transactional
    @CacheEvict(value = {"users-list", "users-by-id", "users-search"}, allEntries = true)
    public UserDto updateUser(Long id, UserDto userDto) {
        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        existingUser.setUsername(userDto.getUsername());
        existingUser.setEmail(userDto.getEmail());
        if (userDto.getPassword() != null && !userDto.getPassword().isBlank()) {
            existingUser.setPassword(passwordEncoder.encode(userDto.getPassword()));
        }

        User updatedUser = userRepository.save(existingUser);
        auditService.logAdminAction(Event.USER_UPDATED, String.valueOf(updatedUser.getId()),
                "newUsername=" + updatedUser.getUsername());
        publishAfterCommit(UserEvent.of(
                UserEventType.USER_UPDATED,
                new UserEventPayload(updatedUser.getId(), updatedUser.getUsername())));
        return convertToDto(updatedUser);
    }

    /**
     * Deletes the user account and all associated sessions (refresh tokens).
     *
     * GDPR Article 17 (right to erasure): refresh tokens are personal data because they
     * are linked to an identifiable natural person.  Deleting them in the same transaction
     * ensures no orphaned session data remains after the account is removed.
     */
    @Transactional
    @CacheEvict(value = {"users-list", "users-by-id", "users-search"}, allEntries = true)
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        // Cascade-delete all active and revoked sessions before removing the account.
        refreshTokenRepository.deleteByUser(user);

        userRepository.delete(user);
        auditService.logAdminAction(Event.USER_DELETED, String.valueOf(id),
                "username=" + user.getUsername());
        publishAfterCommit(UserEvent.of(
                UserEventType.USER_DELETED,
                new UserEventPayload(user.getId(), user.getUsername())));
    }

    @Cacheable("users-search")
    public Page<UserDto> searchUsers(String username, String email, Pageable pageable) {
        return userRepository.searchByFilters(username, email, pageable).map(this::convertToDto);
    }

    // Defers Kafka publish until after the DB transaction commits.
    // Falls back to immediate publish in non-transactional contexts (e.g. unit tests).
    // Failures after commit are logged and swallowed — the DB write already succeeded and
    // the HTTP response must not be poisoned by a Kafka outage.
    private void publishAfterCommit(UserEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        eventPublisher.publish(event);
                    } catch (EventPublishException ex) {
                        log.warn("Event publish failed after DB commit ({}); skipping — broker may be unavailable: {}",
                                event.eventType(), ex.getMessage());
                    }
                }
            });
        } else {
            eventPublisher.publish(event);
        }
    }

    private UserDto convertToDto(User user) {
        return new UserDto(user.getId(), user.getUsername(), user.getEmail());
    }

    private User convertToEntity(UserDto userDto) {
        User user = new User(userDto.getUsername(), userDto.getEmail());
        if (userDto.getPassword() != null && !userDto.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(userDto.getPassword()));
        }
        return user;
    }
}
