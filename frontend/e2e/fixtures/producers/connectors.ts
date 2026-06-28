/**
 * Connector-definition fixtures used by the producer-onboarding Playwright spec.
 *
 * We seed exactly TWO definitions:
 *  - One Family B (external SOR — at minimum one credential property) so the
 *    onboarding test exercises the credential dialog.
 *  - One Family A (object-storage — empty connection_spec.properties) so the
 *    catalog renders more than one row and the test selects deliberately.
 *
 * Keep payload shape aligned with `ConnectorDefinition` in
 * `frontend/src/types/index.ts`. Backend usually returns more fields, but only
 * these are read by the producer UI.
 */

export const FAMILY_B_CONNECTOR_DEF = {
  id: "conn-def-postgres-b",
  airbyteId: "airbyte/source-postgres",
  name: "Postgres",
  connectorType: "SOURCE" as const,
  dockerRepository: "airbyte/source-postgres",
  dockerImageTag: "1.0.0",
  iconUrl: null,
  releaseStage: "GENERALLY_AVAILABLE" as const,
  supportedModes: ["full_refresh", "incremental"],
  documentationUrl: null,
  /**
   * Non-empty `properties` => Family B in `producers/[sorId]/page.tsx`
   * (`isFamilyAConnector` returns false). The `username` field is non-secret
   * env_metadata; `password` is the secret credential we will write through.
   */
  connectionSpec: {
    required: ["username", "password"],
    properties: {
      username: {
        type: "string",
        description: "DB username",
        pulse_role: "env_metadata",
      },
      password: {
        type: "string",
        description: "DB password",
        pulse_role: "credential",
        secret: true,
      },
    },
  },
};

export const FAMILY_A_CONNECTOR_DEF = {
  id: "conn-def-gcs-a",
  airbyteId: "airbyte/source-gcs",
  name: "GCS",
  connectorType: "SOURCE" as const,
  dockerRepository: "airbyte/source-gcs",
  dockerImageTag: "1.2.3",
  iconUrl: null,
  releaseStage: "GENERALLY_AVAILABLE" as const,
  supportedModes: ["full_refresh"],
  documentationUrl: null,
  /**
   * Empty `properties` => Family A: object-storage, no user credentials.
   * Present in the catalog so the test must actively choose Postgres.
   */
  connectionSpec: {
    properties: {},
  },
};

export const CONNECTOR_DEFINITIONS = [
  FAMILY_B_CONNECTOR_DEF,
  FAMILY_A_CONNECTOR_DEF,
];

/**
 * Mirrors the response of `GET /api/v1/connectors/{id}/definition-methods`.
 * The `define-dataset-dialog` reads `isDefault` to preselect a method, but the
 * test deliberately picks CUSTOM_SQL so the spec asserts a non-default choice
 * survives reload.
 */
export const POSTGRES_DEFINITION_METHODS = [
  {
    type: "TABLE_SELECTION" as const,
    label: "Table Selection",
    description: "Pick tables to ingest",
    isDefault: true,
  },
  {
    type: "CUSTOM_SQL" as const,
    label: "Custom SQL",
    description: "Provide a SELECT that defines the dataset",
    isDefault: false,
  },
];
