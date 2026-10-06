package dev.undertow.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;

/** The control/preparation boundary. Tokens are never part of snapshots or worker manifests. */
public interface ServiceGateway {
    ObjectNode signIn(String code) throws Exception;

    boolean userAccess(String userToken, long repositoryId, boolean write) throws Exception;

    ObjectNode installation(String userToken, long installationId) throws Exception;

    ObjectNode repository(long installationId, long repositoryId) throws Exception;

    ObjectNode snapshot(long installationId, long repositoryId, int pr) throws Exception;

    Map<String, String> policyFiles(long installationId, long repositoryId, String commit)
            throws Exception;

    void prepare(long installationId, long repositoryId, ObjectNode snapshot, Path destination)
            throws Exception;

    default ObjectNode ciProvenance(long installation, long repository, long workflow, String head)
            throws Exception {
        return dev.undertow.reporting.Json.MAPPER.createObjectNode().put("status", "missing");
    }

    default ObjectNode importCi(
            long installation, long repository, ObjectNode snapshot, Path workspace)
            throws Exception {
        return dev.undertow.reporting.Json.MAPPER.createObjectNode();
    }
}
