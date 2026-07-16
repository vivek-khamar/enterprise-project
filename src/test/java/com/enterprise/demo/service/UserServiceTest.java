package com.enterprise.demo.service;

import com.enterprise.demo.dto.AdminUserDto;
import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.event.UserEvent;
import com.enterprise.demo.event.UserEventPublisher;
import com.enterprise.demo.event.UserEventType;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.exception.SelfModificationException;
import com.enterprise.demo.repository.RefreshTokenRepository;
import com.enterprise.demo.repository.UserRepository;
import com.enterprise.demo.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserEventPublisher eventPublisher;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService userService;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    // ── getAllUsers ────────────────────────────────────────────────────────────

    @Test
    void getAllUsers_returnsEmptyPage() {
        when(userRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        assertThat(userService.getAllUsers(PAGE).getContent()).isEmpty();
    }

    @Test
    void getAllUsers_returnsMappedAdminDtos() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user)));

        Page<AdminUserDto> result = userService.getAllUsers(PAGE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(1L);
        assertThat(result.getContent().get(0).username()).isEqualTo("jsmith");
        assertThat(result.getContent().get(0).email()).isEqualTo("j@example.com");
        assertThat(result.getContent().get(0).role()).isEqualTo(Role.USER);
        assertThat(result.getContent().get(0).enabled()).isTrue();
    }

    // ── getUserById ───────────────────────────────────────────────────────────

    @Test
    void getUserById_returnsAdminDto() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AdminUserDto result = userService.getUserById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.username()).isEqualTo("jsmith");
        assertThat(result.email()).isEqualTo("j@example.com");
        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void getUserById_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── createUser ────────────────────────────────────────────────────────────

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
    void createUser_hashesPasswordWhenProvided() {
        UserDto dto = new UserDto(null, "alice", "alice@example.com");
        dto.setPassword("Pass1234!");

        User saved = new User("alice", "alice@example.com");
        saved.setId(2L);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(passwordEncoder.encode("Pass1234!")).thenReturn("hashed");

        userService.createUser(dto);

        verify(passwordEncoder).encode("Pass1234!");
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

    // ── updateUser ────────────────────────────────────────────────────────────

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
    void updateUser_hashesPasswordWhenProvided() {
        User existing = new User("jsmith", "j@example.com");
        existing.setId(1L);
        User updated = new User("jsmith", "j@example.com");
        updated.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(updated);

        UserDto dto = new UserDto(null, "jsmith", "j@example.com");
        dto.setPassword("NewPass123!");
        userService.updateUser(1L, dto);

        verify(passwordEncoder).encode("NewPass123!");
    }

    @Test
    void updateUser_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(99L, new UserDto(null, "x", "x@x.com")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
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

    // ── deleteUser ────────────────────────────────────────────────────────────

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

    // ── enableDisableUser ─────────────────────────────────────────────────────

    @Test
    void enableDisableUser_disablesUser() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        AdminUserDto result = userService.enableDisableUser(1L, false, "admin");

        assertThat(result.enabled()).isFalse();
        assertThat(result.username()).isEqualTo("jsmith");

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_UPDATED);
    }

    @Test
    void enableDisableUser_enablesUser() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        AdminUserDto result = userService.enableDisableUser(1L, true, "admin");

        assertThat(result.enabled()).isTrue();
    }

    @Test
    void enableDisableUser_throwsSelfModificationException_whenAdminModifiesOwnAccount() {
        User user = new User("admin", "admin@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.enableDisableUser(1L, false, "admin"))
                .isInstanceOf(SelfModificationException.class)
                .hasMessageContaining("Cannot modify your own account");
    }

    @Test
    void enableDisableUser_throwsNotFound_whenUserDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.enableDisableUser(99L, false, "admin"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── changeRole ────────────────────────────────────────────────────────────

    @Test
    void changeRole_promotesToAdmin() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        AdminUserDto result = userService.changeRole(1L, Role.ADMIN, "admin");

        assertThat(result.role()).isEqualTo(Role.ADMIN);

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(UserEventType.USER_UPDATED);
    }

    @Test
    void changeRole_demotesToUser() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        user.setRole(Role.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        AdminUserDto result = userService.changeRole(1L, Role.USER, "admin");

        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    void changeRole_throwsSelfModificationException_whenAdminModifiesOwnRole() {
        User user = new User("admin", "admin@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.changeRole(1L, Role.USER, "admin"))
                .isInstanceOf(SelfModificationException.class)
                .hasMessageContaining("Cannot modify your own account");
    }

    @Test
    void changeRole_throwsNotFound_whenUserDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changeRole(99L, Role.ADMIN, "admin"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }
}
