package com.enterprise.demo.service;

import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.event.UserEvent;
import com.enterprise.demo.event.UserEventPayload;
import com.enterprise.demo.event.UserEventPublisher;
import com.enterprise.demo.event.UserEventType;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserEventPublisher eventPublisher;

    public Page<UserDto> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::convertToDto);
    }

    public UserDto getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return convertToDto(user);
    }

    @Transactional
    public UserDto createUser(UserDto userDto) {
        User savedUser = userRepository.save(convertToEntity(userDto));
        publishAfterCommit(UserEvent.of(
                UserEventType.USER_CREATED,
                new UserEventPayload(savedUser.getId(), savedUser.getUsername(), savedUser.getEmail())));
        return convertToDto(savedUser);
    }

    @Transactional
    public UserDto updateUser(Long id, UserDto userDto) {
        User existingUser = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        existingUser.setUsername(userDto.getUsername());
        existingUser.setEmail(userDto.getEmail());

        User updatedUser = userRepository.save(existingUser);
        publishAfterCommit(UserEvent.of(
                UserEventType.USER_UPDATED,
                new UserEventPayload(updatedUser.getId(), updatedUser.getUsername(), updatedUser.getEmail())));
        return convertToDto(updatedUser);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        userRepository.delete(user);
        publishAfterCommit(UserEvent.of(
                UserEventType.USER_DELETED,
                new UserEventPayload(user.getId(), user.getUsername(), user.getEmail())));
    }

    // Defers Kafka publish until after the DB transaction commits.
    // Falls back to immediate publish in non-transactional contexts (e.g. unit tests).
    private void publishAfterCommit(UserEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publish(event);
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
        return new User(userDto.getUsername(), userDto.getEmail());
    }
}
