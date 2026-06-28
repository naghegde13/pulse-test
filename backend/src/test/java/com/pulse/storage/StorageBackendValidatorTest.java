package com.pulse.storage;

import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackendType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackendValidatorTest {

    private final StorageBackendValidator v = new StorageBackendValidator();

    // --- gold-on-GCP rule (locked spec section 5) ------------------------

    @Test
    void rejectsGcpGoldDelta() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.GOLD, LakeFormat.DELTA));
        assertTrue(ex.getMessage().contains("bq_native"),
                "Error must mention bq_native: " + ex.getMessage());
    }

    @Test
    void rejectsGcpGoldIcebergExternal() {
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.GOLD, LakeFormat.ICEBERG_EXTERNAL));
    }

    @Test
    void rejectsGcpGoldIcebergBqManaged() {
        // Even BQ-managed Iceberg is illegal at gold — gold = bq_native ONLY.
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.GOLD,
                        LakeFormat.ICEBERG_BQ_MANAGED));
    }

    @Test
    void rejectsGcpGoldParquet() {
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.GOLD, LakeFormat.PARQUET));
    }

    @Test
    void acceptsGcpGoldBqNative() {
        assertDoesNotThrow(
                () -> v.validate(StorageBackendType.GCP, LakeLayer.GOLD, LakeFormat.BQ_NATIVE));
    }

    // --- DPC formats ------------------------------------------------------

    @Test
    void rejectsDpcDeltaAtAnyLayer() {
        // Delta is no longer legal on DPC per RuntimeAuthorityService alignment.
        for (LakeLayer layer : LakeLayer.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> v.validate(StorageBackendType.DPC, layer, LakeFormat.DELTA),
                    "DPC + " + layer + " + delta should be illegal");
        }
    }

    @Test
    void acceptsDpcIcebergExternalAtAnyLayer() {
        for (LakeLayer layer : LakeLayer.values()) {
            assertDoesNotThrow(
                    () -> v.validate(StorageBackendType.DPC, layer, LakeFormat.ICEBERG_EXTERNAL));
        }
    }

    @Test
    void acceptsDpcParquetAtAnyLayer() {
        for (LakeLayer layer : LakeLayer.values()) {
            assertDoesNotThrow(
                    () -> v.validate(StorageBackendType.DPC, layer, LakeFormat.PARQUET));
        }
    }

    @Test
    void rejectsDpcBqNative() {
        // BQ_NATIVE is GCP-only. DPC has no BQ.
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.DPC, LakeLayer.GOLD, LakeFormat.BQ_NATIVE));
    }

    @Test
    void rejectsDpcIcebergBqManaged() {
        // ICEBERG_BQ_MANAGED is GCP-only.
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.DPC, LakeLayer.SILVER,
                        LakeFormat.ICEBERG_BQ_MANAGED));
    }

    // --- GCP non-gold formats --------------------------------------------

    @Test
    void rejectsGcpBronzeDelta() {
        // Delta is no longer legal on GCP per RuntimeAuthorityService alignment.
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.BRONZE, LakeFormat.DELTA));
    }

    @Test
    void acceptsGcpSilverIcebergExternal() {
        assertDoesNotThrow(
                () -> v.validate(StorageBackendType.GCP, LakeLayer.SILVER,
                        LakeFormat.ICEBERG_EXTERNAL));
    }

    @Test
    void acceptsGcpSilverIcebergBqManaged() {
        assertDoesNotThrow(
                () -> v.validate(StorageBackendType.GCP, LakeLayer.SILVER,
                        LakeFormat.ICEBERG_BQ_MANAGED));
    }

    @Test
    void rejectsGcpBronzeParquet() {
        // PARQUET is DPC-only.
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.BRONZE, LakeFormat.PARQUET));
    }

    @Test
    void rejectsGcpBronzeBqNative() {
        // BQ_NATIVE is gold-only.
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.GCP, LakeLayer.BRONZE, LakeFormat.BQ_NATIVE));
    }

    // --- non-table-producing blueprints ----------------------------------

    @Test
    void allowsLayerAndFormatBothNullForNonTableBlueprints() {
        // FileArrivalSensor, DQValidator etc. — backend alone is enough.
        assertDoesNotThrow(() -> v.validate(StorageBackendType.GCP, null, null));
        assertDoesNotThrow(() -> v.validate(StorageBackendType.DPC, null, null));
    }

    @Test
    void rejectsLayerWithoutFormatOrFormatWithoutLayer() {
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.DPC, LakeLayer.BRONZE, null));
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(StorageBackendType.DPC, null, LakeFormat.DELTA));
    }

    @Test
    void rejectsNullBackend() {
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(null, LakeLayer.BRONZE, LakeFormat.DELTA));
    }

    // --- legalFormatsFor (for UI auto-defaulting) ------------------------

    @Test
    void legalFormatsFor_gcpGold_isBqNativeOnly() {
        assertTrue(v.legalFormatsFor(StorageBackendType.GCP, LakeLayer.GOLD)
                .equals(java.util.EnumSet.of(LakeFormat.BQ_NATIVE)));
    }

    @Test
    void legalFormatsFor_dpcGold_includesParquetAndIceberg() {
        var legal = v.legalFormatsFor(StorageBackendType.DPC, LakeLayer.GOLD);
        assertTrue(legal.contains(LakeFormat.ICEBERG_EXTERNAL));
        assertTrue(legal.contains(LakeFormat.PARQUET));
        assertTrue(!legal.contains(LakeFormat.DELTA));
        assertTrue(!legal.contains(LakeFormat.BQ_NATIVE));
    }

    @Test
    void legalFormatsFor_gcpBronze_includesIcebergBqManaged() {
        var legal = v.legalFormatsFor(StorageBackendType.GCP, LakeLayer.BRONZE);
        assertTrue(legal.contains(LakeFormat.ICEBERG_BQ_MANAGED));
        assertTrue(!legal.contains(LakeFormat.PARQUET));
        assertTrue(!legal.contains(LakeFormat.BQ_NATIVE));
    }
}
