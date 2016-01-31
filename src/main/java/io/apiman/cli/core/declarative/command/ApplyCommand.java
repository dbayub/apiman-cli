/*
 * Copyright 2016 Pete Cornish
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apiman.cli.core.declarative.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import io.apiman.cli.command.AbstractFinalCommand;
import io.apiman.cli.core.api.VersionAgnosticApi;
import io.apiman.cli.core.api.model.Api;
import io.apiman.cli.core.api.model.ApiConfig;
import io.apiman.cli.core.api.model.ApiPolicy;
import io.apiman.cli.core.common.ActionApi;
import io.apiman.cli.core.common.model.ServerVersion;
import io.apiman.cli.core.common.util.ServerActionUtil;
import io.apiman.cli.core.declarative.model.Declaration;
import io.apiman.cli.core.declarative.model.DeclarativeApi;
import io.apiman.cli.core.gateway.GatewayApi;
import io.apiman.cli.core.gateway.model.Gateway;
import io.apiman.cli.core.org.OrgApi;
import io.apiman.cli.core.org.model.Org;
import io.apiman.cli.core.plugin.PluginApi;
import io.apiman.cli.core.plugin.model.Plugin;
import io.apiman.cli.exception.CommandException;
import io.apiman.cli.exception.DeclarativeException;
import io.apiman.cli.util.BeanUtil;
import io.apiman.cli.util.MappingUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import retrofit.RetrofitError;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static io.apiman.cli.util.OptionalConsumer.of;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Applies an API environment declaration.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public class ApplyCommand extends AbstractFinalCommand {
    private static final Logger LOGGER = LogManager.getLogger(ApplyCommand.class);
    private static final String JSON_EXTENSION = ".json";
    private static final String STATE_READY = "READY";
    private static final String STATE_PUBLISHED = "PUBLISHED";
    private static final String STATE_RETIRED = "RETIRED";

    @Option(name = "--declarationFile", aliases = {"-f"}, usage = "Declaration file")
    private Path declarationFile;

    @Option(name = "-P", usage = "Set property (key=value)")
    private List<String> properties;

    @Option(name = "--serverVersion", aliases = {"-sv"}, usage = "Management API server version")
    protected ServerVersion serverVersion = ServerVersion.DEFAULT_VERSION;

    @Override
    protected String getCommandDescription() {
        return "Apply declaration";
    }

    @Override
    public void performAction(CmdLineParser parser) throws CommandException {
        final Declaration declaration;

        // parse declaration
        if (declarationFile.endsWith(JSON_EXTENSION)) {
            declaration = loadDeclaration(declarationFile, MappingUtil.JSON_MAPPER);
        } else {
            // default is YAML
            declaration = loadDeclaration(declarationFile, MappingUtil.YAML_MAPPER);
        }

        LOGGER.info("Loaded declaration: {}", declarationFile);
        LOGGER.debug("Declaration loaded: {}", () -> MappingUtil.safeWriteValueAsJson(declaration));

        try {
            applyDeclaration(declaration);
        } catch (Exception e) {
            throw new CommandException("Error applying declaration", e);
        }
    }

    /**
     * Apply the given Declaration.
     *
     * @param declaration the Declaration to apply.
     */
    public void applyDeclaration(Declaration declaration) {
        LOGGER.debug("Applying declaration");

        // add gateways
        applyGateways(declaration);

        // add plugins
        applyPlugins(declaration);

        // add org
        ofNullable(declaration.getOrg()).ifPresent(declarativeOrg -> {
            final String orgName = declaration.getOrg().getName();
            final OrgApi orgApiClient = buildServerApiClient(OrgApi.class);

            of(checkExists(() -> orgApiClient.fetch(orgName)))
                    .ifPresent(existing -> {
                        LOGGER.info("Org already exists: {}", orgName);
                    })
                    .ifNotPresent(() -> {
                        LOGGER.info("Adding org: {}", orgName);
                        orgApiClient.create(MappingUtil.map(declaration.getOrg(), Org.class));
                    });

            // add apis
            applyApis(declaration, orgName);
        });

        LOGGER.info("Applied declaration");
    }

    /**
     * Add gateways if they are not present.
     *
     * @param declaration the Declaration to apply.
     */
    private void applyGateways(Declaration declaration) {
        ofNullable(declaration.getSystem().getGateways()).ifPresent(gateways -> {
            LOGGER.debug("Applying gateways");

            gateways.forEach(declarativeGateway -> {
                final GatewayApi apiClient = buildServerApiClient(GatewayApi.class);
                final String gatewayName = declarativeGateway.getName();

                of(checkExists(() -> apiClient.fetch(gatewayName)))
                        .ifPresent(existing -> {
                            LOGGER.info("Gateway already exists: {}", gatewayName);
                        })
                        .ifNotPresent(() -> {
                            LOGGER.info("Adding gateway: {}", gatewayName);

                            final Gateway gateway = MappingUtil.map(declarativeGateway, Gateway.class);
                            apiClient.create(gateway);
                        });
            });
        });
    }

    /**
     * Add plugins if they are not present.
     *
     * @param declaration the Declaration to apply.
     */
    private void applyPlugins(Declaration declaration) {
        ofNullable(declaration.getSystem().getPlugins()).ifPresent(plugins -> {
            LOGGER.debug("Applying plugins");

            plugins.forEach(plugin -> {
                final PluginApi apiClient = buildServerApiClient(PluginApi.class);

                if (checkPluginExists(plugin, apiClient)) {
                    LOGGER.info("Plugin already installed: {}", plugin.getName());
                } else {
                    LOGGER.info("Installing plugin: {}", plugin.getName());
                    apiClient.create(plugin);
                }
            });
        });
    }

    /**
     * Determine if the plugin is installed.
     *
     * @param plugin
     * @param apiClient
     * @return <code>true</code> if the plugin is installed, otherwise <code>false</code>
     */
    private boolean checkPluginExists(Plugin plugin, PluginApi apiClient) {
        return checkExists(apiClient::list)
                .map(apiPolicies -> apiPolicies.stream()
                        .anyMatch(installedPlugin ->
                                plugin.getArtifactId().equals(installedPlugin.getArtifactId()) &&
                                        plugin.getGroupId().equals(installedPlugin.getGroupId()) &&
                                        plugin.getVersion().equals(installedPlugin.getVersion()) &&
                                        BeanUtil.safeEquals(plugin.getClassifier(), installedPlugin.getClassifier())
                        ))
                .orElse(false);
    }

    /**
     * Add and configure APIs if they are not present.
     *
     * @param declaration the Declaration to apply.
     * @param orgName
     */
    private void applyApis(Declaration declaration, String orgName) {
        ofNullable(declaration.getOrg().getApis()).ifPresent(declarativeApis -> {
            LOGGER.debug("Applying APIs");

            declarativeApis.forEach(declarativeApi -> {
                final VersionAgnosticApi apiClient = buildServerApiClient(VersionAgnosticApi.class, serverVersion);
                final String apiName = declarativeApi.getName();
                final String apiVersion = declarativeApi.getInitialVersion();

                // create and configure API
                applyApi(apiClient, declarativeApi, orgName, apiName, apiVersion);

                // add policies
                applyPolicies(apiClient, declarativeApi, orgName, apiName, apiVersion);

                // publish API
                if (declarativeApi.isPublished()) {
                    publish(apiClient, orgName, apiName, apiVersion);
                }
            });
        });
    }

    /**
     * Add and configure the API if it is not present.
     *
     * @param apiClient
     * @param declarativeApi
     * @param orgName
     * @param apiName
     * @param apiVersion
     * @return the state of the API
     */
    private void applyApi(VersionAgnosticApi apiClient, DeclarativeApi declarativeApi, String orgName,
                          String apiName, String apiVersion) {

        LOGGER.debug("Applying API: {}", apiName);

        of(checkExists(() -> apiClient.fetch(orgName, apiName, apiVersion)))
                .ifPresent(existing -> {
                    LOGGER.info("API already exists: {}", apiName);
                })
                .ifNotPresent(() -> {
                    LOGGER.info("Adding API: {}", apiName);

                    // create API
                    final Api api = MappingUtil.map(declarativeApi, Api.class);
                    apiClient.create(orgName, api);

                    if (ServerVersion.v11x.equals(serverVersion)) {
                        // do this only on initial creation as v1.1.x API throws a 409 if this is called more than once
                        configureApi(declarativeApi, apiClient, orgName, apiName, apiVersion);
                    }
                });

        if (ServerVersion.v12x.equals(serverVersion)) {
            // The v1.2.x API supports configuration of the API even if published (but not retired)
            final String apiState = fetchCurrentState(apiClient, orgName, apiName, apiVersion);
            if (STATE_RETIRED.equals(apiState.toUpperCase())) {
                LOGGER.warn("API '{}' is retired - skipping configuration", apiName);

            } else {
                configureApi(declarativeApi, apiClient, orgName, apiName, apiVersion);
            }
        }
    }

    /**
     * Return the current state of the API.
     *
     * @param apiClient
     * @param orgName
     * @param apiName
     * @param apiVersion
     * @return the API state
     */
    private String fetchCurrentState(VersionAgnosticApi apiClient, String orgName, String apiName, String apiVersion) {
        final String apiState = ofNullable(apiClient.fetch(orgName, apiName, apiVersion).getStatus()).orElse("");
        LOGGER.debug("API '{}' state: {}", apiName, apiState);
        return apiState;
    }

    /**
     * Configures the API using the declarative API configuration.
     *
     * @param declarativeApi
     * @param apiClient
     * @param orgName
     * @param apiName
     * @param apiVersion
     */
    private void configureApi(DeclarativeApi declarativeApi, VersionAgnosticApi apiClient,
                              String orgName, String apiName, String apiVersion) {

        LOGGER.info("Configuring API: {}", apiName);

        final ApiConfig apiConfig = MappingUtil.map(declarativeApi.getConfig(), ApiConfig.class);
        apiClient.configure(orgName, apiName, apiVersion, apiConfig);
    }

    /**
     * Add policies to the API if they are not present.
     *
     * @param apiClient
     * @param declarativeApi
     * @param orgName
     * @param apiName
     * @param apiVersion
     */
    private void applyPolicies(VersionAgnosticApi apiClient, DeclarativeApi declarativeApi, String orgName,
                               String apiName, String apiVersion) {

        ofNullable(declarativeApi.getPolicies()).ifPresent(declarativePolicies -> {
            LOGGER.debug("Applying policies to API: {}", apiName);

            // existing policies for the API
            final List<ApiPolicy> apiPolicies = apiClient.fetchPolicies(orgName, apiName, apiVersion);

            declarativePolicies.forEach(declarativePolicy -> {
                final String policyName = declarativePolicy.getName();

                final ApiPolicy apiPolicy = new ApiPolicy(
                        MappingUtil.safeWriteValueAsJson(declarativePolicy.getConfig()));

                // determine if the policy already exists for this API
                final Optional<ApiPolicy> existingPolicy = apiPolicies.stream()
                        .filter(p -> policyName.equals(p.getPolicyDefinitionId()))
                        .findFirst();

                if (existingPolicy.isPresent()) {
                    if (ServerVersion.v12x.equals(serverVersion)) {
                        // update the existing policy config
                        LOGGER.info("Updating existing policy '{}' configuration for API: {}", policyName, apiName);

                        final Long policyId = existingPolicy.get().getId();
                        apiClient.configurePolicy(orgName, apiName, apiVersion, policyId, apiPolicy);

                    } else {
                        LOGGER.info("Policy '{}' already exists for API '{}' - skipping configuration update", policyName, apiName);
                    }

                } else {
                    // add new policy
                    LOGGER.info("Adding policy '{}' to API: {}", policyName, apiName);

                    apiPolicy.setDefinitionId(policyName);
                    apiClient.addPolicy(orgName, apiName, apiVersion, apiPolicy);
                }
            });
        });
    }

    /**
     * Publish the API, if it is in the 'Ready' state.
     *
     * @param apiClient
     * @param orgName
     * @param apiName
     * @param apiVersion
     */
    private void publish(VersionAgnosticApi apiClient, String orgName, String apiName, String apiVersion) {
        LOGGER.debug("Attempting to publish API: {}", apiName);
        final String apiState = fetchCurrentState(apiClient, orgName, apiName, apiVersion);

        switch (apiState.toUpperCase()) {
            case STATE_READY:
                performPublish(orgName, apiName, apiVersion);
                break;

            case STATE_PUBLISHED:
                switch (serverVersion) {
                    case v11x:
                        LOGGER.info("API '{}' already published - skipping republish", apiName);
                        break;

                    case v12x:
                        LOGGER.info("Republishing API: {}", apiName);
                        performPublish(orgName, apiName, apiVersion);
                        break;
                }
                break;

            default:
                throw new DeclarativeException(String.format(
                        "Unable to publish API '%s' in state: %s", apiName, apiState));
        }
    }

    /**
     * Trigger the publish action for the given API.
     *
     * @param orgName
     * @param apiName
     * @param apiVersion
     */
    private void performPublish(String orgName, String apiName, String apiVersion) {
        LOGGER.info("Publishing API: {}", apiName);
        ServerActionUtil.publishApi(orgName, apiName, apiVersion, serverVersion, buildServerApiClient(ActionApi.class));
    }

    /**
     * Check for the presence of an item using the given Supplier.
     *
     * @param supplier the Supplier of the item
     * @param <T>
     * @return the item or {@link Optional#empty()}
     */
    private <T> Optional<T> checkExists(Supplier<T> supplier) {
        try {
            // attempt to return the item
            return ofNullable(supplier.get());

        } catch (RetrofitError re) {
            // 404 indicates the item does not exist - anything else is an error
            if (ofNullable(re.getResponse())
                    .filter(response -> HttpURLConnection.HTTP_NOT_FOUND == response.getStatus())
                    .isPresent()) {

                return empty();
            }

            throw new DeclarativeException("Error checking for existence of existing item", re);
        }
    }

    /**
     * Load the Declaration from the given Path, using the mapper provided.
     *
     * @param path   the Path to the declaration
     * @param mapper the Mapper to use
     * @return the Declaration
     */
    public Declaration loadDeclaration(Path path, ObjectMapper mapper) {
        try (InputStream is = Files.newInputStream(path)) {
            String fileContents = CharStreams.toString(new InputStreamReader(is));
            LOGGER.trace("Declaration file raw: {}", fileContents);

            fileContents = BeanUtil.resolvePlaceholders(fileContents, properties);
            LOGGER.trace("Declaration file after resolving placeholders: {}", fileContents);

            return mapper.readValue(fileContents, Declaration.class);

        } catch (IOException e) {
            throw new DeclarativeException(e);
        }
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public void setProperties(List<String> properties) {
        this.properties = properties;
    }

    public void setServerVersion(ServerVersion serverVersion) {
        this.serverVersion = serverVersion;
    }
}