/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.JdbcCoordinationStore;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.persistence.jpa.AgentStateEntityRepository;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import io.agentscope.builder.web.persistence.jpa.JpaAgentStateStore;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.share.AgentVisibilityResolver;
import io.agentscope.builder.web.share.JpaAgentVisibilityResolver;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tracing.telemetry.TelemetryTracer;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bean assembly for the builder data plane.
 *
 * <ul>
 *   <li>{@link ObjectMapper} — shared JSON helper when Jackson auto-configuration does not expose
 *       one (WebFlux / Boot 4 setups);
 *   <li>{@link Model} — optional default DashScope model for agents whose snapshot does not pin a
 *       model name; skipped when no api-key is configured so {@code Optional<Model>} injection
 *       sites receive {@code Optional.empty()};
 *   <li>{@link BaseStore} — JDBC-backed shared key/value store. The control plane writes agent
 *       definition files (skills, subagents, mirrors) through it; the data plane reads the same
 *       namespaces from the shared database when materializing agents;
 *   <li>{@link AgentStateStore} — JPA-backed agent state persistence used by every built {@code
 *       HarnessAgent};
 *   <li>{@link CoordinationStore} — JDBC-backed coordination tables (turn leases, HITL tickets,
 *       Hands work queue) shared with the scheduler;
 *   <li>{@link SharedWorkspacePaths} — resolves the platform-wide shared workspace root
 *       (identical layout to the control plane).
 *   <li>{@link TelemetryTracer} — OTLP-based tracing to Langfuse or AgentScope Studio.
 * </ul>
 */
@Configuration
public class DataPlaneConfig {

    private static final Logger log = LoggerFactory.getLogger(DataPlaneConfig.class);

    @Value("${builder.dashscope.api-key:${claw.dashscope.api-key:}}")
    private String dashscopeApiKey;

    @Value("${builder.dashscope.model-name:${claw.dashscope.model-name:qwen-max}}")
    private String dashscopeModelName;

    @Value("${builder.dashscope.stream:${claw.dashscope.stream:true}}")
    private boolean dashscopeStream;

    @Value("${builder.openai.api-key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;

    @Value("${builder.openai.base-url:${OPENAI_BASE_URL:https://api.openai.com/v1}}")
    private String openaiBaseUrl;

    @Value("${builder.openai.model-name:${OPENAI_MODEL_NAME:gpt-4o}}")
    private String openaiModelName;

    @Value("${builder.openai.stream:true}")
    private boolean openaiStream;

    // ---- Langfuse / OTLP tracing ----
    @Value("${builder.langfuse.enabled:false}")
    private boolean langfuseEnabled;

    @Value("${builder.langfuse.public-key:}")
    private String langfusePublicKey;

    @Value("${builder.langfuse.secret-key:}")
    private String langfuseSecretKey;

    @Value("${builder.langfuse.host:https://cloud.langfuse.com}")
    private String langfuseHost;

    /**
     * Initializes OpenTelemetry tracing to Langfuse via OTLP HTTP exporter.
     * When enabled and credentials are configured, all agent/model/tool calls
     * will appear as traces in Langfuse.
     */
    @PostConstruct
    public void initLangfuseTracing() {
        if (!langfuseEnabled || langfusePublicKey.isBlank() || langfuseSecretKey.isBlank()) {
            log.info("Langfuse tracing disabled (builder.langfuse.enabled=false or missing keys)");
            return;
        }

        String otlpEndpoint = langfuseHost + "/api/public/otel/v1/traces";
        String authHeader = "Basic "
                + Base64.getEncoder()
                        .encodeToString((langfusePublicKey + ":" + langfuseSecretKey).getBytes());

        TelemetryTracer tracer = TelemetryTracer.builder()
                .endpoint(otlpEndpoint)
                .addHeader("Authorization", authHeader)
                .build();

        TracerRegistry.register(tracer);
        log.info("Langfuse tracing initialized: endpoint={}", otlpEndpoint);
    }

    @PreDestroy
    public void shutdownLangfuseTracing() {
        TracerRegistry.resetToNoop();
    }

    // ------------------------------------------------------------------
    // Bean definitions
    // ------------------------------------------------------------------

    /**
     * Ensures a shared {@link ObjectMapper} is available for {@code ManagedJsonHelper}
     * when Jackson auto-configuration does not expose one (e.g., WebFlux / Boot 4).
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    /**
     * Creates a {@link DashScopeChatModel} bean when {@code builder.dashscope.api-key} (or the
     * legacy {@code claw.dashscope.api-key}) is configured and no other {@link Model} bean is
     * present.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${builder.dashscope.api-key:${claw.dashscope.api-key:}}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    /**
     * Creates an {@link OpenAIChatModel} bean when {@code builder.openai.api-key} (or
     * {@code OPENAI_API_KEY}) is configured. Takes priority over DashScope.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${builder.openai.api-key:${OPENAI_API_KEY:}}' != ''")
    public Model openaiModel() {
        log.info("Building OpenAIChatModel: model={} baseUrl={}", openaiModelName, openaiBaseUrl);
        return OpenAIChatModel.builder()
                .apiKey(openaiApiKey)
                .modelName(openaiModelName)
                .baseUrl(openaiBaseUrl)
                .stream(openaiStream)
                .build();
    }
    /**
     * Default {@link BaseStore} backed by the Spring-managed {@link DataSource}. All planes point
     * at the same JDBC database, so definition files written by the control plane are visible
     * here. Operators can override by declaring their own {@link BaseStore} bean.
     */
    @Bean
    @ConditionalOnMissingBean(BaseStore.class)
    public BaseStore baseStore(DataSource dataSource) {
        log.info("Wiring default JdbcStore-backed BaseStore on the Spring DataSource");
        return JdbcStore.builder(dataSource).initializeSchema(true).build();
    }

    /** JPA-backed {@link AgentStateStore} shared by every agent built on this node. */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore agentStateStore(
            AgentStateEntityRepository repository, DeletedSessionRegistry deletedSessions) {
        log.info("Wiring JpaAgentStateStore on the Spring DataSource / JPA schema");
        return new JpaAgentStateStore(repository, deletedSessions);
    }

    /**
     * Default multi-replica coordination store (turn leases, HITL tickets, Hands work queue)
     * persisted in the shared builder database. The data plane writes turn leases and work items;
     * the scheduler polls the same tables.
     */
    @Bean
    @ConditionalOnMissingBean(CoordinationStore.class)
    public CoordinationStore coordinationStore(
            CoordLeaseEntityRepository leaseRepository,
            CoordHitlTicketEntityRepository hitlRepository,
            CoordWorkItemEntityRepository workRepository,
            CoordWorkerHeartbeatEntityRepository workerRepository,
            TransactionTemplate transactionTemplate) {
        log.info("Wiring default JdbcCoordinationStore on the Spring DataSource / JPA schema");
        return new JdbcCoordinationStore(
                leaseRepository,
                hitlRepository,
                workRepository,
                workerRepository,
                transactionTemplate);
    }

    /**
     * JPA-only {@link AgentVisibilityResolver} read view over the shared database (global agents
     * via materialized version rows, own/shared-in user agents via the definition store + ACL
     * grants). The control plane uses its own catalog-backed resolver instead.
     */
    @Bean
    @ConditionalOnMissingBean(AgentVisibilityResolver.class)
    public AgentVisibilityResolver agentVisibilityResolver(
            AgentVersionEntityRepository versionRepository,
            AgentVersionService versionService,
            UserAgentDefinitionStore userAgentDefinitionStore,
            UserStore userStore,
            AgentAclService aclService) {
        return new JpaAgentVisibilityResolver(
                versionRepository, versionService, userAgentDefinitionStore, userStore, aclService);
    }

    /**
     * Shared workspace path resolver. Uses the same platform-wide root as the control plane
     * ({@code ~/.agentscope/builder/workspace} by default) so per-agent data paths line up across
     * planes.
     */
    @Bean
    @ConditionalOnMissingBean(SharedWorkspacePaths.class)
    public SharedWorkspacePaths sharedWorkspacePaths(
            @Value("${builder.workspace-root:${user.home}/.agentscope/builder/workspace}")
                    String workspaceRoot) {
        Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        log.info("Builder shared workspace root: {}", root);
        return new SharedWorkspacePaths(root);
    }
}
