package com.pulse.sor.service;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.AsofAdvanceLog;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.DomainAdvanceLog;
import com.pulse.sor.repository.AsofAdvanceLogRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainAdvanceLogRepository;
import com.pulse.sor.repository.DomainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

@Service
public class TimeDimensionService {

    private final DatasetRepository datasetRepo;
    private final DomainRepository domainRepo;
    private final AsofAdvanceLogRepository asofLogRepo;
    private final DomainAdvanceLogRepository domainLogRepo;

    public TimeDimensionService(DatasetRepository datasetRepo,
                                DomainRepository domainRepo,
                                AsofAdvanceLogRepository asofLogRepo,
                                DomainAdvanceLogRepository domainLogRepo) {
        this.datasetRepo = datasetRepo;
        this.domainRepo = domainRepo;
        this.asofLogRepo = asofLogRepo;
        this.domainLogRepo = domainLogRepo;
    }

    @Transactional
    public Dataset advanceDataset(String datasetId, String advancedBy, String source, String notes) {
        return advanceDataset(datasetId, advancedBy, source, notes, null);
    }

    @Transactional
    public Dataset advanceDataset(String datasetId, String advancedBy, String source, String notes, String requestedAsof) {
        Dataset ds = datasetRepo.findById(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Dataset", datasetId));

        if (ds.getTimeGrain() == null) {
            throw new IllegalStateException("Dataset has no time grain configured");
        }

        Instant previousAsof = ds.getCurrentAsof();
        ZoneId zone = ZoneId.of(ds.getAsofTimezone() != null ? ds.getAsofTimezone() : "America/New_York");
        ZonedDateTime current = previousAsof != null
                ? previousAsof.atZone(zone)
                : ZonedDateTime.now(zone).withHour(0).withMinute(0).withSecond(0).withNano(0);

        ZonedDateTime next = requestedAsof == null || requestedAsof.isBlank()
                ? computeNext(current, ds.getTimeGrain(), ds.getTimeGrainConfig())
                : parseRequestedAsof(requestedAsof, zone);
        if (!next.toInstant().isAfter(current.toInstant())) {
            AsofAdvanceLog rejected = new AsofAdvanceLog();
            rejected.setDatasetId(datasetId);
            rejected.setPreviousAsof(previousAsof);
            rejected.setNewAsof(next.toInstant());
            rejected.setRequestedAsof(next.toInstant());
            rejected.setAdvancedBy(advancedBy);
            rejected.setAdvanceSource(source);
            rejected.setNotes(notes);
            rejected.setAdvanceStatus("REJECTED");
            asofLogRepo.save(rejected);
            throw new IllegalArgumentException("Requested as-of must be after current as-of");
        }
        ds.setCurrentAsof(next.toInstant());
        datasetRepo.save(ds);

        AsofAdvanceLog log = new AsofAdvanceLog();
        log.setDatasetId(datasetId);
        log.setPreviousAsof(previousAsof);
        log.setNewAsof(next.toInstant());
        log.setRequestedAsof(next.toInstant());
        log.setAdvancedBy(advancedBy);
        log.setAdvanceSource(source);
        log.setNotes(notes);
        log.setAdvanceStatus("ACCEPTED");
        asofLogRepo.save(log);

        return ds;
    }

    @Transactional
    public Domain advanceDomain(String domainId, String advancedBy, String source, String notes) {
        Domain domain = domainRepo.findById(domainId)
                .orElseThrow(() -> new ResourceNotFoundException("Domain", domainId));

        LocalDate previousDate = domain.getCurrentBusinessDate();
        String grain = domain.getBusinessDateGrain() != null ? domain.getBusinessDateGrain() : "DAILY";
        LocalDate current = previousDate != null ? previousDate : LocalDate.now();

        ZonedDateTime currentZdt = current.atStartOfDay(
                ZoneId.of(domain.getBusinessDateTimezone() != null ? domain.getBusinessDateTimezone() : "America/New_York"));
        ZonedDateTime nextZdt = computeNext(currentZdt, grain, domain.getBusinessDateConfig());
        LocalDate nextDate = nextZdt.toLocalDate();

        domain.setCurrentBusinessDate(nextDate);
        domainRepo.save(domain);

        DomainAdvanceLog log = new DomainAdvanceLog();
        log.setDomainId(domainId);
        log.setPreviousDate(previousDate);
        log.setNewDate(nextDate);
        log.setAdvancedBy(advancedBy);
        log.setAdvanceSource(source);
        log.setNotes(notes);
        domainLogRepo.save(log);

        return domain;
    }

    public Instant computeNextAsof(Dataset ds) {
        if (ds.getCurrentAsof() == null || ds.getTimeGrain() == null) return null;
        ZoneId zone = ZoneId.of(ds.getAsofTimezone() != null ? ds.getAsofTimezone() : "America/New_York");
        ZonedDateTime current = ds.getCurrentAsof().atZone(zone);
        return computeNext(current, ds.getTimeGrain(), ds.getTimeGrainConfig()).toInstant();
    }

    public List<AsofAdvanceLog> getDatasetAdvanceHistory(String datasetId) {
        return asofLogRepo.findByDatasetIdOrderByCreatedAtDesc(datasetId);
    }

    public List<DomainAdvanceLog> getDomainAdvanceHistory(String domainId) {
        return domainLogRepo.findByDomainIdOrderByCreatedAtDesc(domainId);
    }

    @SuppressWarnings("unchecked")
    private ZonedDateTime computeNext(ZonedDateTime current, String grain, Map<String, Object> config) {
        if (config == null) config = Map.of();

        return switch (grain.toUpperCase()) {
            case "DAILY" -> current.plusDays(1);
            case "DAILY_BUSINESS_DAY" -> nextBusinessDay(current);
            case "WEEKLY" -> {
                List<String> days = config.containsKey("days")
                        ? (List<String>) config.get("days")
                        : List.of("MON");
                yield nextWeeklyDay(current, days);
            }
            case "BEG_OF_MONTH" -> current.plusMonths(1).withDayOfMonth(1)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);
            case "END_OF_MONTH" -> current.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);
            case "BEG_OF_QUARTER" -> {
                int currentMonth = current.getMonthValue();
                int nextQuarterStartMonth = ((currentMonth - 1) / 3 + 1) * 3 + 1;
                if (nextQuarterStartMonth > 12) {
                    yield current.plusYears(1).withMonth(1).withDayOfMonth(1)
                            .withHour(0).withMinute(0).withSecond(0).withNano(0);
                }
                yield current.withMonth(nextQuarterStartMonth).withDayOfMonth(1)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0);
            }
            case "END_OF_QUARTER" -> {
                int cm = current.getMonthValue();
                int endMonth = ((cm - 1) / 3 + 1) * 3;
                if (endMonth == cm && current.getDayOfMonth() == current.toLocalDate().lengthOfMonth()) {
                    endMonth += 3;
                }
                if (endMonth > 12) {
                    yield current.plusYears(1).withMonth(3).with(TemporalAdjusters.lastDayOfMonth())
                            .withHour(0).withMinute(0).withSecond(0).withNano(0);
                }
                yield current.withMonth(endMonth).with(TemporalAdjusters.lastDayOfMonth())
                        .withHour(0).withMinute(0).withSecond(0).withNano(0);
            }
            case "EVERY_N_HOURS" -> {
                int hours = config.containsKey("interval_hours")
                        ? ((Number) config.get("interval_hours")).intValue()
                        : 1;
                yield current.plusHours(hours);
            }
            case "CONTINUOUS" -> current; // No advancement for real-time
            default -> current.plusDays(1);
        };
    }

    private ZonedDateTime nextBusinessDay(ZonedDateTime current) {
        ZonedDateTime next = current.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    private ZonedDateTime parseRequestedAsof(String requestedAsof, ZoneId zone) {
        try {
            return Instant.parse(requestedAsof).atZone(zone);
        } catch (DateTimeException ignored) {
            return LocalDate.parse(requestedAsof).atStartOfDay(zone);
        }
    }

    private ZonedDateTime nextWeeklyDay(ZonedDateTime current, List<String> days) {
        List<DayOfWeek> targetDays = days.stream()
                .map(d -> DayOfWeek.valueOf(d.toUpperCase().length() == 3
                        ? expandDay(d.toUpperCase()) : d.toUpperCase()))
                .sorted()
                .toList();
        if (targetDays.isEmpty()) return current.plusWeeks(1);

        ZonedDateTime candidate = current.plusDays(1);
        for (int i = 0; i < 8; i++) {
            if (targetDays.contains(candidate.getDayOfWeek())) return candidate;
            candidate = candidate.plusDays(1);
        }
        return current.plusWeeks(1);
    }

    private String expandDay(String abbrev) {
        return switch (abbrev) {
            case "MON" -> "MONDAY";
            case "TUE" -> "TUESDAY";
            case "WED" -> "WEDNESDAY";
            case "THU" -> "THURSDAY";
            case "FRI" -> "FRIDAY";
            case "SAT" -> "SATURDAY";
            case "SUN" -> "SUNDAY";
            default -> abbrev;
        };
    }
}
