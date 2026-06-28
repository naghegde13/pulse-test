package com.pulse.auth.repository;

import com.pulse.auth.model.PulseUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<PulseUser, String> {
    Optional<PulseUser> findByEmail(String email);
}
