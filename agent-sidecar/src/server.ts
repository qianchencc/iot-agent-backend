import { buildApp } from "./app.js";
import { loadConfig, type SidecarConfig } from "./config.js";
import { createObservability, credentialsDigest, fetchObservabilitySettings } from "./observability.js";

// One explicit YAML file plus owner-only token files; there is no environment
// or .env fallback, so a misconfigured deployment fails closed at startup.
let config: SidecarConfig;
try {
  config = await loadConfig(process.argv.slice(2));
} catch (error) {
  console.error("agent-sidecar startup failed:", error instanceof Error ? error.message : error);
  process.exit(1);
}

// One process owns one observability SDK: the settings snapshot is fetched at
// startup from the Java admin configuration and never swapped afterwards.
const observabilitySettings = await fetchObservabilitySettings(config).catch((error: unknown) => {
  console.error("agent-sidecar startup failed: unable to load observability configuration from Java",
    error instanceof Error ? error.message : error);
  process.exit(1);
});
const app = buildApp(config, createObservability(observabilitySettings), () => ({
  enabled: observabilitySettings.enabled,
  ...(observabilitySettings.revision !== undefined ? { revision: observabilitySettings.revision } : {}),
  ...(observabilitySettings.enabled && observabilitySettings.baseUrl
    ? { baseUrl: observabilitySettings.baseUrl, credentialsDigest: credentialsDigest(observabilitySettings) }
    : {}),
}));

const shutdown = async (signal: string): Promise<void> => {
  app.log.info({ signal }, "sidecar shutting down");
  await app.close();
};

process.once("SIGTERM", () => { void shutdown("SIGTERM").finally(() => process.exit(0)); });
process.once("SIGINT", () => { void shutdown("SIGINT").finally(() => process.exit(0)); });

await app.listen({ host: config.host, port: config.port });
