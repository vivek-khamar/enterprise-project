package com.enterprise.demo.repository;

import com.enterprise.demo.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    java.util.Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    // COALESCE forces a text type for the parameter so PostgreSQL can resolve lower().
    // When null is passed, COALESCE(null,'')='' is true and the LIKE branch is skipped.
    @Query("SELECT u FROM User u WHERE " +
           "(COALESCE(:username, '') = '' OR LOWER(u.username) LIKE LOWER(CONCAT('%', COALESCE(:username, ''), '%'))) AND " +
           "(COALESCE(:email, '') = '' OR LOWER(u.email) LIKE LOWER(CONCAT('%', COALESCE(:email, ''), '%')))")
    Page<User> searchByFilters(@Param("username") String username,
                               @Param("email") String email,
                               Pageable pageable);
}
