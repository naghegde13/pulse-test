package com.pulse.sor.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "asof_advance_log")
public class AsofAdvanceLog extends BaseEntity {

    @Column(name = "dataset_id", nullable = false)
    private String datasetId;

    @Column(name = "previous_asof")
    private Instant previousAsof;

    @Column(name = "new_asof", nullable = false)
    private Instant newAsof;

    @Column(name = "requested_asof")
    private Instant requestedAsof;

    @Column(name = "advance_status", nullable = false)
    private String advanceStatus = "ACCEPTED";

    @Column(name = "advanced_by", nullable = false)
    private String advancedBy;

    @Column(name = "advance_source", nullable = false)
    private String advanceSource;

    @Column
    private String notes;

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public Instant getPreviousAsof() { return previousAsof; }
    public void setPreviousAsof(Instant previousAsof) { this.previousAsof = previousAsof; }
    public Instant getNewAsof() { return newAsof; }
    public void setNewAsof(Instant newAsof) { this.newAsof = newAsof; }
    public Instant getRequestedAsof() { return requestedAsof; }
    public void setRequestedAsof(Instant requestedAsof) { this.requestedAsof = requestedAsof; }
    public String getAdvanceStatus() { return advanceStatus; }
    public void setAdvanceStatus(String advanceStatus) { this.advanceStatus = advanceStatus; }
    public String getAdvancedBy() { return advancedBy; }
    public void setAdvancedBy(String advancedBy) { this.advancedBy = advancedBy; }
    public String getAdvanceSource() { return advanceSource; }
    public void setAdvanceSource(String advanceSource) { this.advanceSource = advanceSource; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
