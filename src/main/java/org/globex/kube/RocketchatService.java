package org.globex.kube;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey="rocketchat")
@Path("/api/v1")
public interface RocketchatService {

    @POST
    @Path("/users.register")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    Response registerAdminUser(String payload);

    @POST
    @Path("/login")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    Response login(String payload);

    @POST
    @Path("/users.create")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    Response createUser(String payload, @HeaderParam("X-User-Id") String userId, @HeaderParam("X-Auth-Token") String token);

    @POST
    @Path("/groups.create")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    Response createGroup(String payload, @HeaderParam("X-User-Id") String userId, @HeaderParam("X-Auth-Token") String token);

    @POST
    @Path("/channels.setType")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    Response setChannelType(String payload, @HeaderParam("X-User-Id") String userId, @HeaderParam("X-Auth-Token") String token);

    @GET
    @Path("/rooms.info")
    @Produces(MediaType.APPLICATION_JSON)
    Response getRoomInfo(@QueryParam("roomName") String name, @HeaderParam("X-User-Id") String userId, @HeaderParam("X-Auth-Token") String token);

    @POST
    @Path("/integrations.create")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    Response createIntegration(String payload, @HeaderParam("X-User-Id") String userId, @HeaderParam("X-Auth-Token") String token);

    @GET
    @Path("/integrations.list")
    @Produces(MediaType.APPLICATION_JSON)
    Response listIntegrations(@HeaderParam("X-User-Id") String userId, @HeaderParam("X-Auth-Token") String token);
}
