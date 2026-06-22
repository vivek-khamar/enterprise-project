package com.enterprise.demo.service;

import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.event.UserEvent;
import com.enterprise.demo.event.UserEventPublisher;
import com.enterprise.demo.event.UserEventType;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import com.enterprise.demo.service.AuditService;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private com.enterprise.demo.repository.RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserEventPublisher eventPublisher;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService userService;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Test
    void getAllUsers_returnsEmptyPage() {
        when(userRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        assertThat(userService.getAllUsers(PAGE).getContent()).isEmpty();
    }

    @Test
    void getAllUsers_returnsMappedDtos() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));

        Page<UserDto> result = userService.getAllUsers(PAGE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
        assertThat(result.getContent().get(0).getUsername()).isEqualTo("jsmith");
        assertThat(result.getContent().get(0).getEmail()).isEqualTo("j@example.com");
    }

    @Test
    void getUserById_returnsDto() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserDto result = userService.getUserById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("jsmith");
        assertThat(result.getEmail()).isEqualTo("j@example.com");
    }

    @Test
    void getUserById_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void createUser_savesAndPublishesCreatedEvent() {
        UserDto dto = new UserDto(null, "jsmith", "j@example.com");
        User savedUser = new User("jsmith", "j@example.com");
        savedUser.setId(1L);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserDto result = userService.createUser(dto);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("jsmith");

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_CREATED);
        assertThat(captor.getValue().payload().userId()).isEqualTo(1L);
        assertThat(captor.getValue().version()).isEqualTo("1.0");
    }

    @Test
    void updateUser_updatesAndPublishesUpdatedEvent() {
        User existing = new User("old", "old@example.com");
        existing.setId(1L);
        UserDto updateDto = new UserDto(null, "new", "new@example.com");
        User updated = new User("new", "new@example.com");
        updated.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(updated);

        UserDto result = userService.updateUser(1L, updateDto);

        assertThat(result.getUsername()).isEqualTo("new");

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_UPDATED);
        assertThat(captor.getValue().payload().username()).isEqualTo("new");
    }

    @Test
    void updateUser_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(99L, new UserDto(null, "x", "x@x.com")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void deleteUser_deletesAndPublishesDeletedEvent() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUser(1L);

        verify(userRepository).delete(user);

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_DELETED);
        assertThat(captor.getValue().payload().userId()).isEqualTo(1L);
    }

    @Test
    void deleteUser_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void createUser_defersPublishToAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            User savedUser = new User("jsmith", "j@example.com");
            savedUser.setId(1L);
            when(userRepository.save(any(User.class))).thenReturn(savedUser);

            userService.createUser(new UserDto(null, "jsmith", "j@example.com"));

            verify(eventPublisher, never()).publish(any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
            verify(eventPublisher).publish(captor.capture());
            assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_CREATED);
            assertThat(captor.getValue().payload().userId()).isEqualTo(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updateUser_defersPublishToAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            User existing = new User("old", "old@example.com");
            existing.setId(1L);
            User updated = new User("new", "new@example.com");
            updated.setId(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(userRepository.save(existing)).thenReturn(updated);

            userService.updateUser(1L, new UserDto(null, "new", "new@example.com"));

            verify(eventPublisher, never()).publish(any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
            verify(eventPublisher).publish(captor.capture());
            assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_UPDATED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ── Password branch coverage ──────────────────────────────────────────────

    @Test
    void updateUser_hashesPasswordWhenProvided() {
        User existing = new User("jsmith", "j@example.com");
        existing.setId(1L);
        User updated = new User("jsmith", "j@example.com");
        updated.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(updated);

        UserDto dto = new UserDto(null, "jsmith", "j@example.com");
        dto.setPassword("NewPass123!");   // password present → branch exercised
        userService.updateUser(1L, dto);

        verify(passwordEncoder).encode("NewPass123!");
    }

    @Test
    void createUser_hashesPasswordWhenProvided() {
        UserDto dto = new UserDto(null, "alice", "alice@example.com");
        dto.setPassword("Pass1234!");    // password present → branch exercised

        User saved = new User("alice", "alice@example.com");
        saved.setId(2L);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(passwordEncoder.encode("Pass1234!")).thenReturn("hashed");

        userService.createUser(dto);

        verify(passwordEncoder).encode("Pass1234!");
    }

    @Test
    void deleteUser_defersPublishToAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            User user = new User("jsmith", "j@example.com");
            user.setId(1L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            userService.deleteUser(1L);

            verify(eventPublisher, never()).publish(any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
            verify(eventPublisher).publish(captor.capture());
            assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_DELETED);
            assertThat(captor.getValue().payload().userId()).isEqualTo(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
