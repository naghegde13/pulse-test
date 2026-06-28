package com.pulse.codegen.repository;

import com.pulse.codegen.model.DbtAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DbtAssetRepository extends JpaRepository<DbtAsset, String> {
    List<DbtAsset> findByDomainIdOrderByAssetTypeAscAssetNameAsc(String domainId);
    List<DbtAsset> findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc(
            String domainId, String branch);
    List<DbtAsset> findByDomainIdAndBusinessConceptIgnoreCaseOrderByAssetTypeAscAssetNameAsc(
            String domainId, String businessConcept);
    Optional<DbtAsset> findByDomainIdAndAssetNameAndAssetType(
            String domainId, String assetName, String assetType);
    Optional<DbtAsset> findByDomainIdAndBranchAndAssetNameAndAssetType(
            String domainId, String branch, String assetName, String assetType);
    void deleteByDomainIdAndBranch(String domainId, String branch);
}
