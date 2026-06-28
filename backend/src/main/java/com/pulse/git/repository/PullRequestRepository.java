package com.pulse.git.repository;

import com.pulse.git.model.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, String> {
    List<PullRequest> findByGitRepoIdOrderByCreatedAtDesc(String gitRepoId);
    List<PullRequest> findByVersionIdOrderByCreatedAtDesc(String versionId);
    List<PullRequest> findByStatusOrderByCreatedAtDesc(String status);
}
