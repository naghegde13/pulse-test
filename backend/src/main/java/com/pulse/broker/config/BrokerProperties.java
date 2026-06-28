package com.pulse.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulse.broker")
public class BrokerProperties {

    private boolean enabled = true;
    private long mirrorRefreshSeconds = 300;
    private long mirrorStalenessSeconds = 1800;
    private long maxCancelWaitSeconds = 1800;
    private long inboundRetentionDays = 90;
    private final Envelope envelope = new Envelope();
    private final Reconciler reconciler = new Reconciler();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getMirrorRefreshSeconds() { return mirrorRefreshSeconds; }
    public void setMirrorRefreshSeconds(long mirrorRefreshSeconds) { this.mirrorRefreshSeconds = mirrorRefreshSeconds; }
    public long getMirrorStalenessSeconds() { return mirrorStalenessSeconds; }
    public void setMirrorStalenessSeconds(long mirrorStalenessSeconds) { this.mirrorStalenessSeconds = mirrorStalenessSeconds; }
    public long getMaxCancelWaitSeconds() { return maxCancelWaitSeconds; }
    public void setMaxCancelWaitSeconds(long maxCancelWaitSeconds) { this.maxCancelWaitSeconds = maxCancelWaitSeconds; }
    public long getInboundRetentionDays() { return inboundRetentionDays; }
    public void setInboundRetentionDays(long inboundRetentionDays) { this.inboundRetentionDays = inboundRetentionDays; }
    public Envelope getEnvelope() { return envelope; }
    public Reconciler getReconciler() { return reconciler; }

    public static class Envelope {
        private long maxTtlSecondsDefault = 300;
        private long replayWindowSeconds = 900;
        private String localIssuer = "pulse-local";
        private String localAudience = "pulse-peer";
        private String signingSecret = "pulse-broker-dev-signing-secret";

        public long getMaxTtlSecondsDefault() { return maxTtlSecondsDefault; }
        public void setMaxTtlSecondsDefault(long maxTtlSecondsDefault) {
            this.maxTtlSecondsDefault = maxTtlSecondsDefault;
        }
        public long getReplayWindowSeconds() { return replayWindowSeconds; }
        public void setReplayWindowSeconds(long replayWindowSeconds) {
            this.replayWindowSeconds = replayWindowSeconds;
        }
        public String getLocalIssuer() { return localIssuer; }
        public void setLocalIssuer(String localIssuer) { this.localIssuer = localIssuer; }
        public String getLocalAudience() { return localAudience; }
        public void setLocalAudience(String localAudience) { this.localAudience = localAudience; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    }

    public static class Reconciler {
        private long pollIntervalDefault = 30;
        private int consecutiveFailureMax = 12;
        private long backoffCeilingSeconds = 600;

        public long getPollIntervalDefault() { return pollIntervalDefault; }
        public void setPollIntervalDefault(long pollIntervalDefault) {
            this.pollIntervalDefault = pollIntervalDefault;
        }
        public int getConsecutiveFailureMax() { return consecutiveFailureMax; }
        public void setConsecutiveFailureMax(int consecutiveFailureMax) {
            this.consecutiveFailureMax = consecutiveFailureMax;
        }
        public long getBackoffCeilingSeconds() { return backoffCeilingSeconds; }
        public void setBackoffCeilingSeconds(long backoffCeilingSeconds) {
            this.backoffCeilingSeconds = backoffCeilingSeconds;
        }
    }
}
