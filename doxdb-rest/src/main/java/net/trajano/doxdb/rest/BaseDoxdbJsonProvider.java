package net.trajano.doxdb.rest;

import javax.annotation.PostConstruct;
import javax.json.JsonObject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * This class is extended by clients to provide a list of objects that are
 * allowed and allowed OOB references along with their schema.
 * <p>
 * Registration is done through a list of schemas provided by the
 * {@link #getRegisteredSchemaResources()} method. The DOXDB table that gets
 * created would be based on the the "$doxdb" object that is required in the
 * schema. In the future an alternate version of this provider will allow
 * passing in the contents of "$doxdb" only with a reference to a schema.
 * <p>
 * Given the following, there's no need for a "customized" WAR file for the REST
 * API. However, there still is a need actually... in case we want the web
 * resources in the same WAR... or perhaps we just have it as a separate web
 * module? Let's try separate web module first.
 *
 * <pre>
 * GET {collection}/{id : [A-Za-z0-9]{32} }
 * GET {collection}/{id : [A-Za-z0-9]{32} }/{oobname}
 * POST {collection}
 * POST {collection}/{id : [A-Za-z0-9]{32} }
 * POST {collection}/{id : [A-Za-z0-9]{32} }/{oobname}
 * POST {collection}/{operation : _[A-Za-z0-9]+}
 * DELETE {collection}/{id : [A-Za-z0-9]{32} }
 * </pre>
 *
 * @author Archimedes
 */
public abstract class BaseDoxdbJsonProvider {

    @Path("{collection}")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@PathParam("collection") final String collection,
        final JsonObject content) {

        System.out.println("collection=" + collection);
        System.out.println("content=" + content);

        // what this would do is given a *single* DoxDAO to get a JsonDoxCollection and then get the appropriate record?
        // it is expected that the requester would know what kind of collection to get?

        // events are triggered on update... it could be transactional or via MDB.  Index updates are always via MDB.

        // how do we handle security?

        return Response.ok().entity(content).build();
    }

    @DELETE
    @Path("{collection}/{id : [A-Za-z0-9]{32} }")
    public Response delete(@PathParam("collection") final String collection,
        @PathParam("id") final String id) {

        // DAO.
        return Response.ok().entity(collection + " " + id).build();
    }

    @GET
    @Path("{collection}/{id : [A-Za-z0-9]{32} }")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("collection") final String collection,
        @PathParam("id") final String id) {

        return Response.ok().entity(collection + " " + id).build();
    }

    protected abstract String[] getRegisteredSchemaResources();

    @PostConstruct
    public void init() {

        System.out.println("schemas=" + getRegisteredSchemaResources());
    }

    @POST
    @Path("{collection}/{operation : _[A-Za-z0-9]+}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response op(@PathParam("collection") final String collection,
        @PathParam("operation") final String op,
        final JsonObject content) {

        return Response.ok().entity(collection + " " + op).build();
    }

    @POST
    @Path("{collection}/{id : [A-Za-z0-9]{32} }")
    public Response save(@PathParam("collection") final String collection,
        @PathParam("id") final String id) {

        return Response.ok().entity(collection + " " + id).build();
    }

}