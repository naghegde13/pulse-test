package com.pulse.sor.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "domain_advance_log")
public class DomainAdvanceLog extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private String domainId;

    @Column(name = "previous_date")
    private LocalDate previousDate;

    @Column(name = "new_date", nullable = false)
    private LocalDate newDate;

    @Column(name = "advanced_by", nullable = false)
    private String advancedBy;

    @Column(name = "advance_source", nullable = false)
    private String advanceSource;

    @Column
    private String notes;

    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }
    public LocalDate getPreviousDate() { return previousDate; }
    public void setPreviousDate(LocalDate previousDate) { this.previousDate = previousDate; }
    public LocalDate getNewDate() { return newDate; }
    public void setNewDate(LocalDate newDate) { this.newDate = newDate; }
    public String getAdvancedBy() { return advancedBy; }
    public void setAdvancedBy(String advancedBy) { this.advancedBy = advancedBy; }
    public String getAdvanceSource() { return advanceSource; }
    public void setAdvanceSource(String advanceSource) { this.advanceSource = advanceSource; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
