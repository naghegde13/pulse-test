package com.pulse.deploy.projection.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "runtime_projection_ddl_statements")
public class RuntimeProjectionDdlStatement extends BaseEntity {

    @Column(name = "projection_id", nullable = false)
    private String projectionId;

    @Column(name = "statement_id", nullable = false)
    private String statementId;

    @Column(name = "phase", nullable = false)
    private int phase;

    @Column(name = "executor", nullable = false)
    private String executor;

    @Column(name = "dialect")
    private String dialect;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dependency_statement_ids", columnDefinition = "jsonb")
    private List<String> dependencyStatementIds;

    @Column(name = "table_contract_id")
    private String tableContractId;

    @Column(name = "table_contract_version")
    private Integer tableContractVersion;

    @Column(name = "idempotency_mode")
    private String idempotencyMode;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column(name = "sha256", nullable = false)
    private String sha256;

    public String getProjectionId() { return projectionId; }
    public void setProjectionId(String projectionId) { this.projectionId = projectionId; }

    public String getStatementId() { return statementId; }
    public void setStatementId(String statementId) { this.statementId = statementId; }

    public int getPhase() { return phase; }
    public void setPhase(int phase) { this.phase = phase; }

    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }

    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }

    public List<String> getDependencyStatementIds() { return dependencyStatementIds; }
    public void setDependencyStatementIds(List<String> dependencyStatementIds) { this.dependencyStatementIds = dependencyStatementIds; }

    public String getTableContractId() { return tableContractId; }
    public void setTableContractId(String tableContractId) { this.tableContractId = tableContractId; }

    public Integer getTableContractVersion() { return tableContractVersion; }
    public void setTableContractVersion(Integer tableContractVersion) { this.tableContractVersion = tableContractVersion; }

    public String getIdempotencyMode() { return idempotencyMode; }
    public void setIdempotencyMode(String idempotencyMode) { this.idempotencyMode = idempotencyMode; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
}
