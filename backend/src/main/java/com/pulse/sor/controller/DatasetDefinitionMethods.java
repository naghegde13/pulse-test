package com.pulse.sor.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatasetDefinitionMethods {

    public static List<Map<String, Object>> getMethodsForConnector(String connectorDockerRepo) {
        if (connectorDockerRepo == null) return getDefault();

        String repo = connectorDockerRepo.toLowerCase();

        if (repo.contains("postgres") || repo.contains("mysql") || repo.contains("oracle")
                || repo.contains("mssql") || repo.contains("snowflake") || repo.contains("bigquery")
                || repo.contains("jdbc") || repo.contains("redshift") || repo.contains("clickhouse")) {
            return jdbcMethods();
        }
        if (repo.contains("source-s3") || repo.contains("source-gcs") || repo.contains("destination-s3")) {
            return fileMethods();
        }
        if (repo.contains("sftp") || repo.contains("ftp")) {
            return fileMethods();
        }
        if (repo.contains("kafka")) {
            return kafkaMethods();
        }
        if (repo.contains("rest-api") || repo.contains("declarative-manifest")) {
            return restApiMethods();
        }
        if (repo.contains("salesforce")) {
            return salesforceMethods();
        }
        if (repo.contains("mongodb")) {
            return mongoMethods();
        }
        if (repo.contains("elasticsearch")) {
            return elasticsearchMethods();
        }

        return getDefault();
    }

    private static List<Map<String, Object>> jdbcMethods() {
        return List.of(
                method("TABLE_SELECTION", "Select Tables", "Browse database schemas and pick tables to ingest. Schema auto-discovered from database metadata.", true),
                method("CUSTOM_SQL", "Custom SQL Query", "Write a SELECT query to define exactly what data to pull. Supports complex joins, filters, and CTEs.", false),
                sampleUpload(),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define fields and types by hand.", false)
        );
    }

    private static List<Map<String, Object>> fileMethods() {
        return List.of(
                sampleUpload(true),
                method("FILE_INFERENCE", "Infer from File Path", "Point to a bucket/path with a glob pattern. Schema inferred from file headers (CSV) or metadata (Parquet/Avro).", false),
                method("SCHEMA_UPLOAD", "Upload Schema", "Upload a JSON Schema, Avro .avsc, or paste a column definition to define the expected file format.", false),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define fields and types by hand for flat files with no headers.", false)
        );
    }

    private static List<Map<String, Object>> kafkaMethods() {
        return List.of(
                method("SCHEMA_REGISTRY", "Schema Registry Lookup", "Fetch the Avro/Protobuf/JSON schema from a Confluent Schema Registry for this topic.", true),
                method("SCHEMA_UPLOAD", "Upload Schema", "Upload an Avro .avsc file, paste JSON Schema, or Protobuf definition for the message envelope.", false),
                method("SAMPLE_INFERENCE", "Sample from Topic", "Read N sample messages from the topic and infer schema from the payloads.", false),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define message fields and types by hand.", false)
        );
    }

    private static List<Map<String, Object>> restApiMethods() {
        return List.of(
                method("API_SPEC_IMPORT", "Import OpenAPI/Swagger Spec", "Provide a URL to or paste an OpenAPI spec. PULSE extracts response schemas for each endpoint.", true),
                method("SCHEMA_UPLOAD", "Paste JSON Schema", "Paste the expected JSON response body schema for a single endpoint.", false),
                method("SAMPLE_INFERENCE", "Sample from Endpoint", "Call the API and infer schema from the response body.", false),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define response fields and types by hand.", false)
        );
    }

    private static List<Map<String, Object>> salesforceMethods() {
        return List.of(
                method("OBJECT_SELECTION", "Select Salesforce Objects", "Browse standard and custom Salesforce objects (Account, Contact, Opportunity, etc.) and select which ones to ingest.", true),
                method("CUSTOM_SQL", "SOQL Query", "Write a SOQL query for custom data extraction from Salesforce.", false),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define fields and types by hand.", false)
        );
    }

    private static List<Map<String, Object>> mongoMethods() {
        return List.of(
                method("OBJECT_SELECTION", "Select Collections", "Browse MongoDB collections and select which ones to ingest. Schema inferred by sampling documents.", true),
                method("SCHEMA_UPLOAD", "Upload JSON Schema", "Provide an explicit JSON Schema for a collection.", false),
                method("SAMPLE_INFERENCE", "Sample Documents", "Read N sample documents from a collection and infer schema.", false),
                sampleUpload(),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define fields and types by hand.", false)
        );
    }

    private static List<Map<String, Object>> elasticsearchMethods() {
        return List.of(
                method("OBJECT_SELECTION", "Select Indices", "Browse Elasticsearch indices and select which ones to ingest. Schema derived from index mappings.", true),
                method("SCHEMA_UPLOAD", "Paste Index Mapping", "Paste the Elasticsearch index mapping JSON.", false),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define fields and types by hand.", false)
        );
    }

    private static List<Map<String, Object>> getDefault() {
        return List.of(
                sampleUpload(true),
                method("SCHEMA_UPLOAD", "Upload Schema", "Upload a JSON Schema or paste a schema definition.", false),
                method("MANUAL_DEFINITION", "Manual Field Definition", "Define fields and types by hand.", false)
        );
    }

    /**
     * LCT-017: First-class "Upload Sample File" method. Routes to the
     * server-side deterministic sample-ingestion workflow (LCT-018) which
     * parses the file content, infers a typed schema with PII/classification
     * evidence, and persists a schema snapshot.
     */
    private static Map<String, Object> sampleUpload() {
        return sampleUpload(false);
    }

    private static Map<String, Object> sampleUpload(boolean isDefault) {
        return method("SAMPLE_UPLOAD", "Upload Sample File",
                "Upload a CSV or JSON sample. PULSE parses the content, infers a typed schema, "
                        + "previews rows, and flags PII/confidential columns automatically.",
                isDefault);
    }

    private static Map<String, Object> method(String type, String label, String description, boolean isDefault) {
        return Map.of(
                "type", type,
                "label", label,
                "description", description,
                "isDefault", isDefault
        );
    }
}
