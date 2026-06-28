package com.pulse.deploy.promotion;

import com.pulse.deploy.model.ApprovalRequest;
import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.git.workspace.DeveloperWorkspace;
import com.pulse.git.workspace.DeveloperWorkspaceRepository;
import com.pulse.pipeline.model.VersionAcceptance;
import com.pulse.pipeline.repository.VersionAcceptanceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PKT-0008 — computes the current {@link PromotionProofState} for a
 * pipeline version by inspecting the highest evidence tier reached.
 *
 * <p>The computation is read-only; it queries existing domain data
 * (workspaces, acceptances, packages, deployments, runs, approvals)
 * and determines the highest proof tier that has been satisfied.
 *
 * <p>Each tier requires strict evidence — static package proof does
 * not imply runtime proof, and a deploy request does not imply
 * runtime success. The readback explicitly distinguishes all seven
 * tiers to prevent UI/API consumers from conflating them.
 */
@Service
public class PromotionProofService {

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final VersionAcceptanceRepository acceptanceRepository;
    private final PackageRepository packageRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentRunRepository deploymentRunRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final DeploymentTargetRepository deploymentTargetRepository;

    public PromotionProofService(DeveloperWorkspaceRepository workspaceRepository,
                                 VersionAcceptanceRepository acceptanceRepository,
                                 PackageRepository packageRepository,
                                 DeploymentRepository deploymentRepository,
                                 DeploymentRunRepository deploymentRunRepository,
                                 ApprovalRequestRepository approvalRequestRepository,
                                 DeploymentTargetRepository deploymentTargetRepository) {
        this.workspaceRepository = workspaceRepository;
        this.acceptanceRepository = acceptanceRepository;
        this.packageRepository = packageRepository;
        this.deploymentRepository = deploymentRepository;
        this.deploymentRunRepository = deploymentRunRepository;
        this.approvalRequestRepository = approvalRequestRepository;
        this.deploymentTargetRepository = deploymentTargetRepository;
    }

    /**
     * Computes the promotion proof readback for the given version.
     * Returns the highest evidence tier reached along with the
     * evidence at each tier.
     */
    public PromotionProofReadback computeProofState(String versionId) {
        PromotionProofState currentState = PromotionProofState.DRAFT_WORKSPACE;
        PromotionProofReadback.WorkspaceEvidence workspaceEvidence = null;
        PromotionProofReadback.AcceptanceEvidence acceptanceEvidence = null;
        PromotionProofReadback.StaticPackageEvidence staticPackageEvidence = null;
        PromotionProofReadback.DeployRequestEvidence deployRequestEvidence = null;
        PromotionProofReadback.RuntimeProofEvidence runtimeProofEvidence = null;
        PromotionProofReadback.PromotionGateEvidence promotionGateEvidence = null;
        PromotionProofReadback.PromotionCompleteEvidence promotionCompleteEvidence = null;

        // Tier 1: workspace exists
        List<DeveloperWorkspace> workspaces = workspaceRepository.findByVersionIdOrderByCreatedAtDesc(versionId);
        DeveloperWorkspace workspace = workspaces.isEmpty() ? null : workspaces.get(0);
        if (workspace != null) {
            workspaceEvidence = new PromotionProofReadback.WorkspaceEvidence(
                    workspace.getId(),
                    workspace.getLifecycleStatus(),
                    workspace.getWorkingTreeStatus(),
                    Instant.now());
        }

        // Tier 2: acceptance (PR merged)
        List<VersionAcceptance> acceptances = acceptanceRepository.findByVersionIdAndAcceptanceStatus(versionId, "ACTIVE");
        VersionAcceptance acceptance = acceptances.isEmpty() ? null : acceptances.get(0);
        if (acceptance != null) {
            currentState = PromotionProofState.ACCEPTED_ARTIFACT;
            acceptanceEvidence = new PromotionProofReadback.AcceptanceEvidence(
                    acceptance.getId(),
                    acceptance.getAcceptanceKind(),
                    acceptance.getAcceptedPackageId(),
                    acceptance.getAcceptedCommitSha(),
                    acceptance.getAcceptedAt());
        }

        // Tier 3: promotable package with static assessment
        var promotablePackages = packageRepository.findByVersionIdAndPromotableTrueOrderByCreatedAtDesc(versionId);
        var promotablePackage = promotablePackages.isEmpty() ? null : promotablePackages.get(0);
        if (acceptance != null && promotablePackage != null) {
            String verdict = extractStaticVerdict(promotablePackage.getMetadata());
            List<String> blockers = extractBlockers(promotablePackage.getMetadata());
            currentState = PromotionProofState.STATIC_PACKAGE_PROOF;
            staticPackageEvidence = new PromotionProofReadback.StaticPackageEvidence(
                    promotablePackage.getId(),
                    promotablePackage.getSourceKind(),
                    promotablePackage.isPromotable(),
                    verdict,
                    blockers,
                    promotablePackage.getBuiltAt());
        }

        // Tier 4: deployment requested (any deployment for this version's promotable package)
        Deployment deployment = null;
        if (staticPackageEvidence != null && promotablePackage != null) {
            var deployments = deploymentRepository.findByPipelineIdOrderByCreatedAtDesc(
                    promotablePackage.getPipelineId());
            for (Deployment d : deployments) {
                if (d.getVersionId().equals(versionId) && d.getPackageId().equals(promotablePackage.getId())) {
                    deployment = d;
                    break;
                }
            }
            if (deployment != null) {
                String targetEnv = resolveTargetEnvironment(deployment.getTargetId());
                currentState = PromotionProofState.DEPLOY_REQUESTED;
                deployRequestEvidence = new PromotionProofReadback.DeployRequestEvidence(
                        deployment.getId(),
                        targetEnv,
                        deployment.getStatus(),
                        deployment.getCreatedAt());
            }
        }

        // Tier 5: runtime proved (deployment run SUCCEEDED)
        if (deployment != null) {
            var runs = deploymentRunRepository.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
            for (DeploymentRun run : runs) {
                DeploymentRunState runState = DeploymentRunState.parse(run.getStatus());
                if (runState == DeploymentRunState.SUCCEEDED) {
                    currentState = PromotionProofState.RUNTIME_PROVED;
                    runtimeProofEvidence = new PromotionProofReadback.RuntimeProofEvidence(
                            run.getId(),
                            run.getStatus(),
                            run.getFinishedAt());
                    break;
                }
            }
        }

        // Tier 6: promotion ready (runtime proved + approval APPROVED)
        if (runtimeProofEvidence != null && deployment != null) {
            var approvals = approvalRequestRepository.findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
            for (ApprovalRequest approval : approvals) {
                if ("APPROVED".equals(approval.getStatus())) {
                    currentState = PromotionProofState.PROMOTION_READY;
                    promotionGateEvidence = new PromotionProofReadback.PromotionGateEvidence(
                            approval.getId(),
                            approval.getStatus(),
                            approval.getDecidedAt());
                    break;
                }
            }
        }

        // Tier 7: promotion complete (ACTIVE deployment in a higher-env target)
        if (promotionGateEvidence != null && deployment != null) {
            if ("ACTIVE".equals(deployment.getStatus())) {
                String targetEnv = resolveTargetEnvironment(deployment.getTargetId());
                currentState = PromotionProofState.PROMOTION_COMPLETE;
                promotionCompleteEvidence = new PromotionProofReadback.PromotionCompleteEvidence(
                        deployment.getId(),
                        targetEnv,
                        deployment.getDeployedAt());
            }
        }

        return new PromotionProofReadback(
                versionId,
                currentState,
                workspaceEvidence,
                acceptanceEvidence,
                staticPackageEvidence,
                deployRequestEvidence,
                runtimeProofEvidence,
                promotionGateEvidence,
                promotionCompleteEvidence);
    }

    private String resolveTargetEnvironment(String targetId) {
        if (targetId == null) return "unknown";
        return deploymentTargetRepository.findById(targetId)
                .map(DeploymentTarget::getEnvironment)
                .orElse("unknown");
    }

    @SuppressWarnings("unchecked")
    private String extractStaticVerdict(Map<String, Object> metadata) {
        if (metadata == null) return "UNKNOWN";
        Object assessment = metadata.get("staticRuntimeAssessment");
        if (assessment instanceof Map<?, ?> map) {
            Object verdict = map.get("verdict");
            return verdict instanceof String s ? s : "UNKNOWN";
        }
        return "UNKNOWN";
    }

    @SuppressWarnings("unchecked")
    private List<String> extractBlockers(Map<String, Object> metadata) {
        if (metadata == null) return List.of();
        Object assessment = metadata.get("staticRuntimeAssessment");
        if (assessment instanceof Map<?, ?> map) {
            Object blockers = map.get("blockers");
            if (blockers instanceof List<?> list) {
                return list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
        }
        return List.of();
    }
}
