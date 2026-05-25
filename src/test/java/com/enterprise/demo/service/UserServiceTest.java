package com.enterprise.demo.service;

import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void getAllUsers_returnsEmptyList() {
        when(userRepository.findAll()).thenReturn(List.of());

        assertThat(userService.getAllUsers()).isEmpty();
    }

    @Test
    void getAllUsers_returnsMappedDtos() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findAll()).thenReturn(List.of(user));

        List<UserDto> result = userService.getAllUsers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getUsername()).isEqualTo("jsmith");
        assertThat(result.get(0).getEmail()).isEqualTo("j@example.com");
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
    void createUser_savesAndReturnsDto() {
        UserDto dto = new UserDto(null, "jsmith", "j@example.com");
        User savedUser = new User("jsmith", "j@example.com");
        savedUser.setId(1L);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserDto result = userService.createUser(dto);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("jsmith");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void updateUser_updatesAndReturnsDto() {
        User existing = new User("old", "old@example.com");
        existing.setId(1L);
        UserDto updateDto = new UserDto(null, "new", "new@example.com");
        User updated = new User("new", "new@example.com");
        updated.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(updated);

        UserDto result = userService.updateUser(1L, updateDto);

        assertThat(result.getUsername()).isEqualTo("new");
        assertThat(result.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateUser_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(99L, new UserDto(null, "x", "x@x.com")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void deleteUser_deletesUser() {
        User user = new User("jsmith", "j@example.com");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUser(1L);

        verify(userRepository).delete(user);
    }

    @Test
    void deleteUser_throwsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }
}
