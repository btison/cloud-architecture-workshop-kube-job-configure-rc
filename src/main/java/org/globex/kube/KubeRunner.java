package org.globex.kube;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.jboss.resteasy.reactive.common.jaxrs.ResponseImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KubeRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KubeRunner.class);

    @Inject
    KubernetesClient client;

    @RestClient
    RocketchatService rocketchatService;

    public int run() {

        // mandatory environment variables
        String namespace = System.getenv("NAMESPACE");
        if (namespace == null || namespace.isBlank()) {
            LOGGER.error("Environment variable 'NAMESPACE' for namespace not set. Exiting...");
            return -1;
        }

        String numUsersStr = System.getenv("NUM_USERS");
        if (numUsersStr == null || numUsersStr.isBlank()) {
            LOGGER.error("Environment variable 'NUM_USERS' not set. Exiting...");
            return -1;
        }
        int numUsers = Integer.parseInt(numUsersStr);

        String outgoingWebhookUrl = System.getenv("OUTGOING_WEBHOOK_URL");
        if (outgoingWebhookUrl == null || outgoingWebhookUrl.isBlank()) {
            LOGGER.error("Environment variable 'OUTGOING_WEBHOOK_URL' not set. Exiting...");
            return -1;
        }

        // make sure rocketchat is available
        String deploymentName = System.getenv().getOrDefault("ROCKETCHAT_DEPLOYMENT", "rocketchat");

        String maxTimeToWaitStr = System.getenv().getOrDefault("MAX_TIME_TO_WAIT_MS", "60000");
        long maxTimeToWait = Long.parseLong(maxTimeToWaitStr);

        Resource<Deployment> deployment = client.apps().deployments().inNamespace(namespace).withName(deploymentName);
        try {
            deployment.waitUntilCondition(d -> d != null && Objects.equals(d.getStatus().getAvailableReplicas(), d.getStatus().getReadyReplicas()),
                    maxTimeToWait, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.error("Deployment " + deploymentName + " is not ready after " + maxTimeToWaitStr + " milliseconds. Exiting...");
            return -1;
        }
        if (deployment.get() == null) {
            LOGGER.error("Deployment " + deploymentName + " is not ready after " + maxTimeToWaitStr + " milliseconds. Exiting...");
            return -1;
        }

        // Create admin user
        String adminUsername = System.getenv().getOrDefault("ROCKETCHAT_ADMIN_USER", "rcadmin");
        String adminPassword = System.getenv().getOrDefault("ROCKETCHAT_ADMIN_PASSWORD", "rcadmin");
        String adminEmail = System.getenv().getOrDefault("ROCKETCHAT_ADMIN_EMAIL", "rcadmin@example.org");
        JsonObject registerAdminUserPayload = new JsonObject().put("name", adminUsername).put("pass", adminPassword)
                .put("email", adminEmail).put("username", adminUsername);
        try {
            rocketchatService.registerAdminUser(registerAdminUserPayload.encode());
            LOGGER.info("Admin user created");
        } catch (ClientWebApplicationException e) {
            Response response = e.getResponse();
            boolean errorFlag = true;
            if (response.getStatus() == 400) {
                JsonObject j = new JsonObject(getEntity(response));
                String error = j.getString("error");
                if (error != null && error.equals("Username is already in use")) {
                    LOGGER.warn("Admin user " + adminUsername + " already exists");
                    errorFlag = false;
                }
            }
            if (errorFlag) {
                LOGGER.error("Exception while creating the admin user. Exiting...");
                return -1;
            }
        }

        // get admin token
        String adminUserId;
        String adminToken;
        JsonObject loginPayload = new JsonObject().put("user", adminUsername).put("password", adminPassword);
        try {
            Response response = rocketchatService.login(loginPayload.encode());
            JsonObject j = new JsonObject(getEntity(response));
            adminUserId = j.getJsonObject("data").getString("userId");
            adminToken = j.getJsonObject("data").getString("authToken");
        } catch (Exception e) {
            LOGGER.error("Exception while obtaining the admin user token. Exiting...", e);
            return -1;
        }

        // set up RocketChat
        String bot = System.getenv().getOrDefault("BOT_NAME", "Globex.support");
        String supportGroup = System.getenv().getOrDefault("GLOBEX_SUPPORT_GROUP", "GlobexSupport");

        String userPrefix = System.getenv().getOrDefault("USER_PREFIX", "user");
        String userPassword = System.getenv().getOrDefault("USER_PASSWORD", "openshift");

        String numUserStart = System.getenv().getOrDefault("NUM_USERS_START", "1");
        int numUsersStart = Integer.parseInt(numUserStart);

        String channelPrefix = System.getenv().getOrDefault("CHANNEL_PREFIX", "GlobexSupport");

        // create bot
        boolean errorFlag = false;
        JsonObject createBotPayload = new JsonObject().put("name", bot).put("email", bot + "@example.org")
                .put("password",userPassword + "-bot").put("username", bot).put("roles", new JsonArray().add("bot"))
                .put("verified", true);
        try {
            rocketchatService.createUser(createBotPayload.encode(), adminUserId, adminToken);
            LOGGER.info("User " + bot + " created");
        } catch (ClientWebApplicationException e) {
            errorFlag = true;
            Response response = e.getResponse();
            if (response.getStatus() == 400) {
                JsonObject j = new JsonObject(getEntity(response));
                String error = j.getString("error");
                if (error != null && error.contains(bot + " is already in use")) {
                    LOGGER.warn("User " + bot + " already exists");
                    errorFlag = false;
                }
            }
        }
        if (errorFlag) {
            LOGGER.error("Exception while creating user " + bot + ". Exiting...");
            return -1;
        }

        // create support group
        JsonObject createSupportGroupPayload = new JsonObject().put("name", supportGroup)
                .put("members", new JsonArray().add(bot));
        try {
            rocketchatService.createGroup(createSupportGroupPayload.encode(), adminUserId, adminToken);
            LOGGER.info("Channel " + supportGroup + " created");
        } catch (ClientWebApplicationException e) {
            errorFlag = true;
            Response response = e.getResponse();
            if (response.getStatus() == 400) {
                JsonObject j = new JsonObject(getEntity(response));
                String errorType = j.getString("errorType");
                if (errorType != null && errorType.equals("error-duplicate-channel-name")) {
                    LOGGER.warn("Channel " + supportGroup + " already exists");
                    errorFlag = false;
                }
            }
        }
        if (errorFlag) {
            LOGGER.error("Exception while creating user " + bot + ". Exiting...");
            return -1;
        }

        for (int i = numUsersStart; i <= numUsers; i++) {
            // create user
            String user = userPrefix + i;
            JsonObject createUserPayload = new JsonObject().put("name", user).put("email", user + "@example.org")
                    .put("password", userPassword).put("username", user).put("verified", true);
            try {
                rocketchatService.createUser(createUserPayload.encode(), adminUserId, adminToken);
                LOGGER.info("User " + user + " created");
            } catch (ClientWebApplicationException e) {
                errorFlag = true;
                Response response = e.getResponse();
                if (response.getStatus() == 400) {
                    JsonObject j = new JsonObject(getEntity(response));
                    String error = j.getString("error");
                    if (error != null && error.contains(user + " is already in use")) {
                        LOGGER.warn("User " + user + " already exists");
                        errorFlag = false;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            //create private group for users
            String group = channelPrefix + i;
            JsonObject createGroupPayload = new JsonObject().put("name", group)
                    .put("members", new JsonArray().add(user).add(bot));
            try {
                rocketchatService.createGroup(createGroupPayload.encode(), adminUserId, adminToken);
                LOGGER.info("Channel " + group + " created");
            } catch (ClientWebApplicationException e) {
                errorFlag = true;
                Response response = e.getResponse();
                if (response.getStatus() == 400) {
                    JsonObject j = new JsonObject(getEntity(response));
                    String errorType = j.getString("errorType");
                    if (errorType != null && errorType.equals("error-duplicate-channel-name")) {
                        LOGGER.warn("Channel " + group + " already exists");
                        errorFlag = false;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        if (errorFlag) {
            LOGGER.error("Exception while creating users, channels and webhooks. Exiting...");
            return -1;
        }

        //create outgoing webhook
        String outgoingIntegration = "outgoing_webhook";
        String outgoingIntegrationScript = """
                class Script {

                    prepare_outgoing_request({ request }) {

                        let match;
                        match = request.data.user_name.match('%s');
                        if (match) {
                            return;
                        }
                        return request;
                    }

                    process_outgoing_response({ request, response }) {
                        return;
                    }

                }
                """.formatted(bot);

        JsonObject json = new JsonObject(getEntity(rocketchatService.listIntegrations(adminUserId, adminToken)));
        JsonArray integrations = json.getJsonArray("integrations");
        if (integrations == null) {
            LOGGER.warn("Error getting list of integrations");
            integrations = new JsonArray();
        }
        boolean found = integrations.stream().map(o -> (JsonObject)o)
                .anyMatch(j -> (outgoingIntegration.equals(j.getString("name")) && "webhook-outgoing".equals(j.getString("type"))));
        if (found) {
            LOGGER.warn("Outgoing webhook already exists");
        } else {
            JsonObject createOutgoingIntegrationPayload = new JsonObject().put("type", "webhook-outgoing")
                    .put("name", outgoingIntegration).put("enabled", true).put("username", "rcadmin")
                    .put("urls", new JsonArray().add(outgoingWebhookUrl)).put("scriptEnabled", true)
                    .put("script", outgoingIntegrationScript)
                    .put("channel", "all_private_groups").put("event", "sendMessage");
            try {
                rocketchatService.createIntegration(createOutgoingIntegrationPayload.encode(), adminUserId, adminToken);
                LOGGER.info("Outgoing webhook created");
            } catch (ClientWebApplicationException e) {
                errorFlag = true;
            }

            if (errorFlag) {
                LOGGER.error("Exception while outgoing webhook. Exiting...");
                return -1;
            }
        }

        String incomingIntegration = "incoming_webhook";
        String incomingIntegrationScript = """
                class Script {

                    process_incoming_request({ request }) {
                        return {
                            content:{
                                channel: request.content.channel,
                                text: request.content.text
                            }
                        }
                    }
                }
                """;
        found = integrations.stream().map(o -> (JsonObject)o)
                .anyMatch(j -> (incomingIntegration.equals(j.getString("name")) && "webhook-incoming".equals(j.getString("type"))));
        if (found) {
            LOGGER.warn("Incoming webhook already exists");
        } else {
            JsonObject createIncomingIntegrationPayload = new JsonObject().put("type", "webhook-incoming")
                    .put("name", incomingIntegration).put("enabled", true).put("username", bot)
                    .put("scriptEnabled", true).put("script", incomingIntegrationScript).put("channel", "#" + supportGroup);
            try {
                Response response = rocketchatService.createIntegration(createIncomingIntegrationPayload.encode(), adminUserId, adminToken);
                JsonObject j = new JsonObject(getEntity(response));
                System.out.println(j.encodePrettily());
                LOGGER.info("Incoming webhook created");
            } catch (ClientWebApplicationException e) {
                Response response = e.getResponse();
                JsonObject j = new JsonObject(getEntity(response));
                System.out.println(j.encodePrettily());
                errorFlag = true;
            }

            if (errorFlag) {
                LOGGER.error("Exception while outgoing webhook. Exiting...");
                return -1;
            }
        }

        return 0;
    }

    private String getEntity(Response response) {
        ResponseImpl responseImpl = (ResponseImpl) response;
        InputStream is = responseImpl.getEntityStream();
        if (is != null) {
            try {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return "{}";
    }

}
