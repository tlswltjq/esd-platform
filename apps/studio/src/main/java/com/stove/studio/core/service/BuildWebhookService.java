package com.stove.studio.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.BuildWebhookDelivery;
import com.stove.studio.core.domain.BuildWebhookDeliveryRepository;
import com.stove.studio.core.domain.BuildWebhookSubscription;
import com.stove.studio.core.domain.BuildWebhookSubscriptionRepository;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.WebhookDeliveryStatus;
import com.stove.studio.core.domain.RegisteredBuildWebhook;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BuildWebhookService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final BuildWebhookSubscriptionRepository subscriptionRepository;
    private final BuildWebhookDeliveryRepository deliveryRepository;
    private final GameProjectService projectService;
    private final WebhookSecretCipherService secretCipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public RegisteredBuildWebhook register(Long gameId, Long workspaceId, String endpointUrl) {
        projectService.requireOwned(gameId, workspaceId);
        if (endpointUrl == null || !endpointUrl.matches("^https://\\S+$")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "webhook endpoint는 HTTPS URL이어야 합니다.");
        }
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        BuildWebhookSubscription subscription = subscriptionRepository.save(
                BuildWebhookSubscription.create(gameId, workspaceId, endpointUrl,
                        secretCipher.encrypt(secret)));
        return new RegisteredBuildWebhook(subscription, secret);
    }

    public void disable(Long gameId, Long subscriptionId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        BuildWebhookSubscription subscription = subscriptionRepository.findById(subscriptionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "webhookSubscriptionId=" + subscriptionId));
        subscription.disable();
    }

    @Transactional(readOnly = true)
    public List<BuildWebhookSubscription> list(Long gameId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        return subscriptionRepository.findByGameIdOrderByIdDesc(gameId);
    }

    public void enqueue(GameBuild build, String eventType, String failureCode) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.ofEntries(
                    Map.entry("eventType", eventType),
                    Map.entry("buildId", build.getId()),
                    Map.entry("gameId", build.getGameId()),
                    Map.entry("status", build.getStatus().name()),
                    Map.entry("productVersion", build.getVersion()),
                    Map.entry("buildNumber", build.getBuildNumber()),
                    Map.entry("platform", build.getPlatform()),
                    Map.entry("repository", nullSafe(build.getRepository())),
                    Map.entry("sourceRef", nullSafe(build.getSourceRef())),
                    Map.entry("commitSha", nullSafe(build.getCommitSha())),
                    Map.entry("ciRunId", nullSafe(build.getCiRunId())),
                    Map.entry("failureCode", nullSafe(failureCode)),
                    Map.entry("occurredAt", Instant.now().toString())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("webhook payload serialization failed", exception);
        }
        subscriptionRepository.findByGameIdAndActiveTrue(build.getGameId())
                .forEach(subscription -> deliveryRepository.save(BuildWebhookDelivery.pending(
                        subscription.getId(), build.getId(), eventType, payload)));
    }

    public int dispatchDue() {
        List<BuildWebhookDelivery> deliveries = deliveryRepository
                .findTop100ByStatusInAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRY), Instant.now());
        deliveries.forEach(this::dispatch);
        return deliveries.size();
    }

    private void dispatch(BuildWebhookDelivery delivery) {
        BuildWebhookSubscription subscription = subscriptionRepository.findById(delivery.getSubscriptionId())
                .orElse(null);
        if (subscription == null || !subscription.isActive()) {
            delivery.failed("subscription inactive");
            return;
        }
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String signature = sign(secretCipher.decrypt(subscription.getEncryptedSecret()),
                    timestamp + "." + delivery.getPayload());
            HttpRequest request = HttpRequest.newBuilder(URI.create(subscription.getEndpointUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-ESD-Event", delivery.getEventType())
                    .header("X-ESD-Delivery", delivery.getId().toString())
                    .header("X-ESD-Timestamp", timestamp)
                    .header("X-ESD-Signature", "v1=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload())).build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) delivery.delivered();
            else delivery.failed("HTTP " + response.statusCode());
        } catch (Exception exception) {
            delivery.failed(exception.getClass().getSimpleName() + ": " + nullSafe(exception.getMessage()));
        }
    }

    private String sign(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

}
