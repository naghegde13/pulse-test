package com.pulse.sor;

import com.pulse.sor.model.Dataset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for the physical-design fields added to {@link Dataset} in V116.
 * No Spring context needed — pure POJO verification.
 */
class DatasetPhysicalDesignFieldsTest {

    @Test
    void defaultValues_areCorrect() {
        Dataset ds = new Dataset();

        assertEquals("append", ds.getWriteMode(), "writeMode default must be 'append'");
        assertEquals(0, ds.getPhysicalDesignVersion(), "physicalDesignVersion default must be 0");
        assertNull(ds.getDatasetSlug(), "datasetSlug must default to null");
        assertNull(ds.getDomainSlug(), "domainSlug must default to null");
        assertNull(ds.getPartitionStrategy(), "partitionStrategy must default to null");
        assertNull(ds.getClusterStrategy(), "clusterStrategy must default to null");
        assertNull(ds.getTableFormatHint(), "tableFormatHint must default to null");
    }

    @Test
    void settersAndGetters_roundTrip() {
        Dataset ds = new Dataset();

        ds.setDatasetSlug("daily-trades");
        assertEquals("daily-trades", ds.getDatasetSlug());

        ds.setDomainSlug("capital-markets");
        assertEquals("capital-markets", ds.getDomainSlug());

        ds.setWriteMode("merge");
        assertEquals("merge", ds.getWriteMode());

        ds.setTableFormatHint("iceberg");
        assertEquals("iceberg", ds.getTableFormatHint());

        ds.setPhysicalDesignVersion(3);
        assertEquals(3, ds.getPhysicalDesignVersion());
    }

    @Test
    void partitionStrategy_acceptsTypicalShape() {
        Dataset ds = new Dataset();

        Map<String, Object> partition = Map.of(
                "kind", "range",
                "columns", List.of("trade_date"),
                "transforms", List.of("month")
        );
        ds.setPartitionStrategy(partition);

        Map<String, Object> result = ds.getPartitionStrategy();
        assertNotNull(result);
        assertEquals("range", result.get("kind"));
        assertEquals(List.of("trade_date"), result.get("columns"));
        assertEquals(List.of("month"), result.get("transforms"));
    }

    @Test
    void clusterStrategy_acceptsTypicalShape() {
        Dataset ds = new Dataset();

        Map<String, Object> cluster = Map.of(
                "columns", List.of("account_id", "region")
        );
        ds.setClusterStrategy(cluster);

        Map<String, Object> result = ds.getClusterStrategy();
        assertNotNull(result);
        assertEquals(List.of("account_id", "region"), result.get("columns"));
    }
}
