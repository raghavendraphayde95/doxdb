package net.trajano.doxdb.rest;

import javax.persistence.OptimisticLockException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.sun.messaging.jmq.io.Status;

@Provider
public class OptimisticLockingMapper implements
    ExceptionMapper<OptimisticLockException> {

    @Override
    public Response toResponse(final OptimisticLockException e) {

        return Response.status(Status.CONFLICT).entity("Optimistic lock conflict").build();
    }

}